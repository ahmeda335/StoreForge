package dev.storeforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

public final class CatalogFlows {

    private final HttpTestClient api;

    public CatalogFlows(HttpTestClient api) {
        this.api = api;
    }

    /** A T-shirt with Size × Color options and two variants. */
    public static String tShirt(String title, String status, String skuPrefix) {
        return """
                {"title":"%s","status":"%s","vendor":"Acme","productType":"Shirts","options":["Size","Color"],
                 "variants":[
                   {"sku":"%s-M-RED","price":"19.99","compareAtPrice":"24.99","options":["M","Red"],"weightGrams":200},
                   {"sku":"%s-L-RED","price":"21.50","options":["L","Red"],"weightGrams":220}]}
                """.formatted(title, status, skuPrefix, skuPrefix);
    }

    public HttpResponse<String> create(Owner owner, String body) {
        return api.post(products(owner), body, owner.auth());
    }

    public UUID createProduct(Owner owner, String title, String status) {
        HttpResponse<String> response = create(owner, tShirt(title, status, "SKU-" + UUID.randomUUID().toString().substring(0, 8)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(Json.parse(response.body()).get("id").asString());
    }

    /** Creates an ACTIVE single-variant product and returns its variant id. */
    public UUID createVariant(Owner owner, String title) {
        HttpResponse<String> response = create(owner, """
                {"title":"%s","status":"ACTIVE","options":[],"variants":[{"price":"10.00","options":[]}]}""".formatted(title));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(Json.parse(response.body()).get("variants").get(0).get("id").asString());
    }

    public HttpResponse<String> adjustStock(Owner owner, UUID variantId, int delta, String reason) {
        return api.post("/admin/v1/stores/" + owner.storeId() + "/variants/" + variantId + "/inventory/adjustments",
                "{\"delta\":" + delta + ",\"reason\":\"" + reason + "\"}", owner.auth());
    }

    public static String products(Owner owner) {
        return "/admin/v1/stores/" + owner.storeId() + "/products";
    }

    public static Map<String, String> withIfMatch(Owner owner, String etag) {
        return Map.of("Authorization", owner.auth().get("Authorization"), "If-Match", etag);
    }
}
