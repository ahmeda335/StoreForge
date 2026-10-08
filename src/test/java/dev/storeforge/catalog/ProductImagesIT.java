package dev.storeforge.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.catalog.internal.application.ImageUploadSweeper;
import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.storage.ObjectStorage;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Product images against a real S3-compatible store: presigned upload, verification, cleanup, isolation. */
class ProductImagesIT extends IntegrationTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ObjectStorage storage;

    @Autowired
    JobWorker jobWorker;

    @Autowired
    ImageUploadSweeper sweeper;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    JdbcClient jdbc;

    Owner shop;
    UUID product;

    @BeforeEach
    void productWithoutImages() {
        shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        product = new CatalogFlows(api).createProduct(shop, "Lamp " + UUID.randomUUID(), "ACTIVE");
    }

    @Test
    void anImageIsUploadedDirectlyToStorageVerifiedAndShownInTheStorefront() throws Exception {
        byte[] png = png(300);
        JsonNode started = start("image/png", png.length, "Brass lamp, side view");
        String uploadUrl = started.get("upload").get("url").asString();
        assertThat(uploadUrl).as("tenant-prefixed key chosen by the server").contains("stores/" + shop.storeId() + "/products/" + product + "/");
        assertThat(put(uploadUrl, "image/png", png)).isEqualTo(200);

        HttpResponse<String> completed = complete(started);
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(Json.parse(completed.body()).get("position").asInt()).isEqualTo(1);
        assertThat(complete(started).statusCode()).as("completing twice is harmless").isEqualTo(200);

        JsonNode listed = Json.parse(api.get(imagesPath(""), shop.auth()).body()).get("data");
        assertThat(listed).hasSize(1);
        assertThat(download(listed.get(0).get("url").asString())).isEqualTo(png);

        JsonNode storefrontProduct = Json.parse(api.get("/storefront/v1/products?limit=50", new StorefrontFlows(api, shop).headers(null)).body())
                .get("data").valueStream().filter(p -> p.get("id").asString().equals(product.toString())).findFirst().orElseThrow();
        assertThat(storefrontProduct.get("images").get(0).get("alt").asString()).isEqualTo("Brass lamp, side view");
        assertThat(download(storefrontProduct.get("images").get(0).get("url").asString())).isEqualTo(png);
    }

    /** The declared type is a claim; the stored bytes are the evidence. */
    @Test
    void aFileThatIsNotTheDeclaredImageIsDiscarded() throws Exception {
        byte[] script = "<svg onload=alert(document.cookie)>  ".getBytes(StandardCharsets.US_ASCII);
        JsonNode started = start("image/png", script.length, null);
        assertThat(put(started.get("upload").get("url").asString(), "image/png", script)).isEqualTo(200);

        HttpResponse<String> completed = complete(started);
        assertThat(completed.statusCode()).isEqualTo(422);
        assertThat(Json.parse(completed.body()).get("code").asString()).isEqualTo("upload_rejected");
        assertThat(Json.parse(api.get(imagesPath(""), shop.auth()).body()).get("data")).isEmpty();
        jobWorker.drain();
        assertThat(storage.head(key(started))).as("object deleted by the job").isEmpty();
    }

    @Test
    void theSignedUrlAcceptsOnlyTheDeclaredSizeAndType() throws Exception {
        byte[] png = png(100);
        JsonNode started = start("image/png", png.length, null);
        String url = started.get("upload").get("url").asString();
        assertThat(put(url, "image/png", png(5000))).as("bigger than declared").isGreaterThanOrEqualTo(400);
        assertThat(put(url, "text/html", png)).as("other content type").isGreaterThanOrEqualTo(400);
        assertThat(Json.parse(complete(started).body()).get("code").asString()).isEqualTo("upload_missing");
        assertThat(put(url, "image/png", png)).isEqualTo(200);
        assertThat(complete(started).statusCode()).isEqualTo(200);
    }

    @Test
    void onlyRasterImagesWithinTheLimitsCanBeUploaded() {
        assertThat(code(api.post(imagesPath("/uploads"), "{\"contentType\":\"image/svg+xml\",\"sizeBytes\":100}", shop.auth())))
                .isEqualTo("invalid_image");
        assertThat(code(api.post(imagesPath("/uploads"), "{\"contentType\":\"image/png\",\"sizeBytes\":10485761}", shop.auth())))
                .isEqualTo("invalid_image");
        for (int i = 0; i < 20; i++) {
            start("image/jpeg", 10, null);
        }
        assertThat(code(api.post(imagesPath("/uploads"), "{\"contentType\":\"image/jpeg\",\"sizeBytes\":10}", shop.auth())))
                .as("uploads in progress count too").isEqualTo("too_many_images");
    }

    @Test
    void anotherStoreCanNeitherCompleteNorSeeTheImage() throws Exception {
        byte[] png = png(64);
        JsonNode started = start("image/png", png.length, null);
        put(started.get("upload").get("url").asString(), "image/png", png);
        Owner other = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());

        String viaOwnStore = "/admin/v1/stores/" + other.storeId() + "/products/" + product + "/images/" + started.get("imageId").asString();
        assertThat(api.post(viaOwnStore + "/completion", "{}", other.auth()).statusCode()).isEqualTo(404);
        assertThat(api.post(imagesPath("/" + started.get("imageId").asString() + "/completion"), "{}", other.auth()).statusCode())
                .as("not a member: 404, the store's existence isn't revealed").isEqualTo(404);
        assertThat(complete(started).statusCode()).isEqualTo(200);
    }

    @Test
    void abandonedUploadsAreRemovedWithTheirObjects() throws Exception {
        byte[] png = png(64);
        JsonNode abandoned = start("image/png", png.length, null);
        put(abandoned.get("upload").get("url").asString(), "image/png", png);
        JsonNode recent = start("image/png", png.length, null);
        tenant.runInStore(StoreId.of(shop.storeId()), () -> jdbc.sql("UPDATE product_images SET created_at = now() - interval '2 hours' WHERE id = :id")
                .param("id", UUID.fromString(abandoned.get("imageId").asString())).update());

        assertThat(sweeper.sweepOnce()).isGreaterThanOrEqualTo(1);
        jobWorker.drain();

        assertThat(complete(abandoned).statusCode()).isEqualTo(404);
        assertThat(storage.head(key(abandoned))).isEmpty();
        assertThat(Json.parse(complete(recent).body()).get("code").asString()).as("a fresh upload is left alone").isEqualTo("upload_missing");
    }

    @Test
    void deletingAnImageDeletesItsObject() throws Exception {
        byte[] png = png(64);
        JsonNode started = start("image/png", png.length, null);
        put(started.get("upload").get("url").asString(), "image/png", png);
        complete(started);
        assertThat(storage.head(key(started))).isPresent();

        assertThat(api.delete(imagesPath("/" + started.get("imageId").asString()), shop.auth()).statusCode()).isEqualTo(204);
        assertThat(Json.parse(api.get(imagesPath(""), shop.auth()).body()).get("data")).isEmpty();
        jobWorker.drain();
        assertThat(storage.head(key(started))).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private JsonNode start(String contentType, int size, String alt) {
        String body = alt == null ? Json.object("contentType", contentType).replace("}", ",\"sizeBytes\":" + size + "}")
                : Json.object("contentType", contentType, "alt", alt).replace("}", ",\"sizeBytes\":" + size + "}");
        HttpResponse<String> response = api.post(imagesPath("/uploads"), body, shop.auth());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return Json.parse(response.body());
    }

    private HttpResponse<String> complete(JsonNode started) {
        return api.post(imagesPath("/" + started.get("imageId").asString() + "/completion"), "{}", shop.auth());
    }

    private String key(JsonNode started) {
        return "stores/" + shop.storeId() + "/products/" + product + "/" + started.get("imageId").asString();
    }

    private String imagesPath(String suffix) {
        return "/admin/v1/stores/" + shop.storeId() + "/products/" + product + "/images" + suffix;
    }

    private static int put(String url, String contentType, byte[] body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 7);
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, bytes, 0, 8);
        return bytes;
    }

    private static String code(HttpResponse<String> response) {
        JsonNode body = Json.parse(response.body());
        return body.has("code") ? body.get("code").asString() : "HTTP " + response.statusCode();
    }
}
