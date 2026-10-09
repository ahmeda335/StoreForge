package dev.storeforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import dev.storeforge.support.StorefrontFlows.Placed;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** A customer's data: complete export, and erasure that leaves nothing behind but accounting records. */
class CustomerPrivacyIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    JobWorker jobs;

    Owner shop;
    UUID variant;
    StorefrontFlows storefront;
    String email;

    @BeforeEach
    void aCustomerWithAClosedOrderAndADiscount() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        CatalogFlows catalog = new CatalogFlows(api);
        variant = catalog.createVariant(shop, "Lamp");
        catalog.adjustStock(shop, variant, 10, "RECEIVED");
        storefront = new StorefrontFlows(api, shop);
        email = "ada-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void anExportContainsEverythingTheStoreHoldsAboutTheCustomer() {
        Placed placed = closedOrder();
        HttpResponse<String> export = api.get(customerPath() + "/export", shop.auth());
        assertThat(export.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment");
        assertThat(export.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode data = Json.parse(export.body());
        assertThat(data.get("customer").get("email").asString()).isEqualTo(email);
        assertThat(data.get("orders").get(0).get("shippingAddress").get("line1").asString()).isEqualTo("12 Analytical St");
        assertThat(data.get("orders").get(0).get("items").get(0).get("product").asString()).isEqualTo("Lamp");
        assertThat(data.get("checkouts")).isNotEmpty();
        assertThat(data.get("discountRedemptions").get("codesUsed").get(0).asString()).isEqualTo("WELCOME");
        assertThat(data.get("emails")).as("the order confirmation").isNotEmpty();
        assertThat(lastAudit().get("action").asString()).isEqualTo("customer.exported");
        assertThat(placed).isNotNull();
    }

    /** After erasure, the email appears in NO column of ANY table: searched, not assumed. */
    @Test
    void erasureLeavesNoPersonalDataAnywhereButKeepsTheAccounting() {
        Placed placed = closedOrder();
        assertThat(occurrencesOf(email)).as("before: where the email lives").contains("customers.email", "orders.email", "checkouts.email",
                "discount_redemptions.customer_key", "email_messages.recipient", "idempotency_keys.response_body");
        assertThat(occurrencesOf("12 Analytical St")).isNotEmpty();

        HttpResponse<String> erased = api.post(customerPath() + "/erasure", "{\"reason\":\"Customer request #4711\"}", shop.auth());
        assertThat(erased.statusCode()).as(erased.body()).isEqualTo(204);

        assertThat(occurrencesOf(email)).as("email left in").isEmpty();
        assertThat(occurrencesOf("12 Analytical St")).as("address left in").isEmpty();
        assertThat(occurrencesOf("Ada Lovelace")).as("name left in").isEmpty();
        JsonNode order = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/orders/" + placed.orderId(), shop.auth()).body());
        assertThat(order.get("total").get("amount").asString()).as("accounting kept").isEqualTo("9.00");
        assertThat(order.get("shippingAddress").get("country").asString()).isEqualTo("GB");
        assertThat(order.get("email").asString()).endsWith("@erased.invalid");

        JsonNode audit = lastAudit();
        assertThat(audit.get("action").asString()).isEqualTo("customer.erased");
        assertThat(audit.toString()).contains("Customer request #4711").doesNotContain(email);
        assertThat(api.post(customerPath() + "/erasure", "{\"reason\":\"again\"}", shop.auth()).statusCode()).as("once").isEqualTo(404);

        // The same person can shop again: a new customer, unrelated to the erased one.
        storefront.placeOrder(variant, 1, email);
        JsonNode customers = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/customers", shop.auth()).body()).get("data");
        assertThat(customers).hasSize(2);
    }

    @Test
    void anOrderThatMayStillShipBlocksErasure() {
        storefront.placeOrder(variant, 1, email);   // OPEN, unpaid
        HttpResponse<String> refused = api.post(customerPath() + "/erasure", "{\"reason\":\"request\"}", shop.auth());
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(Json.parse(refused.body()).get("code").asString()).isEqualTo("customer_has_open_orders");
        assertThat(occurrencesOf(email)).as("nothing changed").isNotEmpty();
    }

    @Test
    void erasingNeedsItsOwnPermission() {
        closedOrder();
        StoreFlows stores = new StoreFlows(api, mailbox);
        UUID support = stores.createRole(shop, "Support", "customers:read", "orders:read");
        var agent = stores.addMember(shop, uniqueEmail(), support);
        assertThat(api.get(customerPath() + "/export", StoreFlows.bearer(agent.accessToken())).statusCode()).isEqualTo(200);
        assertThat(api.post(customerPath() + "/erasure", "{\"reason\":\"x\"}", StoreFlows.bearer(agent.accessToken())).statusCode())
                .isEqualTo(403);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Paid and shipped (CLOSED), bought with a once-per-customer discount; confirmation email sent. */
    private Placed closedOrder() {
        api.post("/admin/v1/stores/" + shop.storeId() + "/discounts",
                "{\"code\":\"WELCOME\",\"kind\":\"PERCENTAGE\",\"value\":\"10\",\"oncePerCustomer\":true}", shop.auth());
        var cart = storefront.newCart();
        UUID checkout = storefront.readyCheckout(cart, variant, 1, email);
        api.request("PUT", "/storefront/v1/checkouts/" + checkout + "/discount", "{\"code\":\"WELCOME\"}", storefront.headers(cart.token()));
        HttpResponse<String> completed = storefront.complete(cart, checkout, "9.00", UUID.randomUUID().toString());
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        Placed placed = new Placed(cart, checkout, Json.parse(completed.body()));
        String order = "/admin/v1/stores/" + shop.storeId() + "/orders/" + placed.orderId();
        api.post(order + "/manual-payment", "{}", shop.auth());
        api.post(order + "/fulfillment", "{}", shop.auth());
        jobs.drain();   // confirmation email, events
        return placed;
    }

    private String customerPath() {
        String id = tenant.inStore(StoreId.of(shop.storeId()), () -> jdbc.sql("SELECT id FROM customers WHERE email = CAST(:e AS citext)")
                .param("e", email).query(UUID.class).optional().map(UUID::toString).orElse(UUID.randomUUID().toString()));
        return "/admin/v1/stores/" + shop.storeId() + "/customers/" + id;
    }

    private JsonNode lastAudit() {
        return Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/audit-log", shop.auth()).body()).get("data").get(0);
    }

    /** Every text, citext and JSON column of every table in the schema, as seen inside this store's tenant context. */
    private List<String> occurrencesOf(String value) {
        return tenant.inStore(StoreId.of(shop.storeId()), () -> {
            List<String[]> columns = jdbc.sql("""
                            SELECT table_name, column_name FROM information_schema.columns
                             WHERE table_schema = 'storeforge' AND data_type IN ('text', 'jsonb', 'json', 'character varying', 'USER-DEFINED')
                               AND table_name IN (SELECT table_name FROM information_schema.tables WHERE table_schema = 'storeforge' AND table_type = 'BASE TABLE')
                            """)
                    .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
            List<String> found = new ArrayList<>();
            for (String[] column : columns) {
                boolean hit = jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + column[0] + " WHERE position(lower(:v) in lower(" + column[1] + "::text)) > 0)")
                        .param("v", value).query(Boolean.class).single();
                if (hit) {
                    found.add(column[0] + "." + column[1]);
                }
            }
            return found;
        });
    }
}
