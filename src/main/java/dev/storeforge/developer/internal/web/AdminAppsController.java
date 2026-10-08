package dev.storeforge.developer.internal.web;

import dev.storeforge.developer.internal.oauth.AppRegistrationService;
import dev.storeforge.developer.internal.oauth.InstallationService;
import dev.storeforge.developer.internal.oauth.OAuthAppStore;
import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.tenancy.StoreId;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developers register OAuth apps (any signed-in user; apps are not store-scoped). Merchants see
 * and uninstall the apps installed on their store ({@code apps:manage}).
 */
@RestController
@RequiredArgsConstructor
class AdminAppsController {

    private final AppRegistrationService registrations;
    private final InstallationService installations;

    record RegisterRequest(@NotBlank @Size(max = 100) String name, @NotEmpty @Size(max = 10) List<@NotBlank String> redirectUris,
                           @NotEmpty @Size(max = 10) List<@NotBlank String> scopes) {
    }

    record AppResponse(UUID id, String name, String clientId, List<String> redirectUris, List<String> scopes, Instant createdAt,
                       @Nullable String clientSecret) {
        static AppResponse of(OAuthAppStore.App app, @Nullable String secret) {
            return new AppResponse(app.id(), app.name(), app.clientId(), app.redirectUris(), app.scopes(), app.createdAt(), secret);
        }
    }

    @PostMapping("/admin/v1/apps")
    ResponseEntity<AppResponse> register(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody RegisterRequest body) {
        var registered = registrations.register(user.userId(), body.name(), body.redirectUris(), body.scopes());
        return ResponseEntity.created(URI.create("/admin/v1/apps/" + registered.app().id()))
                .body(AppResponse.of(registered.app(), registered.clientSecret()));
    }

    @GetMapping("/admin/v1/apps")
    Map<String, List<AppResponse>> mine(@AuthenticationPrincipal UserPrincipal user) {
        return Map.of("data", registrations.mine(user.userId()).stream().map(app -> AppResponse.of(app, null)).toList());
    }

    @GetMapping("/admin/v1/stores/{storeId}/app-installations")
    Map<String, List<InstallationService.Installation>> installations(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        return Map.of("data", installations.list(user.userId(), StoreId.of(storeId)));
    }

    /** Revokes the app's token immediately and deletes its webhook subscriptions. */
    @DeleteMapping("/admin/v1/stores/{storeId}/app-installations/{installationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void uninstall(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID installationId) {
        installations.uninstall(user.userId(), StoreId.of(storeId), installationId);
    }
}
