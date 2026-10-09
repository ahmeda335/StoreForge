package dev.storeforge.support;

import java.util.Map;
import java.util.UUID;

/**
 * A valid body for every admin write endpoint (by "METHOD pattern"), so security tests reach the
 * authorization decision instead of stopping at Bean Validation (422). Used by TenantIsolationIT and
 * FunctionLevelAuthorizationIT; a new write endpoint needs an entry here.
 */
public final class SampleRequests {

    /** Valid request bodies, so requests get past Bean Validation and actually reach the tenant check. */
    public static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("PATCH /admin/v1/stores/{storeId}", "{\"name\":\"Hijacked\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/roles", "{\"name\":\"x\",\"permissions\":[]}"),
            Map.entry("POST /admin/v1/stores/{storeId}/invitations", "{\"email\":\"x@example.test\",\"roleId\":\"" + UUID.randomUUID() + "\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/invitations/acceptance", "{\"token\":\"not-a-real-token\"}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/members/{userId}", "{\"roleId\":\"" + UUID.randomUUID() + "\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/products", "{\"title\":\"Injected\",\"options\":[],\"variants\":[{\"price\":\"1.00\",\"options\":[]}]}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/products/{productId}", "{\"title\":\"Hijacked\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/products/{productId}/variants", "{\"price\":\"1.00\",\"options\":[]}"),
            Map.entry("POST /admin/v1/stores/{storeId}/products/{productId}/images/uploads", "{\"contentType\":\"image/png\",\"sizeBytes\":100}"),
            Map.entry("POST /admin/v1/stores/{storeId}/customers/{customerId}/erasure", "{\"reason\":\"request\"}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/products/{productId}/variants/{variantId}", "{\"price\":\"1.00\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/variants/{variantId}/inventory/adjustments", "{\"delta\":5,\"reason\":\"RECEIVED\"}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/variants/{variantId}/inventory", "{\"tracked\":false}"),
            Map.entry("POST /admin/v1/stores/{storeId}/orders/{orderId}/cancellation", "{\"reason\":\"other\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/orders/{orderId}/manual-payment", "{}"),
            Map.entry("POST /admin/v1/stores/{storeId}/orders/{orderId}/refunds", "{\"amount\":\"1.00\",\"reason\":\"other\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/shipping-rates", "{\"name\":\"Standard\",\"price\":\"4.90\"}"),
            Map.entry("PATCH /admin/v1/stores/{storeId}/shipping-rates/{rateId}", "{\"active\":false}"),
            Map.entry("POST /admin/v1/stores/{storeId}/discounts", "{\"code\":\"SAVE10\",\"kind\":\"PERCENTAGE\",\"value\":\"10\"}"),
            Map.entry("POST /admin/v1/stores/{storeId}/api-keys", "{\"name\":\"x\",\"scopes\":[\"read_orders\"]}"),
            Map.entry("POST /admin/v1/stores/{storeId}/webhook-subscriptions", "{\"url\":\"https://example.com/hooks\",\"topics\":[\"orders/create\"]}"),
            Map.entry("POST /admin/v1/stores/{storeId}/webhook-subscriptions/{subscriptionId}/replays",
                    "{\"statuses\":[\"DEAD\"],\"since\":\"2026-01-01T00:00:00Z\"}"));

    private SampleRequests() {
    }
}
