package dev.storeforge.developer.internal.oauth;

import dev.storeforge.developer.Scope;
import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The only server-rendered pages of StoreForge: the OAuth login and the install consent. Plain HTML,
 * every dynamic value escaped. The consent shows WHICH store, WHICH app and WHAT it may do, and only
 * offers "Install" if the signed-in merchant may grant it (re-checked at token issuance too).
 */
@RestController
@RequiredArgsConstructor
class OAuthPagesController {

    private static final Map<Scope, String> DESCRIPTIONS = Map.of(
            Scope.READ_PRODUCTS, "See products and prices",
            Scope.WRITE_PRODUCTS, "Create and change products",
            Scope.READ_ORDERS, "See orders, including customer emails and addresses",
            Scope.WRITE_ORDERS, "Change orders",
            Scope.READ_INVENTORY, "See stock levels",
            Scope.WRITE_INVENTORY, "Change stock levels",
            Scope.READ_CUSTOMERS, "See customer data (personal information)");

    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final StoreDirectory stores;
    private final StoreAccess access;
    private final TenantTransactions tenant;

    @GetMapping(value = "/oauth/login", produces = MediaType.TEXT_HTML_VALUE)
    String login(HttpServletRequest request, @RequestParam(required = false) @Nullable String error) {
        String message = "";
        if (error != null) {
            HttpSession session = request.getSession(false);
            Object last = session == null ? null : session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
            String code = last instanceof AuthenticationException e ? e.getMessage() : "";
            message = "<p class=error>" + switch (code) {
                case "email_not_verified" -> "Please verify your email address first.";
                case "rate_limited" -> "Too many attempts. Please wait a moment and try again.";
                default -> "Invalid email or password.";
            } + "</p>";
        }
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return page("Sign in to StoreForge", message + """
                <form method=post action="/oauth/login">
                  <label>Email <input name=username type=email autocomplete=username required></label>
                  <label>Password <input name=password type=password autocomplete=current-password required></label>
                  <input type=hidden name="%s" value="%s">
                  <button type=submit>Sign in</button>
                </form>""".formatted(esc(csrf.getParameterName()), esc(csrf.getToken())));
    }

    @GetMapping(value = "/oauth/consent", produces = MediaType.TEXT_HTML_VALUE)
    String consent(Principal principal, @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
                   @RequestParam(OAuth2ParameterNames.SCOPE) String scope, @RequestParam(OAuth2ParameterNames.STATE) String state) {
        RegisteredClient client = clients.findByClientId(clientId);
        OAuth2Authorization pending = authorizations.findByToken(state, new OAuth2TokenType(OAuth2ParameterNames.STATE));
        if (client == null || pending == null) {
            return page("Installation failed", "<p class=error>This installation request is invalid or has expired.</p>");
        }
        OAuth2AuthorizationRequest request = pending.getAttribute(OAuth2AuthorizationRequest.class.getName());
        Object host = request == null ? null : request.getAdditionalParameters().get(InstallationTokenGenerator.STORE_PARAMETER);
        StoreInfo store = host == null ? null : stores.resolveHost(host.toString()).orElse(null);
        if (store == null) {
            return page("Installation failed", "<p class=error>The app did not say which store to install on.</p>");
        }
        Set<Scope> scopes = Arrays.stream(scope.split(" ")).filter(s -> !s.isBlank()).map(s -> Scope.fromCode(s).orElseThrow())
                .collect(Collectors.toCollection(TreeSet::new));
        String list = scopes.stream().map(s -> "<li><code>" + esc(s.code()) + "</code> " + esc(DESCRIPTIONS.get(s)) + "</li>")
                .collect(Collectors.joining());
        String intro = "<p><b>" + esc(client.getClientName()) + "</b> wants to access <b>" + esc(store.name()) + "</b> ("
                + esc(store.handle()) + ") and will be able to:</p><ul>" + list + "</ul>";
        if (!mayGrant(UUID.fromString(principal.getName()), store, scopes)) {
            return page("Install " + client.getClientName(), intro
                    + "<p class=error>You don't have permission to install apps with these permissions on this store.</p>");
        }
        String hidden = scopes.stream().map(s -> "<input type=hidden name=scope value=\"" + esc(s.code()) + "\">").collect(Collectors.joining());
        return page("Install " + client.getClientName(), intro + """
                <form method=post action="/oauth2/authorize">
                  <input type=hidden name=client_id value="%s"><input type=hidden name=state value="%s">%s
                  <button type=submit>Install app</button>
                </form>
                <form method=post action="/oauth2/authorize">
                  <input type=hidden name=client_id value="%s"><input type=hidden name=state value="%s">
                  <button type=submit class=secondary>Cancel</button>
                </form>""".formatted(esc(clientId), esc(state), hidden, esc(clientId), esc(state)));
    }

    private boolean mayGrant(UUID userId, StoreInfo store, Set<Scope> scopes) {
        try {
            tenant.runInStore(store.id(), () -> {
                access.require(userId, store.id(), Permission.APPS_MANAGE);
                access.require(userId, store.id(), Scope.permissionsOf(scopes).toArray(Permission[]::new));
            });
            return true;
        } catch (StoreForgeException e) {
            return false;
        }
    }

    private static String page(String title, String body) {
        return """
                <!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
                <title>%s</title><style>body{font-family:system-ui,sans-serif;max-width:28rem;margin:3rem auto;padding:0 1rem;color:#1f2328}
                label{display:block;margin:.75rem 0}input[type=email],input[type=password]{display:block;width:100%%;padding:.5rem;margin-top:.25rem}
                button{padding:.5rem 1rem;margin-top:1rem}.secondary{background:none;border:none;text-decoration:underline}.error{color:#b42318}</style>
                </head><body><h1>%s</h1>%s</body></html>""".formatted(esc(title), esc(title), body);
    }

    private static String esc(@Nullable String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
