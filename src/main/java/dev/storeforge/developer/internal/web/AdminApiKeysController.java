package dev.storeforge.developer.internal.web;

import dev.storeforge.developer.Scope;
import dev.storeforge.developer.internal.credentials.ApiCredentialStore.Credential;
import dev.storeforge.developer.internal.credentials.ApiKeyService;
import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Merchant management of API keys (admin API, user tokens). */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/api-keys")
@RequiredArgsConstructor
class AdminApiKeysController {

    private final ApiKeyService keys;

    record CreateRequest(@NotBlank @Size(max = 100) String name, @NotEmpty @Size(max = 10) List<@NotBlank String> scopes) {
    }

    record KeyResponse(UUID id, String name, String tokenPrefix, List<String> scopes, Instant createdAt, @Nullable Instant lastUsedAt,
                       @Nullable Instant revokedAt, @Nullable String token) {
        static KeyResponse of(Credential c, @Nullable String token) {
            return new KeyResponse(c.id(), c.name(), c.tokenPrefix(), c.scopes().stream().map(Scope::code).sorted().toList(), c.createdAt(),
                    c.lastUsedAt(), c.revokedAt(), token);
        }
    }

    @PostMapping
    ResponseEntity<KeyResponse> create(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                       @Valid @RequestBody CreateRequest body) {
        var created = keys.create(user.userId(), StoreId.of(storeId), body.name(), body.scopes());
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/api-keys/" + created.credential().id()))
                .body(KeyResponse.of(created.credential(), created.token()));
    }

    @GetMapping
    Map<String, List<KeyResponse>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        return Map.of("data", keys.list(user.userId(), StoreId.of(storeId)).stream().map(c -> KeyResponse.of(c, null)).toList());
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID keyId) {
        keys.revoke(user.userId(), StoreId.of(storeId), keyId);
    }

    @GetMapping("/{keyId}/usage")
    Map<String, List<ApiKeyService.UsageHour>> usage(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                                     @PathVariable UUID keyId,
                                                     @RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours) {
        return Map.of("data", keys.usage(user.userId(), StoreId.of(storeId), keyId, hours));
    }
}
