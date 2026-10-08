package dev.storeforge.discounts.internal;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Merchant discount codes ({@code discounts:manage}). Amounts are decimals in the store currency, percentages like "10.5". */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/discounts")
@RequiredArgsConstructor
class AdminDiscountsController {

    private final DiscountService discounts;
    private final StoreDirectory stores;

    record CreateRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,40}", message = "3-40 characters: letters, digits, _ and -") String code,
                         @NotBlank @Pattern(regexp = "PERCENTAGE|FIXED_AMOUNT") String kind,
                         @NotBlank @Pattern(regexp = "\\d{1,9}(\\.\\d{1,2})?", message = "a decimal like 10 or 12.50") String value,
                         @Nullable @Pattern(regexp = "\\d{1,9}(\\.\\d{1,2})?") String minimumSubtotal,
                         @Nullable Instant startsAt, @Nullable Instant endsAt, @Nullable @Min(1) Integer usageLimit,
                         @Nullable Boolean oncePerCustomer) {
    }

    record UpdateRequest(@Nullable @Pattern(regexp = "ACTIVE|DISABLED") String status, @Nullable Instant endsAt,
                         @Nullable @Min(1) Integer usageLimit, @Nullable Boolean clearEndsAt, @Nullable Boolean clearUsageLimit) {
    }

    record DiscountResponse(UUID id, String code, String kind, String value, String minimumSubtotal, Instant startsAt, @Nullable Instant endsAt,
                            @Nullable Integer usageLimit, int usageCount, boolean oncePerCustomer, String status, Instant createdAt) {
        static DiscountResponse of(DiscountStore.Discount d, StoreInfo store) {
            String value = "PERCENTAGE".equals(d.kind()) ? BigDecimal.valueOf(d.value(), 2).toPlainString()
                    : Money.ofMinor(d.value(), store.currency()).toDecimalString();
            return new DiscountResponse(d.id(), d.code(), d.kind(), value, Money.ofMinor(d.minimumSubtotalMinor(), store.currency()).toDecimalString(),
                    d.startsAt(), d.endsAt(), d.usageLimit(), d.usageCount(), d.oncePerCustomer(), d.status(), d.createdAt());
        }
    }

    @PostMapping
    ResponseEntity<DiscountResponse> create(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                            @Valid @RequestBody CreateRequest body) {
        StoreInfo store = store(storeId);
        long value = "PERCENTAGE".equals(body.kind())
                ? new BigDecimal(body.value()).movePointRight(2).longValueExact()             // percent → basis points
                : Money.parse(body.value(), store.currency()).amountMinor();
        long minimum = body.minimumSubtotal() == null ? 0 : Money.parse(body.minimumSubtotal(), store.currency()).amountMinor();
        var created = discounts.create(user.userId(), StoreId.of(storeId), new DiscountService.NewDiscount(body.code(), body.kind(), value,
                minimum, body.startsAt(), body.endsAt(), body.usageLimit(), Boolean.TRUE.equals(body.oncePerCustomer())));
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/discounts/" + created.id()))
                .body(DiscountResponse.of(created, store));
    }

    @GetMapping
    Map<String, List<DiscountResponse>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        StoreInfo store = store(storeId);
        return Map.of("data", discounts.list(user.userId(), StoreId.of(storeId)).stream().map(d -> DiscountResponse.of(d, store)).toList());
    }

    @PatchMapping("/{discountId}")
    DiscountResponse update(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID discountId,
                            @Valid @RequestBody UpdateRequest body) {
        return DiscountResponse.of(discounts.update(user.userId(), StoreId.of(storeId), discountId, body.status(), body.endsAt(), body.usageLimit(),
                Boolean.TRUE.equals(body.clearEndsAt()), Boolean.TRUE.equals(body.clearUsageLimit())), store(storeId));
    }

    private StoreInfo store(UUID storeId) {
        return stores.find(StoreId.of(storeId)).orElseThrow(DiscountService.NotFound::new);
    }
}
