package dev.storeforge.checkout.internal;

import dev.storeforge.catalog.SellableVariant;
import dev.storeforge.catalog.VariantLookup;
import dev.storeforge.checkout.internal.CartStore.Cart;
import dev.storeforge.checkout.internal.CheckoutStore.Line;
import dev.storeforge.inventory.Inventory;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.error.ConstraintViolations;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.StoreInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Anonymous carts. Creating a cart returns a random token ONCE; every later call must present it
 * ({@code X-Cart-Token}). The cart id alone is not enough: ids appear in URLs and logs, tokens don't.
 */
@Service
@RequiredArgsConstructor
class CartService {

    static final Duration CART_TTL = Duration.ofDays(30);
    static final int MAX_LINES = 50;

    private final CartStore carts;
    private final Pricing pricing;
    private final VariantLookup variants;
    private final Inventory inventory;
    private final TenantTransactions tenant;
    private final Clock clock;

    record CreatedCart(UUID cartId, String cartToken) {
        @Override
        public String toString() {
            return "CreatedCart[" + cartId + ", token=REDACTED]";
        }
    }

    record CartLine(Line line, boolean sellable, boolean inStock) {
    }

    record CartView(UUID id, String status, String currency, List<CartLine> lines, long subtotalMinor, int itemCount) {
    }

    CreatedCart create(StoreInfo store) {
        UUID cartId = Ids.newId();
        String token = SecretTokens.generate();
        Instant now = clock.instant();
        tenant.runInStore(store.id(), () -> carts.create(store.id(), cartId, SecretTokens.hash(token), now.plus(CART_TTL), now));
        return new CreatedCart(cartId, token);
    }

    CartView view(StoreInfo store, UUID cartId, @Nullable String token) {
        return tenant.inStore(store.id(), () -> render(store, requireCart(store, cartId, token)));
    }

    CartView addItem(StoreInfo store, UUID cartId, @Nullable String token, UUID variantId, int quantity) {
        return tenant.inStore(store.id(), () -> {
            Cart cart = requireOpenCart(store, cartId, token);
            SellableVariant variant = variants.find(store.id(), List.of(variantId)).get(variantId);
            if (variant == null || !variant.sellable()) {
                throw new CheckoutExceptions.ItemUnavailable(variantId);
            }
            if (carts.lineCount(store.id(), cartId) >= MAX_LINES
                    && carts.items(store.id(), cartId).stream().noneMatch(item -> item.variantId().equals(variantId))) {
                throw new CheckoutExceptions.CartFull(MAX_LINES);
            }
            try {
                carts.addItem(store.id(), cartId, variantId, quantity, clock.instant());
            } catch (DataIntegrityViolationException e) {
                if (ConstraintViolations.isCheckViolation(e, "ck_cart_items_quantity")) {
                    throw new CheckoutExceptions.QuantityLimit();
                }
                throw e;
            }
            return render(store, cart);
        });
    }

    /** Quantity 0 removes the line. */
    CartView setQuantity(StoreInfo store, UUID cartId, @Nullable String token, UUID variantId, int quantity) {
        return tenant.inStore(store.id(), () -> {
            Cart cart = requireOpenCart(store, cartId, token);
            if (quantity == 0) {
                carts.removeItem(store.id(), cartId, variantId);
            } else if (carts.setQuantity(store.id(), cartId, variantId, quantity, clock.instant()) == 0) {
                throw new CheckoutExceptions.ItemUnavailable(variantId);
            }
            return render(store, cart);
        });
    }

    Cart requireCart(StoreInfo store, UUID cartId, @Nullable String token) {
        if (token == null || token.isBlank()) {
            throw new CheckoutExceptions.CartNotFound();
        }
        return carts.find(store.id(), cartId, SecretTokens.hash(token)).orElseThrow(CheckoutExceptions.CartNotFound::new);
    }

    private Cart requireOpenCart(StoreInfo store, UUID cartId, @Nullable String token) {
        Cart cart = requireCart(store, cartId, token);
        if (!cart.open(clock.instant())) {
            throw new CheckoutExceptions.CartClosed();
        }
        return cart;
    }

    /** Live view: current catalog prices and stock. Nothing here is a promise until checkout completion. */
    private CartView render(StoreInfo store, Cart cart) {
        var items = carts.items(store.id(), cart.id());
        var priced = pricing.price(store.id(), items);
        Map<UUID, Integer> stock = inventory.availability(store.id(), items.stream().map(CartStore.Item::variantId).toList());
        List<CartLine> lines = new java.util.ArrayList<>();
        for (Line line : priced.lines()) {
            boolean inStock = !stock.containsKey(line.variantId()) ? false
                    : stock.get(line.variantId()) == null || stock.get(line.variantId()) >= line.quantity();
            lines.add(new CartLine(line, true, inStock));
        }
        priced.unavailable().forEach(line -> lines.add(new CartLine(line, false, false)));
        int itemCount = items.stream().mapToInt(CartStore.Item::quantity).sum();
        return new CartView(cart.id(), cart.status(), store.currency().getCurrencyCode(), lines, priced.subtotal(), itemCount);
    }
}
