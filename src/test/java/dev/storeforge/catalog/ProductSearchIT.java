package dev.storeforge.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PostgreSQL search: weighted full text, accents, typos, filters, disjunctive facets, tenant isolation. */
class ProductSearchIT extends IntegrationTest {

    Owner shop;
    StorefrontFlows storefront;

    @BeforeEach
    void catalog() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        product("Brass Desk Lamp", "Lumen", "Lighting", "A warm reading light.", "89.00", "ACTIVE");
        product("Linen Lampshade", "Lumen", "Lighting", "Soft diffused glow.", "35.00", "ACTIVE");
        product("Café Table", "Nordwood", "Furniture", "Solid oak, seats two.", "240.00", "ACTIVE");
        product("Reading Chair", "Nordwood", "Furniture", "Pairs well with a floor lamp.", "320.00", "ACTIVE");
        product("Prototype Lamp", "Lumen", "Lighting", "Not for sale yet.", "10.00", "DRAFT");
        storefront = new StorefrontFlows(api, shop);
    }

    @Test
    void titleMatchesOutrankDescriptionMatchesAndDraftsAreHidden() {
        List<String> titles = titles(search("q=lamp"));
        assertThat(titles.getFirst()).as("title (weight A) before description (weight C)").isEqualTo("Brass Desk Lamp");
        assertThat(titles).contains("Reading Chair").doesNotContain("Prototype Lamp");
        assertThat(titles.indexOf("Reading Chair")).isGreaterThan(titles.indexOf("Brass Desk Lamp"));
    }

    @Test
    void accentsAndTyposStillFindTheProduct() {
        JsonNode cafe = search("q=cafe");
        assertThat(titles(cafe)).containsExactly("Café Table");
        assertThat(cafe.get("data").get(0).get("minPrice").get("amount").asString()).as("prices looked up for the page").isEqualTo("240.00");
        assertThat(titles(search("q=CAFÉ"))).containsExactly("Café Table");
        assertThat(titles(search("q=lampshde"))).as("one letter missing").contains("Linen Lampshade");
        assertThat(titles(search("q=lampshade"))).containsExactly("Linen Lampshade");
    }

    @Test
    void anEmptyQueryListsEverythingWithFacets() {
        JsonNode all = search("");
        assertThat(all.get("page").get("total").asInt()).isEqualTo(4);
        assertThat(facet(all, "vendors")).containsExactlyInAnyOrder("Lumen:2", "Nordwood:2");
        assertThat(facet(all, "productTypes")).containsExactlyInAnyOrder("Lighting:2", "Furniture:2");
    }

    /** Choosing a vendor filters the results, but the vendor facet still offers the others (disjunctive). */
    @Test
    void filtersNarrowResultsButFacetsStayUsable() {
        JsonNode lumen = search("vendor=Lumen");
        assertThat(titles(lumen)).containsExactlyInAnyOrder("Brass Desk Lamp", "Linen Lampshade");
        assertThat(facet(lumen, "vendors")).contains("Nordwood:2");
        assertThat(facet(lumen, "productTypes")).containsExactly("Lighting:2");

        assertThat(titles(search("minPrice=50&maxPrice=250&sort=price_asc"))).containsExactly("Brass Desk Lamp", "Café Table");
        assertThat(search("sort=cheapest").toString()).contains("invalid_parameter");
    }

    @Test
    void searchIsImmediatelyConsistentAndScopedToTheStore() {
        Owner other = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        HttpResponse<String> created = api.post("/admin/v1/stores/" + other.storeId() + "/products",
                "{\"title\":\"Brass Floor Lamp\",\"status\":\"ACTIVE\",\"options\":[],\"variants\":[{\"price\":\"1.00\",\"options\":[]}]}", other.auth());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(titles(search("q=brass"))).containsExactly("Brass Desk Lamp");

        String id = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/products?limit=50", shop.auth()).body()).get("data")
                .valueStream().filter(p -> p.get("title").asString().equals("Reading Chair")).findFirst().orElseThrow().get("id").asString();
        HttpResponse<String> product = api.get("/admin/v1/stores/" + shop.storeId() + "/products/" + id, shop.auth());
        api.patch("/admin/v1/stores/" + shop.storeId() + "/products/" + id, "{\"title\":\"Velvet Armchair\"}",
                java.util.Map.of("Authorization", shop.auth().get("Authorization"), "If-Match", product.headers().firstValue("ETag").orElseThrow()));
        assertThat(titles(search("q=velvet"))).as("same transaction as the write: no indexing lag").containsExactly("Velvet Armchair");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private void product(String title, String vendor, String type, String description, String price, String status) {
        HttpResponse<String> created = api.post("/admin/v1/stores/" + shop.storeId() + "/products", Json.object("title", title, "vendor", vendor,
                "productType", type, "description", description, "status", status).replace("}", ",\"options\":[],\"variants\":[{\"price\":\""
                + price + "\",\"options\":[]}]}"), shop.auth());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    }

    /** {@code query}: a raw query string; the value of {@code q=} (always first, if present) is URL-encoded here. */
    private JsonNode search(String query) {
        String encoded = query;
        if (query.startsWith("q=")) {
            int end = query.indexOf('&');
            String q = end < 0 ? query.substring(2) : query.substring(2, end);
            encoded = "q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + (end < 0 ? "" : query.substring(end));
        }
        return Json.parse(api.get("/storefront/v1/search?" + encoded, storefront.headers(null)).body());
    }

    private static List<String> titles(JsonNode response) {
        return response.get("data").valueStream().map(hit -> hit.get("title").asString()).toList();
    }

    private static List<String> facet(JsonNode response, String name) {
        return response.get("facets").get(name).valueStream().map(f -> f.get("value").asString() + ":" + f.get("count").asInt()).toList();
    }
}
