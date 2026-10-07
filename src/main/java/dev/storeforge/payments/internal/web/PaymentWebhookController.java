package dev.storeforge.payments.internal.web;

import dev.storeforge.payments.internal.application.WebhookIntake;
import dev.storeforge.payments.internal.provider.StripeFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider → StoreForge. Unauthenticated at the HTTP level: the signature over the raw body is the
 * authentication. The body is taken as a String, untouched, because the signature covers its exact bytes.
 */
@RestController
@RequestMapping("/webhooks/v1/payments")
@RequiredArgsConstructor
class PaymentWebhookController {

    private final WebhookIntake intake;

    @PostMapping("/{provider}")
    Map<String, Object> receive(@PathVariable String provider, @RequestBody String body,
                                @RequestHeader(name = StripeFormat.SIGNATURE_HEADER, required = false) @Nullable String signature) {
        intake.receive(provider, body, signature);
        return Map.of("received", true);
    }
}
