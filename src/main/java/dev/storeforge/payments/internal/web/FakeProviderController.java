package dev.storeforge.payments.internal.web;

import dev.storeforge.payments.internal.provider.FakePaymentProvider;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL DEVELOPMENT ONLY (exists only with the fake provider): what the customer's browser would do
 * with the provider's card form. Test cards: 4242424242424242 succeeds, 4000000000000002 is declined.
 */
@RestController
@RequestMapping("/fake-provider/v1")
@ConditionalOnProperty(name = "storeforge.payments.provider", havingValue = FakePaymentProvider.NAME, matchIfMissing = true)
@RequiredArgsConstructor
class FakeProviderController {

    private final FakePaymentProvider provider;

    record ConfirmRequest(@NotBlank String card) {
    }

    @PostMapping("/payment_intents/{id}/confirm")
    Map<String, Object> confirm(@PathVariable String id, @RequestBody ConfirmRequest body) {
        var webhooks = provider.confirm(id, body.card());
        return Map.of("status", provider.status(id), "webhooks", webhooks.stream().map(FakePaymentProvider.Webhook::type).toList());
    }
}
