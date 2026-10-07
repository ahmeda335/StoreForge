package dev.storeforge.checkout.internal;

import dev.storeforge.checkout.internal.CheckoutDtos.AddItemRequest;
import dev.storeforge.checkout.internal.CheckoutDtos.CartResponse;
import dev.storeforge.checkout.internal.CheckoutDtos.CreatedCartResponse;
import dev.storeforge.checkout.internal.CheckoutDtos.SetQuantityRequest;
import dev.storeforge.stores.CurrentStorefront;
import dev.storeforge.stores.StoreInfo;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous carts on the storefront. Store from the hostname; cart access by {@code X-Cart-Token}. */
@RestController
@RequestMapping("/storefront/v1/carts")
@RequiredArgsConstructor
class StorefrontCartController {

    static final String CART_TOKEN = "X-Cart-Token";

    private final CartService carts;

    @PostMapping
    ResponseEntity<CreatedCartResponse> create(@CurrentStorefront StoreInfo store) {
        var created = carts.create(store);
        return ResponseEntity.created(URI.create("/storefront/v1/carts/" + created.cartId()))
                .cacheControl(CacheControl.noStore())   // the body holds a secret
                .body(new CreatedCartResponse(created.cartId(), created.cartToken()));
    }

    @GetMapping("/{cartId}")
    CartResponse get(@CurrentStorefront StoreInfo store, @PathVariable UUID cartId,
                     @RequestHeader(name = CART_TOKEN, required = false) @Nullable String token) {
        return CartResponse.of(carts.view(store, cartId, token));
    }

    @PostMapping("/{cartId}/items")
    CartResponse addItem(@CurrentStorefront StoreInfo store, @PathVariable UUID cartId,
                         @RequestHeader(name = CART_TOKEN, required = false) @Nullable String token,
                         @Valid @RequestBody AddItemRequest body) {
        return CartResponse.of(carts.addItem(store, cartId, token, body.variantId(), body.quantity()));
    }

    @PatchMapping("/{cartId}/items/{variantId}")
    CartResponse setQuantity(@CurrentStorefront StoreInfo store, @PathVariable UUID cartId, @PathVariable UUID variantId,
                             @RequestHeader(name = CART_TOKEN, required = false) @Nullable String token,
                             @Valid @RequestBody SetQuantityRequest body) {
        return CartResponse.of(carts.setQuantity(store, cartId, token, variantId, body.quantity()));
    }

    @DeleteMapping("/{cartId}/items/{variantId}")
    CartResponse removeItem(@CurrentStorefront StoreInfo store, @PathVariable UUID cartId, @PathVariable UUID variantId,
                            @RequestHeader(name = CART_TOKEN, required = false) @Nullable String token) {
        return CartResponse.of(carts.setQuantity(store, cartId, token, variantId, 0));
    }
}
