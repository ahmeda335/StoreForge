package dev.storeforge.checkout.internal;

import dev.storeforge.checkout.internal.CheckoutDtos.AddressDto;
import dev.storeforge.checkout.internal.CheckoutDtos.CheckoutResponse;
import dev.storeforge.checkout.internal.CheckoutDtos.CompletionRequest;
import dev.storeforge.checkout.internal.CheckoutDtos.StartCheckoutRequest;
import dev.storeforge.checkout.internal.CheckoutDtos.UpdateCheckoutRequest;
import dev.storeforge.checkout.internal.CheckoutStore.Address;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.idempotency.Idempotency;
import dev.storeforge.stores.CurrentStorefront;
import dev.storeforge.stores.StoreInfo;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Checkout sessions. Authorized by the cart token of the cart they were started from. */
@RestController
@RequestMapping("/storefront/v1/checkouts")
class StorefrontCheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CheckoutService checkouts;
    private final Idempotency idempotency;

    StorefrontCheckoutController(CheckoutService checkouts, Idempotency idempotency) {
        this.checkouts = checkouts;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<CheckoutResponse> start(@CurrentStorefront StoreInfo store,
                                           @RequestHeader(name = StorefrontCartController.CART_TOKEN, required = false) @Nullable String token,
                                           @Valid @RequestBody StartCheckoutRequest body) {
        var started = checkouts.start(store, body.cartId(), token, body.email(), address(body.shippingAddress()));
        var response = CheckoutResponse.of(started.view());
        return started.created()
                ? ResponseEntity.created(URI.create("/storefront/v1/checkouts/" + response.id())).body(response)
                : ResponseEntity.ok(response);
    }

    @GetMapping("/{checkoutId}")
    CheckoutResponse get(@CurrentStorefront StoreInfo store, @PathVariable UUID checkoutId,
                         @RequestHeader(name = StorefrontCartController.CART_TOKEN, required = false) @Nullable String token) {
        return CheckoutResponse.of(checkouts.get(store, checkoutId, token));
    }

    @PatchMapping("/{checkoutId}")
    CheckoutResponse update(@CurrentStorefront StoreInfo store, @PathVariable UUID checkoutId,
                            @RequestHeader(name = StorefrontCartController.CART_TOKEN, required = false) @Nullable String token,
                            @Valid @RequestBody UpdateCheckoutRequest body) {
        return CheckoutResponse.of(checkouts.update(store, checkoutId, token, body.email(), address(body.shippingAddress())));
    }

    /**
     * Requires {@code Idempotency-Key}. A retried request (timeout, double click, flaky mobile network)
     * gets the stored response of the first one ({@code Idempotent-Replayed: true}) instead of
     * completing twice. The fingerprint includes the cart token's hash: someone who learns another
     * shopper's key can't replay that shopper's stored response (it contains PII).
     */
    @PostMapping("/{checkoutId}/completion")
    ResponseEntity<String> complete(@CurrentStorefront StoreInfo store, @PathVariable UUID checkoutId,
                                    @RequestHeader(name = StorefrontCartController.CART_TOKEN, required = false) @Nullable String token,
                                    @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String key,
                                    @Valid @RequestBody CompletionRequest body) {
        String fingerprint = "POST /checkouts/" + checkoutId + "/completion|" + body.expectedTotal() + "|"
                + (token == null ? "" : SecretTokens.hash(token));
        var result = idempotency.execute(store.id(), "checkout.completion", key, fingerprint, 200,
                () -> CheckoutResponse.of(checkouts.complete(store, checkoutId, token, body.expectedTotal())));
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result.jsonBody());
    }

    private static @Nullable Address address(@Nullable AddressDto dto) {
        return dto == null ? null : dto.toAddress();
    }
}
