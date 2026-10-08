package dev.storeforge.developer.internal;

import dev.storeforge.developer.DeveloperApi;
import dev.storeforge.developer.Scope;
import dev.storeforge.shared.error.StoreForgeException;
import java.util.Collection;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

public final class DeveloperExceptions {

    private DeveloperExceptions() {
    }

    public static class UnsupportedApiVersion extends StoreForgeException {
        public UnsupportedApiVersion() {
            super(HttpStatus.NOT_FOUND, "unsupported_api_version", "Unknown API version. Supported: " + DeveloperApi.SUPPORTED_VERSIONS + ".");
        }
    }

    /** RFC 6750 §3.1: 403 + {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="..."}. */
    public static class InsufficientScope extends StoreForgeException {
        private final String scopes;

        public InsufficientScope(Collection<Scope> missing) {
            super(HttpStatus.FORBIDDEN, "insufficient_scope", "This request needs the scope(s) "
                    + missing.stream().map(Scope::code).collect(Collectors.joining(", ")) + ".");
            this.scopes = missing.stream().map(Scope::code).collect(Collectors.joining(" "));
        }

        @Override
        public HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"" + scopes + "\"");
            return headers;
        }
    }

    public static class UnknownScope extends StoreForgeException {
        public UnknownScope(String code) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_scope", "Unknown scope: " + code + ".");
        }
    }

    public static class ApiKeyNotFound extends StoreForgeException {
        public ApiKeyNotFound() {
            super(HttpStatus.NOT_FOUND, "api_key_not_found", "API key not found.");
        }
    }

    public static class ApiKeyLimit extends StoreForgeException {
        public ApiKeyLimit(int limit) {
            super(HttpStatus.CONFLICT, "api_key_limit", "A store can have at most " + limit + " active API keys.");
        }
    }
}
