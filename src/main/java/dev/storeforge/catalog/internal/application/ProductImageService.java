package dev.storeforge.catalog.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.catalog.internal.domain.CatalogExceptions;
import dev.storeforge.catalog.internal.domain.ImageSignatures;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.storage.ObjectStorage;
import dev.storeforge.storage.ObjectStorage.PresignedUpload;
import dev.storeforge.storage.ObjectStorage.StoredObject;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Product images (ADR-024). Upload in three steps, bytes never passing through the application:
 * <ol>
 *   <li>{@link #startUpload}: validate the declared type and size, record a PENDING image, sign a
 *       PUT URL for exactly that key, type and size (10 minutes).</li>
 *   <li>The client PUTs the file to object storage.</li>
 *   <li>{@link #complete}: look at what was ACTUALLY stored (size, file signature) and only then
 *       make the image READY. Anything else is discarded.</li>
 * </ol>
 * Storage calls happen outside database transactions (they are network calls). Abandoned uploads
 * are removed by {@link ImageUploadSweeper}; deleted objects go through a retried job.
 */
@Service
@RequiredArgsConstructor
public class ProductImageService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_IMAGES_PER_PRODUCT = 20;
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final TenantTransactions tenant;
    private final StoreAccess access;
    private final AuditLog audit;
    private final Clock clock;

    public record Upload(UUID imageId, PresignedUpload upload) {
    }

    public record ImageView(UUID id, URI url, @Nullable String alt, int position, String contentType, long sizeBytes) {
    }

    private record Row(UUID id, String key, String contentType, long sizeBytes, @Nullable String alt, @Nullable Integer position, String status) {
    }

    public Upload startUpload(UUID userId, StoreId storeId, UUID productId, String contentType, long sizeBytes, @Nullable String alt) {
        if (!ImageSignatures.ACCEPTED.contains(contentType)) {
            throw new CatalogExceptions.InvalidImage("contentType must be one of " + ImageSignatures.ACCEPTED.stream().sorted().toList());
        }
        if (sizeBytes < 1 || sizeBytes > MAX_BYTES) {
            throw new CatalogExceptions.InvalidImage("sizeBytes must be between 1 and " + MAX_BYTES);
        }
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            // Locks the product: concurrent uploads can't both pass the count check.
            jdbc.sql("SELECT id FROM products WHERE store_id = :store AND id = :product FOR UPDATE")
                    .param("store", storeId.value()).param("product", productId)
                    .query(UUID.class).optional().orElseThrow(CatalogExceptions.ProductNotFound::new);
            int existing = jdbc.sql("SELECT count(*) FROM product_images WHERE store_id = :store AND product_id = :product")
                    .param("store", storeId.value()).param("product", productId).query(Integer.class).single();
            if (existing >= MAX_IMAGES_PER_PRODUCT) {
                throw new CatalogExceptions.TooManyImages(MAX_IMAGES_PER_PRODUCT);
            }
            UUID imageId = Ids.newId();
            String key = "stores/" + storeId.value() + "/products/" + productId + "/" + imageId;
            jdbc.sql("""
                    INSERT INTO product_images (id, store_id, product_id, object_key, content_type, size_bytes, alt, status, created_at)
                    VALUES (:id, :store, :product, :key, :type, :size, :alt, 'PENDING', :now)
                    """)
                    .param("id", imageId).param("store", storeId.value()).param("product", productId).param("key", key)
                    .param("type", contentType).param("size", sizeBytes).param("alt", alt).param("now", Timestamp.from(clock.instant()))
                    .update();
            return new Upload(imageId, storage.presignUpload(key, contentType, sizeBytes, UPLOAD_TTL));
        });
    }

    public ImageView complete(UUID userId, StoreId storeId, UUID productId, UUID imageId) {
        Row image = tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            return find(storeId, productId, imageId).orElseThrow(CatalogExceptions.ImageNotFound::new);
        });
        if (image.status().equals("READY")) {
            return view(image);   // completing twice is harmless (client retry)
        }
        // Outside any transaction: network calls to object storage.
        StoredObject stored = storage.head(image.key()).orElseThrow(CatalogExceptions.UploadMissing::new);
        String problem = stored.size() != image.sizeBytes()
                ? "stored " + stored.size() + " bytes, declared " + image.sizeBytes()
                : ImageSignatures.matches(image.contentType(), storage.readPrefix(image.key(), ImageSignatures.PREFIX_LENGTH)) ? null
                : "the file is not a " + image.contentType() + " image";
        if (problem != null) {
            tenant.runInStore(storeId, () -> discard(storeId, image));
            throw new CatalogExceptions.UploadRejected("Upload discarded: " + problem + ". Start a new upload.");
        }
        return tenant.inStore(storeId, () -> {
            int updated = jdbc.sql("""
                    UPDATE product_images
                       SET status = 'READY', completed_at = :now,
                           position = (SELECT coalesce(max(position), 0) + 1 FROM product_images
                                        WHERE store_id = :store AND product_id = :product AND status = 'READY')
                     WHERE store_id = :store AND id = :id AND status = 'PENDING'
                    """)
                    .param("now", Timestamp.from(clock.instant())).param("store", storeId.value()).param("product", productId)
                    .param("id", imageId).update();
            if (updated == 1) {
                audit.recordUserAction(storeId, userId, "product.image_added", "product", productId.toString(),
                        Map.of("imageId", imageId.toString(), "contentType", image.contentType(), "sizeBytes", image.sizeBytes()));
            }
            // 0 rows: a concurrent completion won (fine), or the sweeper removed it (not found).
            return view(find(storeId, productId, imageId).filter(row -> row.status().equals("READY"))
                    .orElseThrow(CatalogExceptions.ImageNotFound::new));
        });
    }

    public List<ImageView> list(UUID userId, StoreId storeId, UUID productId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_READ);
            return ready(storeId, List.of(productId)).getOrDefault(productId, List.of());
        });
    }

    public void delete(UUID userId, StoreId storeId, UUID productId, UUID imageId) {
        tenant.runInStore(storeId, () -> {
            access.require(userId, storeId, Permission.PRODUCTS_WRITE);
            Row image = find(storeId, productId, imageId).orElseThrow(CatalogExceptions.ImageNotFound::new);
            discard(storeId, image);
            audit.recordUserAction(storeId, userId, "product.image_removed", "product", productId.toString(), Map.of("imageId", imageId.toString()));
        });
    }

    /** READY images of the given products, in position order, with download URLs. Caller holds the tenant transaction. */
    Map<UUID, List<ImageView>> ready(StoreId storeId, Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        record Ready(UUID productId, Row row) {
        }
        return jdbc.sql("""
                        SELECT product_id, id, object_key, content_type, size_bytes, alt, position, status FROM product_images
                         WHERE store_id = :store AND product_id IN (:products) AND status = 'READY' ORDER BY product_id, position
                        """)
                .param("store", storeId.value()).param("products", productIds)
                .query((rs, n) -> new Ready(rs.getObject("product_id", UUID.class), row(rs)))
                .list().stream()
                .collect(Collectors.groupingBy(Ready::productId, Collectors.mapping(r -> view(r.row()), Collectors.toList())));
    }

    /** Row and object go together: the row now, the object after commit (retried job). */
    private void discard(StoreId storeId, Row image) {
        jdbc.sql("DELETE FROM product_images WHERE store_id = :store AND id = :id").param("store", storeId.value()).param("id", image.id()).update();
        storage.deleteLater(storeId, image.key());
    }

    private Optional<Row> find(StoreId storeId, UUID productId, UUID imageId) {
        return jdbc.sql("""
                        SELECT id, object_key, content_type, size_bytes, alt, position, status FROM product_images
                         WHERE store_id = :store AND product_id = :product AND id = :id
                        """)
                .param("store", storeId.value()).param("product", productId).param("id", imageId)
                .query((rs, n) -> row(rs)).optional();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_key"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("alt"), rs.getObject("position", Integer.class), rs.getString("status"));
    }

    private ImageView view(Row row) {
        return new ImageView(row.id(), storage.presignDownload(row.key(), DOWNLOAD_TTL), row.alt(), row.position() == null ? 0 : row.position(),
                row.contentType(), row.sizeBytes());
    }
}
