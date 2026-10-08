package dev.storeforge.developer.internal.oauth;

import dev.storeforge.developer.Scope;
import dev.storeforge.developer.internal.DeveloperExceptions;
import dev.storeforge.developer.internal.credentials.ApiTokens;
import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.shared.ids.Ids;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Developers register OAuth apps: client id + secret (shown once), exact redirect URIs, the scopes it may ask for. */
@Service
@RequiredArgsConstructor
public class AppRegistrationService {

    private final OAuthAppStore apps;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public record Registered(OAuthAppStore.App app, String clientSecret) {
    }

    public Registered register(UUID developerId, String name, List<String> redirectUris, List<String> scopes) {
        for (String uri : redirectUris) {
            validateRedirect(uri);
        }
        for (String scope : scopes) {
            Scope.fromCode(scope).orElseThrow(() -> new DeveloperExceptions.UnknownScope(scope));
        }
        String secret = ApiTokens.newToken("sfcs_");
        OAuthAppStore.App app = new OAuthAppStore.App(Ids.newId(), developerId, name.trim(),
                ApiTokens.newToken("sfapp_").substring(0, 30), passwordEncoder.encode(secret),
                redirectUris.stream().distinct().toList(), scopes.stream().distinct().sorted().toList(), clock.instant());
        apps.insert(app);
        return new Registered(app, secret);
    }

    public List<OAuthAppStore.App> mine(UUID developerId) {
        return apps.ownedBy(developerId);
    }

    /**
     * Exact, absolute redirect URIs: https, or http only for loopback (local development, RFC 8252).
     * No fragments, no wildcards: the authorization code is delivered to this URI, and a loose match
     * is how codes get stolen (open redirect → code theft).
     */
    static void validateRedirect(String uri) {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            throw new InvalidRedirectUri(uri);
        }
        String host = parsed.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        boolean schemeOk = "https".equals(parsed.getScheme()) || ("http".equals(parsed.getScheme()) && loopback);
        if (!parsed.isAbsolute() || host == null || !schemeOk || parsed.getRawFragment() != null || uri.contains("*")) {
            throw new InvalidRedirectUri(uri);
        }
    }

    static class InvalidRedirectUri extends StoreForgeException {
        InvalidRedirectUri(String uri) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_redirect_uri",
                    "Redirect URIs must be absolute https URLs (http only for localhost), without fragments or wildcards: " + uri);
        }
    }
}
