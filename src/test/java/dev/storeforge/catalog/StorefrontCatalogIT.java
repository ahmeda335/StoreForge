package dev.storeforge.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.QueryCounter;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The public storefront: store from the hostname, only ACTIVE products, no internal fields, and
 * no way to reach another store's products.
 */
class StorefrontCatalogIT extends IntegrationTest {

    StoreFlows stores;
    CatalogFlows catalog;
    Owner shopA;
    Owner shopB;

    @BeforeEach
    void twoShops() {
        stores = new StoreFlows(api, mailbox);
        catalog = new CatalogFlows(api);
        shopA = stores.newOwnerWithStore(uniqueEmail());
        shopB = stores.newOwnerWithStore(uniqueEmail());
    }

    @Test
    void showsOnlyActiveProductsOfTheStoreNamedByTheHost() {
        catalog.createProduct(shopA, "Visible Tee", "ACTIVE");
        catalog.createProduct(shopA, "Draft Tee", "DRAFT");
        catalog.createProduct(shopB, "Other Shop Tee", "ACTIVE");

        var products = Json.parse(storefront(shopA, "/storefront/v1/products").body()).get("data");

        assertThat(products.valueStream().map(p -> p.get("title").asString())).containsExactly("Visible Tee");
        var variant = products.get(0).get("variants").get(0);
        assertThat(variant.get("price").get("amount").asString()).isEqualTo("19.99");
        assertThat(variant.has("sku")).as("internal fields are not exposed").isFalse();
        assertThat(products.get(0).has("status")).isFalse();
    }

    @Test
    void productHandlesResolvePerStore() {
        catalog.createProduct(shopB, "Shop B Exclusive", "ACTIVE");
        catalog.createProduct(shopA, "Shop A Draft", "DRAFT");

        assertThat(storefront(shopB, "/storefront/v1/products/shop-b-exclusive").statusCode()).isEqualTo(200);
        assertThat(storefront(shopA, "/storefront/v1/products/shop-b-exclusive").statusCode())
                .as("B's product through A's hostname").isEqualTo(404);
        assertThat(storefront(shopA, "/storefront/v1/products/shop-a-draft").statusCode()).as("draft").isEqualTo(404);
    }

    @Test
    void unknownHostsAreNotFound() {
        HttpResponse<String> response = api.get("/storefront/v1/products", Map.of("X-Forwarded-Host", "nope.storeforge.localhost"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("store_not_found");
    }

    /** The N+1 detector: the number of SQL statements must not depend on how many products are on the page. */
    @Test
    void listQueryCountIsIndependentOfPageSize() {
        catalog.createProduct(shopA, "Warm-up", "ACTIVE");
        storefront(shopA, "/storefront/v1/products");   // warm the store-directory cache

        QueryCounter.reset();
        storefront(shopA, "/storefront/v1/products");
        long withOneProduct = QueryCounter.count();

        for (int i = 0; i < 9; i++) {
            catalog.createProduct(shopA, "Bulk " + i, "ACTIVE");
        }
        QueryCounter.reset();
        var response = storefront(shopA, "/storefront/v1/products");
        long withTenProducts = QueryCounter.count();

        assertThat(Json.parse(response.body()).get("data").size()).isEqualTo(10);
        assertThat(withTenProducts).isEqualTo(withOneProduct);
    }

    private HttpResponse<String> storefront(Owner shop, String path) {
        String handle = Json.parse(api.get("/admin/v1/stores/" + shop.storeId(), shop.auth()).body()).get("handle").asString();
        return api.get(path, Map.of("X-Forwarded-Host", handle + ".storeforge.localhost"));
    }
}
