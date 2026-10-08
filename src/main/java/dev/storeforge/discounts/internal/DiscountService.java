package dev.storeforge.discounts.internal;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.discounts.DiscountUnavailableException;
import dev.storeforge.discounts.Discounts;
import dev.storeforge.jobs.Event;
import dev.storeforge.jobs.EventHandler;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.shared.error.ConstraintViolations;
import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class DiscountService implements Discounts, EventHandler {

    private final DiscountStore discounts;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final Clock clock;

    record NewDiscount(String code, String kind, long value, long minimumSubtotalMinor, @Nullable Instant startsAt, @Nullable Instant endsAt,
                       @Nullable Integer usageLimit, boolean oncePerCustomer) {
    }

    // ---- checkout ----------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Evaluation evaluate(StoreId storeId, String code, long subtotalMinor, @Nullable String customerEmail, Instant now) {
        var found = discounts.byCode(storeId, code.trim());
        boolean used = found.isPresent() && found.get().oncePerCustomer() && customerEmail != null
                && discounts.usedByCustomer(storeId, found.get().id(), customerEmail);
        return DiscountRules.evaluate(found.map(DiscountStore.Discount::rule).orElse(null), code.trim(), subtotalMinor, used, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long redeem(StoreId storeId, String code, UUID checkoutId, UUID orderId, long subtotalMinor, String customerEmail, Instant now) {
        Evaluation evaluation = evaluate(storeId, code, subtotalMinor, customerEmail, now);
        if (!evaluation.applicable()) {
            throw new DiscountUnavailableException(evaluation.reason());
        }
        DiscountStore.Discount discount = discounts.byCode(storeId, code.trim()).orElseThrow();
        if (!discounts.takeUse(storeId, discount.id(), now)) {
            throw new DiscountUnavailableException("usage_limit_reached");   // lost the race for the last use
        }
        try {
            discounts.insertRedemption(storeId, discount.id(), checkoutId, orderId, evaluation.amountMinor(),
                    discount.oncePerCustomer() ? customerEmail : null, now);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isUniqueViolation(e, "uq_discount_redemptions_customer")) {
                throw new DiscountUnavailableException("already_used");   // the same customer, in a parallel checkout
            }
            throw e;
        }
        return evaluation.amountMinor();
    }

    // ---- unpaid orders give their use back --------------------------------------------------------

    @Override
    public String name() {
        return "discount-releases";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OrderEvents.CANCELLED);
    }

    /** An order cancelled or expired unpaid returns its code use: otherwise unpaid orders could burn single-use codes. */
    @Override
    public void handle(Event event) {
        UUID orderId = UUID.fromString(event.payload().get("orderId").asString());
        tenant.runInStore(event.storeId(), () -> discounts.releaseForOrder(event.storeId(), orderId));
    }

    // ---- merchant -------------------------------------------------------------------------------------

    DiscountStore.Discount create(UUID userId, StoreId storeId, NewDiscount command) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.DISCOUNTS_MANAGE);
            Instant now = clock.instant();
            UUID id;
            try {
                id = discounts.insert(storeId, command.code().trim(), command.kind(), command.value(), command.minimumSubtotalMinor(),
                        command.startsAt() == null ? now : command.startsAt(), command.endsAt(), command.usageLimit(), command.oncePerCustomer(), now);
            } catch (DataIntegrityViolationException e) {
                if (ConstraintViolations.isUniqueViolation(e, "uq_discounts_store_code")) {
                    throw new CodeTaken();
                }
                throw new InvalidDiscount();
            }
            audit.recordUserAction(storeId, userId, "discount.created", "discount", id.toString(), Map.of("code", command.code().trim()));
            return discounts.byId(storeId, id).orElseThrow();
        });
    }

    List<DiscountStore.Discount> list(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.DISCOUNTS_MANAGE);
            return discounts.list(storeId);
        });
    }

    DiscountStore.Discount update(UUID userId, StoreId storeId, UUID id, @Nullable String status, @Nullable Instant endsAt,
                                  @Nullable Integer usageLimit, boolean clearEndsAt, boolean clearUsageLimit) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.DISCOUNTS_MANAGE);
            DiscountStore.Discount current = discounts.byId(storeId, id).orElseThrow(NotFound::new);
            try {
                discounts.update(storeId, id, status == null ? current.status() : status,
                        clearEndsAt ? null : endsAt == null ? current.endsAt() : endsAt,
                        clearUsageLimit ? null : usageLimit == null ? current.usageLimit() : usageLimit, clock.instant());
            } catch (DataIntegrityViolationException e) {
                throw new InvalidDiscount();   // e.g. a usage limit below the uses already taken
            }
            audit.recordUserAction(storeId, userId, "discount.updated", "discount", id.toString(), Map.of("status", status == null ? "" : status));
            return discounts.byId(storeId, id).orElseThrow();
        });
    }

    static class NotFound extends StoreForgeException {
        NotFound() {
            super(HttpStatus.NOT_FOUND, "discount_not_found", "Discount not found.");
        }
    }

    static class CodeTaken extends StoreForgeException {
        CodeTaken() {
            super(HttpStatus.CONFLICT, "discount_code_taken", "This store already has a discount with this code.");
        }
    }

    static class InvalidDiscount extends StoreForgeException {
        InvalidDiscount() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_discount",
                    "Invalid discount: code 3-40 of A-Z 0-9 _ -, percentage ≤ 100.00, end after start, usage limit ≥ uses so far.");
        }
    }
}
