package dev.storeforge.webhooks.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.error.InvalidParameterException;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.webhooks.internal.delivery.DeliveryService;
import dev.storeforge.webhooks.internal.delivery.DeliveryStore;
import dev.storeforge.webhooks.internal.delivery.DeliveryStore.Delivery;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionService;
import dev.storeforge.webhooks.internal.subscriptions.SubscriptionStore.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Merchant webhook management: subscriptions, secrets, delivery history, replay. Requires {@code webhooks:manage}. */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/webhook-subscriptions")
@RequiredArgsConstructor
class AdminWebhooksController {

    private final SubscriptionService subscriptions;
    private final DeliveryService deliveries;

    record CreateRequest(@NotBlank @Size(max = 2048) String url, @NotEmpty @Size(max = 20) List<@NotBlank String> topics,
                         @Nullable String apiVersion) {
    }

    record UpdateRequest(@Nullable @Size(max = 2048) String url, @Nullable @Size(min = 1, max = 20) List<@NotBlank String> topics,
                         @Nullable @Pattern(regexp = "ACTIVE|DISABLED", message = "must be ACTIVE or DISABLED") String status) {
    }

    record ReplayRequest(@NotEmpty List<@Pattern(regexp = "SUCCEEDED|DEAD|PAUSED|DISCARDED") String> statuses, @NotNull Instant since) {
    }

    record SubscriptionResponse(UUID id, String url, List<String> topics, String apiVersion, String status, int consecutiveFailures,
                                @Nullable Instant lastSuccessAt, @Nullable Instant lastFailureAt, @Nullable Instant pausedAt,
                                @Nullable Instant previousSecretExpiresAt, Instant createdAt, @Nullable String secret) {
        static SubscriptionResponse of(Subscription s, @Nullable String secret) {
            return new SubscriptionResponse(s.id(), s.url(), s.topics(), s.apiVersion(), s.status(), s.consecutiveFailures(),
                    s.lastSuccessAt(), s.lastFailureAt(), s.pausedAt(), s.previousSecretExpiresAt(), s.createdAt(), secret);
        }
    }

    record DeliverySummary(UUID id, UUID eventId, String topic, String status, int attempts, int replays,
                           @Nullable Integer lastStatusCode, @Nullable String lastError, @Nullable Instant nextAttemptAt,
                           @Nullable Instant deliveredAt, Instant createdAt) {
        static DeliverySummary of(Delivery d) {
            return new DeliverySummary(d.id(), d.eventId(), d.topic(), d.status(), d.attempts(), d.replays(), d.lastStatusCode(),
                    d.lastError(), d.nextAttemptAt(), d.deliveredAt(), d.createdAt());
        }
    }

    record DeliveryResponse(DeliverySummary delivery, String payload, List<DeliveryStore.Attempt> attempts) {
    }

    @PostMapping
    ResponseEntity<SubscriptionResponse> create(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                                @Valid @RequestBody CreateRequest body) {
        var created = subscriptions.create(user.userId(), StoreId.of(storeId), body.url(), body.topics(), body.apiVersion());
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/webhook-subscriptions/" + created.subscription().id()))
                .body(SubscriptionResponse.of(created.subscription(), created.secret()));
    }

    @GetMapping
    Map<String, List<SubscriptionResponse>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        return Map.of("data", subscriptions.list(user.userId(), StoreId.of(storeId)).stream()
                .map(s -> SubscriptionResponse.of(s, null)).toList());
    }

    @GetMapping("/{subscriptionId}")
    SubscriptionResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID subscriptionId) {
        return SubscriptionResponse.of(subscriptions.get(user.userId(), StoreId.of(storeId), subscriptionId), null);
    }

    @PatchMapping("/{subscriptionId}")
    SubscriptionResponse update(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID subscriptionId,
                                @Valid @RequestBody UpdateRequest body) {
        return SubscriptionResponse.of(subscriptions.update(user.userId(), StoreId.of(storeId), subscriptionId, body.url(), body.topics(),
                body.status()), null);
    }

    @DeleteMapping("/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID subscriptionId) {
        subscriptions.delete(user.userId(), StoreId.of(storeId), subscriptionId);
    }

    @PostMapping("/{subscriptionId}/secret-rotation")
    SubscriptionResponse rotate(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID subscriptionId) {
        var rotated = subscriptions.rotateSecret(user.userId(), StoreId.of(storeId), subscriptionId);
        return SubscriptionResponse.of(rotated.subscription(), rotated.secret());
    }

    @GetMapping("/{subscriptionId}/deliveries")
    Page<DeliverySummary> deliveries(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID subscriptionId,
                                     @RequestParam(required = false) @Nullable String status,
                                     @RequestParam(required = false) @Nullable Integer limit,
                                     @RequestParam(required = false) @Nullable String cursor) {
        if (status != null && !List.of("PENDING", "RETRYING", "SUCCEEDED", "DEAD", "PAUSED", "DISCARDED").contains(status)) {
            throw new InvalidParameterException("status", "PENDING, RETRYING, SUCCEEDED, DEAD, PAUSED, DISCARDED");
        }
        var page = deliveries.list(user.userId(), StoreId.of(storeId), subscriptionId, status, Page.limit(limit),
                cursor == null ? null : Cursor.decode(cursor));
        return new Page<>(page.data().stream().map(DeliverySummary::of).toList(), page.page());
    }

    @GetMapping("/{subscriptionId}/deliveries/{deliveryId}")
    DeliveryResponse delivery(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID subscriptionId,
                              @PathVariable UUID deliveryId) {
        var details = deliveries.get(user.userId(), StoreId.of(storeId), subscriptionId, deliveryId);
        return new DeliveryResponse(DeliverySummary.of(details.delivery()), details.delivery().payload(), details.attempts());
    }

    @PostMapping("/{subscriptionId}/deliveries/{deliveryId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void replay(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID subscriptionId,
                @PathVariable UUID deliveryId) {
        deliveries.replay(user.userId(), StoreId.of(storeId), subscriptionId, deliveryId);
    }

    @PostMapping("/{subscriptionId}/replays")
    ResponseEntity<Map<String, Integer>> replayAll(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                                   @PathVariable UUID subscriptionId, @Valid @RequestBody ReplayRequest body) {
        int queued = deliveries.replayAll(user.userId(), StoreId.of(storeId), subscriptionId, body.statuses(), body.since());
        return ResponseEntity.accepted().body(Map.of("queued", queued));
    }
}
