package dev.storeforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.SampleRequests;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * OWASP API5 (broken function level authorization), over EVERY endpoint the application has,
 * discovered from Spring MVC (a new endpoint is covered without anyone remembering to add it).
 */
class FunctionLevelAuthorizationIT extends IntegrationTest {

    /** Endpoints any member may call whatever their role, with the reason. Everything else needs a permission. */
    private static final Map<String, String> ANY_MEMBER = Map.of(
            "GET /admin/v1/stores/{storeId}", "members see which store they're in (name, currency) to use the admin at all",
            "POST /admin/v1/stores/{storeId}/invitations/acceptance", "for people who are NOT members yet: the invitation token authorizes");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyNonPublicEndpointRefusesMissingOrForgedCredentials() {
        List<String> endpoints = endpoints(p -> p.startsWith("/admin/") || p.startsWith("/platform/") || p.startsWith("/api/"));
        assertThat(endpoints).as("discovered").hasSizeGreaterThan(60);
        for (String endpoint : endpoints) {
            assertThat(call(endpoint, Map.of()).statusCode()).as(endpoint + " without credentials").isEqualTo(401);
            assertThat(call(endpoint, Map.of("Authorization", "Bearer forged.token.value")).statusCode()).as(endpoint + " forged").isEqualTo(401);
        }
    }

    @Test
    void noPlatformEndpointAcceptsAMerchantToken() {
        Owner merchant = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        Set<String> stepUp = Set.of("/platform/v1/session", "/platform/v1/mfa/enrollment", "/platform/v1/mfa/enrollment/confirmation");
        List<String> endpoints = endpoints(p -> p.startsWith("/platform/") && !stepUp.contains(p));
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(6);
        for (String endpoint : endpoints) {
            assertThat(call(endpoint, merchant.auth()).statusCode()).as(endpoint).isEqualTo(401);
        }
    }

    /** A member whose role grants nothing can reach nothing (except the documented exemptions). */
    @Test
    void aRoleWithoutPermissionsReachesNoFunction() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        Owner shop = stores.newOwnerWithStore(uniqueEmail());
        var nobody = stores.addMember(shop, uniqueEmail(), stores.createRole(shop, "Nothing"));
        Map<String, String> auth = StoreFlows.bearer(nobody.accessToken());
        List<String> failures = new ArrayList<>();
        for (String endpoint : endpoints(p -> p.startsWith("/admin/v1/stores/{storeId}"))) {
            int status = call(endpoint.replace("{storeId}", shop.storeId().toString()), SampleRequests.BODIES.get(endpoint), auth).statusCode();
            boolean exempt = ANY_MEMBER.containsKey(endpoint);
            if (exempt ? status == 403 : status != 403) {
                failures.add(endpoint + " → " + status);
            }
        }
        assertThat(failures).as("expected 403 (or allowed, if exempt)").isEmpty();
    }

    @Test
    void operatorEndpointsAreNotOnThePublicPort() {
        for (String path : List.of("/actuator/health", "/actuator/prometheus", "/actuator/jobs", "/actuator/sbom/application", "/actuator/env")) {
            assertThat(api.get(path).statusCode()).as(path).isEqualTo(404);
        }
        assertThat(management.get("/actuator/health").statusCode()).as("private port").isEqualTo(200);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private List<String> endpoints(Predicate<String> pattern) {
        List<String> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (pattern.test(path)) {
                    for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                        endpoints.add(method.name() + " " + path);
                    }
                }
            }
        }
        return endpoints.stream().distinct().sorted().toList();
    }

    private HttpResponse<String> call(String endpoint, Map<String, String> headers) {
        return call(endpoint, null, headers);
    }

    /**
     * Placeholders become random ids. Authentication is decided before the body is read; store
     * permissions are checked in services, after Bean Validation: write endpoints get a VALID body
     * ({@link SampleRequests}) so the test reaches the permission check.
     */
    private HttpResponse<String> call(String endpoint, String body, Map<String, String> headers) {
        String method = endpoint.substring(0, endpoint.indexOf(' '));
        String path = endpoint.substring(endpoint.indexOf(' ') + 1).replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        return api.request(method, path, body != null ? body : "GET".equals(method) || "DELETE".equals(method) ? null : "{}", headers);
    }
}
