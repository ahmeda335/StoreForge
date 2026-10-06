package dev.storeforge.catalog;

import static dev.storeforge.support.CatalogFlows.products;
import static dev.storeforge.support.CatalogFlows.tShirt;
import static dev.storeforge.support.CatalogFlows.withIfMatch;
import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductAdminIT extends IntegrationTest {

    StoreFlows stores;
    CatalogFlows catalog;
    Owner owner;

    @BeforeEach
    void storeWithOwner() {
        stores = new StoreFlows(api, mailbox);
        catalog = new CatalogFlows(api);
        owner = stores.newOwnerWithStore(uniqueEmail());
    }

    @Test
    void createsAProductWithVariantsAndExactMoney() {
        HttpResponse<String> created = catalog.create(owner, tShirt("Classic Tee (Ünïcode)", "ACTIVE", "TEE"));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("ETag")).hasValue("\"v0\"");
        var product = Json.parse(created.body());
        assertThat(created.headers().firstValue("Location")).hasValue(products(owner) + "/" + product.get("id").asString());
        assertThat(product.get("handle").asString()).isEqualTo("classic-tee-unicode");
        assertThat(product.get("options").valueStream().map(o -> o.asString())).containsExactly("Size", "Color");
        var first = product.get("variants").get(0);
        assertThat(first.get("title").asString()).isEqualTo("M / Red");
        assertThat(first.get("price").get("amount").asString()).isEqualTo("19.99");
        assertThat(first.get("price").get("currency").asString()).isEqualTo("USD");
        assertThat(first.get("compareAtPrice").get("amount").asString()).isEqualTo("24.99");
        assertThat(product.get("variants").get(1).get("price").get("amount").asString()).isEqualTo("21.50");
    }

    @Test
    void enforcesAggregateRulesAndUniqueness() {
        catalog.create(owner, tShirt("Unique Tee", "DRAFT", "DUP"));

        assertThat(code(catalog.create(owner, tShirt("Unique Tee", "DRAFT", "OTHER")))).isEqualTo("handle_taken");
        assertThat(code(catalog.create(owner, tShirt("Another Tee", "DRAFT", "DUP")))).isEqualTo("sku_taken");
        assertThat(code(catalog.create(owner, """
                {"title":"Bad options","options":["Size"],"variants":[{"price":"1.00","options":["M","Red"]}]}"""))).isEqualTo("invalid_product");
        assertThat(code(catalog.create(owner, """
                {"title":"Duplicate combos","options":["Size"],"variants":[{"price":"1.00","options":["M"]},{"price":"2.00","options":["M"]}]}""")))
                .isEqualTo("duplicate_variant");
        assertThat(code(catalog.create(owner, """
                {"title":"Too precise","options":[],"variants":[{"price":"1.999","options":[]}]}"""))).isEqualTo("invalid_product");
        assertThat(code(catalog.create(owner, """
                {"title":"No variants","options":[],"variants":[]}"""))).isEqualTo("validation_failed");
    }

    @Test
    void staleIfMatchIsRejectedWith412AndCurrentEtagSucceeds() {
        UUID productId = catalog.createProduct(owner, "Versioned Tee", "DRAFT");
        String path = products(owner) + "/" + productId;
        String etag = api.get(path, owner.auth()).headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> first = api.patch(path, Json.object("title", "Edit A"), withIfMatch(owner, etag));
        HttpResponse<String> stale = api.patch(path, Json.object("title", "Edit B"), withIfMatch(owner, etag));

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("ETag")).hasValue("\"v1\"");
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(code(stale)).isEqualTo("precondition_failed");
        assertThat(Json.parse(api.get(path, owner.auth()).body()).get("title").asString()).isEqualTo("Edit A");
    }

    @Test
    void concurrentEditsWithTheSameVersionNeverBothWin() throws Exception {
        UUID productId = catalog.createProduct(owner, "Contended Tee", "DRAFT");
        String path = products(owner) + "/" + productId;
        String etag = api.get(path, owner.auth()).headers().firstValue("ETag").orElseThrow();
        int writers = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                String title = "Writer " + i;
                results.add(executor.submit(() -> {
                    start.await();
                    return api.patch(path, Json.object("title", title), withIfMatch(owner, etag)).statusCode();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            // 412: lost the race before loading; 409: passed the If-Match check but lost at the @Version check.
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(200, 409, 412));
        }
        assertThat(api.get(path, owner.auth()).headers().firstValue("ETag")).hasValue("\"v1\"");
    }

    @Test
    void variantsAreManagedThroughTheProductAndTheLastOneCannotBeRemoved() {
        UUID productId = catalog.createProduct(owner, "Variant Tee", "DRAFT");
        String path = products(owner) + "/" + productId;

        HttpResponse<String> added = api.post(path + "/variants", """
                {"sku":"VAR-XL","price":"23.00","options":["XL","Red"]}""", owner.auth());
        assertThat(added.statusCode()).isEqualTo(201);
        var variants = Json.parse(added.body()).get("variants");
        assertThat(variants.size()).isEqualTo(3);

        String firstId = variants.get(0).get("id").asString();
        HttpResponse<String> repriced = api.patch(path + "/variants/" + firstId, "{\"price\":\"17.00\"}", owner.auth());
        assertThat(Json.parse(repriced.body()).get("variants").get(0).get("price").get("amount").asString()).isEqualTo("17.00");
        assertThat(repriced.headers().firstValue("ETag")).as("variant changes bump the product version").hasValue("\"v2\"");

        for (int i = 1; i < 3; i++) {
            String id = Json.parse(api.get(path, owner.auth()).body()).get("variants").get(1).get("id").asString();
            assertThat(api.delete(path + "/variants/" + id, owner.auth()).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> last = api.delete(path + "/variants/" + firstId, owner.auth());
        assertThat(last.statusCode()).isEqualTo(409);
        assertThat(code(last)).isEqualTo("last_variant");

        var audit = Json.parse(api.get("/admin/v1/stores/" + owner.storeId() + "/audit-log?limit=10", owner.auth()).body()).get("data");
        assertThat(audit.valueStream().map(entry -> entry.get("action").asString()))
                .contains("variant.created", "variant.updated", "variant.removed", "product.created");
    }

    @Test
    void listFiltersSortsAndPaginates() {
        for (int i = 1; i <= 5; i++) {
            catalog.createProduct(owner, "Listed " + i, i % 2 == 0 ? "ACTIVE" : "DRAFT");
        }

        var active = Json.parse(api.get(products(owner) + "?status=ACTIVE", owner.auth()).body()).get("data");
        assertThat(active.valueStream().map(p -> p.get("title").asString())).containsExactly("Listed 4", "Listed 2");
        assertThat(active.get(0).get("variantCount").asInt()).isEqualTo(2);
        assertThat(active.get(0).get("minPrice").get("amount").asString()).isEqualTo("19.99");
        assertThat(active.get(0).get("maxPrice").get("amount").asString()).isEqualTo("21.50");

        var page1 = Json.parse(api.get(products(owner) + "?sort=createdAt&limit=3", owner.auth()).body());
        var page2 = Json.parse(api.get(products(owner) + "?sort=createdAt&limit=3&cursor="
                + page1.get("page").get("nextCursor").asString(), owner.auth()).body());
        List<String> titles = new ArrayList<>();
        page1.get("data").valueStream().forEach(p -> titles.add(p.get("title").asString()));
        page2.get("data").valueStream().forEach(p -> titles.add(p.get("title").asString()));
        assertThat(titles).containsExactly("Listed 1", "Listed 2", "Listed 3", "Listed 4", "Listed 5");

        assertThat(Json.parse(api.get(products(owner) + "?q=sted%203", owner.auth()).body()).get("data").size()).isEqualTo(1);
        assertThat(Json.parse(api.get(products(owner) + "?q=%25", owner.auth()).body()).get("data").size())
                .as("'%' is a literal, not a wildcard").isZero();
        assertThat(code(api.get(products(owner) + "?sort=price", owner.auth()))).isEqualTo("invalid_parameter");
    }

    @Test
    void readOnlyRolesCannotWrite() {
        UUID viewerRole = stores.createRole(owner, "Catalog viewer", "products:read");
        var viewer = stores.addMember(owner, uniqueEmail(), viewerRole);
        var viewerAuth = StoreFlows.bearer(viewer.accessToken());

        assertThat(api.get(products(owner), viewerAuth).statusCode()).isEqualTo(200);
        assertThat(api.post(products(owner), tShirt("Nope", "DRAFT", "NOPE"), viewerAuth).statusCode()).isEqualTo(403);
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
