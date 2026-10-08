package dev.storeforge.webhooks.internal.web;

import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.developer.DeveloperApi;
import dev.storeforge.developer.RequiresScope;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionService;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionStore.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer API (2026-10): the calling credential's OWN webhook subscriptions. No fixed scope
 * ({@code @RequiresScope({})}): each topic requires the read scope of its resource, checked per
 * topic in {@link SubscriptionService#createForClient}; an app only ever sees its own subscriptions.
 */
@RestController
@RequestMapping(DeveloperApi.STORE_PATH + "/webhook-subscriptions")
@RequiredArgsConstructor
class DeveloperWebhooksController {

    private final SubscriptionService subscriptions;

    record CreateRequest(@NotBlank @Size(max = 2048) String url, @NotEmpty @Size(max = 20) List<@NotBlank String> topics) {
    }

    record SubscriptionResponse(UUID id, String url, List<String> topics, String apiVersion, String status, Instant createdAt,
                                @Nullable String secret) {
        static SubscriptionResponse of(Subscription s, @Nullable String secret) {
            return new SubscriptionResponse(s.id(), s.url(), s.topics(), s.apiVersion(), s.status(), s.createdAt(), secret);
        }
    }

    @GetMapping
    @RequiresScope({})
    Map<String, List<SubscriptionResponse>> list(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion,
                                                 @PathVariable UUID storeId) {
        return Map.of("data", subscriptions.listForClient(client).stream().map(s -> SubscriptionResponse.of(s, null)).toList());
    }

    @PostMapping
    @RequiresScope({})
    ResponseEntity<SubscriptionResponse> create(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion,
                                                @PathVariable UUID storeId, @Valid @RequestBody CreateRequest body) {
        var created = subscriptions.createForClient(client, body.url(), body.topics());
        return ResponseEntity.created(URI.create("/api/" + apiVersion + "/stores/" + storeId + "/webhook-subscriptions/" + created.subscription().id()))
                .body(SubscriptionResponse.of(created.subscription(), created.secret()));
    }

    @DeleteMapping("/{subscriptionId}")
    @RequiresScope({})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion, @PathVariable UUID storeId,
                @PathVariable UUID subscriptionId) {
        subscriptions.deleteForClient(client, subscriptionId);
    }
}
