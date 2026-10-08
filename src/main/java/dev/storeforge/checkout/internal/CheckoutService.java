package dev.storeforge.checkout.internal;

import dev.storeforge.checkout.internal.CartStore.Cart;
import dev.storeforge.checkout.internal.CheckoutStore.Address;
import dev.storeforge.checkout.internal.CheckoutStore.Checkout;
import dev.storeforge.checkout.internal.CheckoutStore.Line;
import dev.storeforge.customers.Customers;
import dev.storeforge.discounts.DiscountUnavailableException;
import dev.storeforge.discounts.Discounts;
import dev.storeforge.inventory.Inventory;
import dev.storeforge.inventory.ReservationLine;
import dev.storeforge.orders.Orders;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.email.EmailAddress;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.StoreInfo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class CheckoutService {

    private final CheckoutStore checkouts;
    private final CartStore carts;
    private final CartService cartService;
    private final Pricing pricing;
    private final Inventory inventory;
    private final Customers customers;
    private final Orders orders;
    private final Discounts discounts;
    private final TenantTransactions tenant;
    private final ObservationRegistry observations;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    record CheckoutView(UUID id, UUID cartId, String status, @Nullable String email, @Nullable Address shippingAddress,
                        String currency, List<Line> lines, List<Line> unavailable, long subtotalMinor, @Nullable DiscountView discount,
                        long shippingMinor, long taxMinor, long totalMinor, @Nullable UUID customerId, @Nullable Instant completedAt,
                        @Nullable Instant reservationExpiresAt, @Nullable UUID orderId, @Nullable Long orderNumber) {
    }

    /** The code in the checkout, evaluated live while OPEN (it can stop applying: shown, not hidden). */
    record DiscountView(String code, long amountMinor, boolean applicable, @Nullable String reason) {
    }

    record Started(CheckoutView view, boolean created) {
    }

    /** One OPEN checkout per cart: starting again returns the existing one (safe to retry). */
    Started start(StoreInfo store, UUID cartId, @Nullable String cartToken, @Nullable String email, @Nullable Address address) {
        return tenant.inStore(store.id(), () -> {
            Cart cart = cartService.requireCart(store, cartId, cartToken);
            if (!cart.open(clock.instant())) {
                throw new CheckoutExceptions.CartClosed();
            }
            if (carts.items(store.id(), cartId).isEmpty()) {
                throw new CheckoutExceptions.CartEmpty();
            }
            var existing = checkouts.openForCart(store.id(), cartId);
            UUID checkoutId = existing.map(Checkout::id).orElseGet(Ids::newId);
            if (existing.isEmpty()) {
                checkouts.create(store.id(), checkoutId, cartId, store.currency().getCurrencyCode(), clock.instant());
            }
            checkouts.updateContact(store.id(), checkoutId, normalize(email), address, clock.instant());
            return new Started(view(store, find(store, checkoutId, cartToken, false)), existing.isEmpty());
        });
    }

    CheckoutView get(StoreInfo store, UUID checkoutId, @Nullable String cartToken) {
        return tenant.inStore(store.id(), () -> view(store, find(store, checkoutId, cartToken, false)));
    }

    /** The order of a completed checkout; the cart token proves the caller is the shopper. */
    UUID orderOf(StoreInfo store, UUID checkoutId, @Nullable String cartToken) {
        return tenant.inStore(store.id(), () -> {
            Checkout checkout = find(store, checkoutId, cartToken, false);
            if (checkout.orderId() == null) {
                throw new CheckoutExceptions.CheckoutNotCompleted();
            }
            return checkout.orderId();
        });
    }

    /** Applies a discount code to an open checkout. One code per checkout: applying another replaces it. */
    CheckoutView applyDiscount(StoreInfo store, UUID checkoutId, @Nullable String cartToken, String code) {
        return tenant.inStore(store.id(), () -> {
            Checkout checkout = find(store, checkoutId, cartToken, true);
            if (!"OPEN".equals(checkout.status())) {
                throw new CheckoutExceptions.CheckoutNotOpen();
            }
            var priced = pricing.price(store.id(), carts.items(store.id(), checkout.cartId()));
            var evaluation = discounts.evaluate(store.id(), code, priced.subtotal(), checkout.email(), clock.instant());
            if (!evaluation.applicable()) {
                throw new CheckoutExceptions.DiscountNotApplicable(evaluation.reason());
            }
            checkouts.setDiscountCode(store.id(), checkoutId, evaluation.code(), clock.instant());
            return view(store, find(store, checkoutId, cartToken, false));
        });
    }

    CheckoutView removeDiscount(StoreInfo store, UUID checkoutId, @Nullable String cartToken) {
        return tenant.inStore(store.id(), () -> {
            Checkout checkout = find(store, checkoutId, cartToken, true);
            if (!"OPEN".equals(checkout.status())) {
                throw new CheckoutExceptions.CheckoutNotOpen();
            }
            checkouts.setDiscountCode(store.id(), checkoutId, null, clock.instant());
            return view(store, find(store, checkoutId, cartToken, false));
        });
    }

    CheckoutView update(StoreInfo store, UUID checkoutId, @Nullable String cartToken, @Nullable String email, @Nullable Address address) {
        return tenant.inStore(store.id(), () -> {
            Checkout checkout = find(store, checkoutId, cartToken, true);
            if (!"OPEN".equals(checkout.status())) {
                throw new CheckoutExceptions.CheckoutNotOpen();
            }
            checkouts.updateContact(store.id(), checkoutId, normalize(email), address, clock.instant());
            return view(store, find(store, checkoutId, cartToken, false));
        });
    }

    /**
     * Completion, all in ONE transaction (joined by the idempotency record):
     * lock checkout → validate contact → price from catalog → compare with the total the customer
     * saw → reserve stock (all-or-nothing) → record customer → freeze lines and totals → PLACE THE
     * ORDER → close cart. Any failure rolls back everything, including the reservations and the order.
     */
    CheckoutView complete(StoreInfo store, UUID checkoutId, @Nullable String cartToken, String expectedTotal) {
        return Observation.createNotStarted("storeforge.checkout.complete", observations)
                .contextualName("checkout.complete")
                .highCardinalityKeyValue("storeforge.checkout_id", checkoutId.toString())
                .observe(() -> tenant.inStore(store.id(), () -> doComplete(store, checkoutId, cartToken, expectedTotal)));
    }

    private CheckoutView doComplete(StoreInfo store, UUID checkoutId, @Nullable String cartToken, String expectedTotal) {
        Checkout checkout = find(store, checkoutId, cartToken, true);
        if (!"OPEN".equals(checkout.status())) {
            throw new CheckoutExceptions.CheckoutNotOpen();
        }
        List<String> missing = new ArrayList<>();
        if (checkout.email() == null) {
            missing.add("email");
        }
        if (checkout.address() == null) {
            missing.add("shippingAddress");
        }
        if (!missing.isEmpty()) {
            throw new CheckoutExceptions.CheckoutIncomplete(missing);
        }

        var items = carts.items(store.id(), checkout.cartId());
        if (items.isEmpty()) {
            throw new CheckoutExceptions.CartEmpty();
        }
        var priced = pricing.price(store.id(), items);
        if (!priced.unavailable().isEmpty()) {
            throw new CheckoutExceptions.ItemUnavailable(priced.unavailable().getFirst().variantId());
        }
        Instant now = clock.instant();
        long discount = 0;
        if (checkout.discountCode() != null) {
            // The code may have expired or been used up since the customer saw the total: fail clearly, never charge more silently.
            var evaluation = discounts.evaluate(store.id(), checkout.discountCode(), priced.subtotal(), checkout.email(), now);
            if (!evaluation.applicable()) {
                meterRegistry.counter("storeforge.checkouts.completions", "outcome", "discount_unavailable").increment();
                throw new DiscountUnavailableException(evaluation.reason());
            }
            discount = evaluation.amountMinor();
        }
        long total = priced.total() - discount;
        Money expected = parse(expectedTotal, store);
        if (expected.amountMinor() != total) {
            meterRegistry.counter("storeforge.checkouts.completions", "outcome", "total_changed").increment();
            throw new CheckoutExceptions.TotalChanged(expected.toDecimalString(),
                    Money.ofMinor(total, store.currency()).toDecimalString());
        }

        inventory.reserve(store.id(), checkoutId,
                priced.lines().stream().map(line -> new ReservationLine(line.variantId(), line.quantity())).toList(),
                orders.reservationHold());
        String[] name = splitName(checkout.address().name());
        UUID customerId = customers.findOrCreate(store.id(), checkout.email(), name[0], name[1]);
        checkouts.insertLines(store.id(), checkoutId, priced.lines());
        Address address = checkout.address();
        var placed = orders.place(store.id(), new Orders.OrderPlacement(checkoutId, customerId, checkout.email(), checkout.currency(),
                new Orders.ShippingAddress(address.name(), address.line1(), address.line2(), address.city(), address.region(),
                        address.postalCode(), address.country()),
                priced.lines().stream().map(line -> new Orders.PlacedLine(line.variantId(), line.productTitle(), line.variantTitle(),
                        line.sku(), line.unitPriceMinor(), line.quantity(), line.lineTotalMinor())).toList(),
                priced.subtotal(), discount, checkout.discountCode(), priced.shipping(), priced.tax(), total));
        if (checkout.discountCode() != null) {
            // Takes the use in THIS transaction: if anything later fails, the rollback gives it back.
            discounts.redeem(store.id(), checkout.discountCode(), checkoutId, placed.orderId(), priced.subtotal(), checkout.email(), now);
        }
        checkouts.complete(store.id(), checkoutId, customerId, placed.orderId(), priced.subtotal(), discount, priced.shipping(), priced.tax(),
                total, placed.paymentDueAt(), now);
        carts.markConverted(store.id(), checkout.cartId(), now);
        meterRegistry.counter("storeforge.checkouts.completions", "outcome", "completed").increment();
        return view(store, find(store, checkoutId, cartToken, false));
    }

    private Checkout find(StoreInfo store, UUID checkoutId, @Nullable String cartToken, boolean lock) {
        if (cartToken == null || cartToken.isBlank()) {
            throw new CheckoutExceptions.CheckoutNotFound();
        }
        return checkouts.find(store.id(), checkoutId, SecretTokens.hash(cartToken), lock)
                .orElseThrow(CheckoutExceptions.CheckoutNotFound::new);
    }

    /** OPEN: priced live from the catalog. COMPLETED: the frozen snapshot, regardless of later catalog changes. */
    private CheckoutView view(StoreInfo store, Checkout checkout) {
        if ("OPEN".equals(checkout.status())) {
            var priced = pricing.price(store.id(), carts.items(store.id(), checkout.cartId()));
            DiscountView discount = null;
            if (checkout.discountCode() != null) {
                var e = discounts.evaluate(store.id(), checkout.discountCode(), priced.subtotal(), checkout.email(), clock.instant());
                discount = new DiscountView(e.code(), e.amountMinor(), e.applicable(), e.reason());
            }
            long discountMinor = discount == null ? 0 : discount.amountMinor();
            return new CheckoutView(checkout.id(), checkout.cartId(), checkout.status(), checkout.email(), checkout.address(),
                    checkout.currency(), priced.lines(), priced.unavailable(), priced.subtotal(), discount, priced.shipping(), priced.tax(),
                    priced.total() - discountMinor, null, null, null, null, null);
        }
        DiscountView frozen = checkout.discountCode() == null ? null
                : new DiscountView(checkout.discountCode(), checkout.discountMinor() == null ? 0 : checkout.discountMinor(), true, null);
        return new CheckoutView(checkout.id(), checkout.cartId(), checkout.status(), checkout.email(), checkout.address(),
                checkout.currency(), checkouts.lines(store.id(), checkout.id()), List.of(), checkout.subtotalMinor(), frozen,
                checkout.shippingMinor(), checkout.taxMinor(), checkout.totalMinor(), checkout.customerId(),
                checkout.completedAt(), checkout.reservationExpiresAt(), checkout.orderId(), checkout.orderNumber());
    }

    private static Money parse(String amount, StoreInfo store) {
        try {
            return Money.parse(amount, store.currency());
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new CheckoutExceptions.InvalidAmount();
        }
    }

    private static @Nullable String normalize(@Nullable String email) {
        return email == null ? null : EmailAddress.of(email).value();
    }

    private static String[] splitName(String fullName) {
        String trimmed = fullName.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? new String[] {trimmed, null} : new String[] {trimmed.substring(0, space), trimmed.substring(space + 1).trim()};
    }
}
