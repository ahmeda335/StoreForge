package dev.storeforge.checkout.internal;

import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Carts and their items. Every lookup requires the cart token's hash: holding the token IS the authorization. */
@Repository
class CartStore {

    private final JdbcClient jdbc;

    CartStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Cart(UUID id, String status, Instant expiresAt) {
        boolean open(Instant now) {
            return "OPEN".equals(status) && expiresAt.isAfter(now);
        }
    }

    record Item(UUID variantId, int quantity) {
    }

    void create(StoreId storeId, UUID cartId, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.sql("""
                INSERT INTO carts (id, store_id, token_hash, status, expires_at, created_at, updated_at)
                VALUES (:id, :storeId, :tokenHash, 'OPEN', :expiresAt, :now, :now)
                """)
                .param("id", cartId)
                .param("storeId", storeId.value())
                .param("tokenHash", tokenHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    Optional<Cart> find(StoreId storeId, UUID cartId, String tokenHash) {
        return jdbc.sql("SELECT id, status, expires_at FROM carts WHERE store_id = :storeId AND id = :id AND token_hash = :tokenHash")
                .param("storeId", storeId.value())
                .param("id", cartId)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new Cart(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    List<Item> items(StoreId storeId, UUID cartId) {
        return jdbc.sql("SELECT variant_id, quantity FROM cart_items WHERE store_id = :storeId AND cart_id = :cartId ORDER BY created_at, variant_id")
                .param("storeId", storeId.value())
                .param("cartId", cartId)
                .query((rs, rowNum) -> new Item(rs.getObject("variant_id", UUID.class), rs.getInt("quantity")))
                .list();
    }

    int lineCount(StoreId storeId, UUID cartId) {
        return jdbc.sql("SELECT count(*) FROM cart_items WHERE store_id = :storeId AND cart_id = :cartId")
                .param("storeId", storeId.value())
                .param("cartId", cartId)
                .query(Integer.class)
                .single();
    }

    /** Adding the same variant again increments its quantity (one statement, safe under concurrent adds). */
    void addItem(StoreId storeId, UUID cartId, UUID variantId, int quantity, Instant now) {
        jdbc.sql("""
                INSERT INTO cart_items (store_id, cart_id, variant_id, quantity, created_at, updated_at)
                VALUES (:storeId, :cartId, :variantId, :quantity, :now, :now)
                ON CONFLICT (cart_id, variant_id) DO UPDATE
                   SET quantity = cart_items.quantity + excluded.quantity, updated_at = excluded.updated_at
                """)
                .param("storeId", storeId.value())
                .param("cartId", cartId)
                .param("variantId", variantId)
                .param("quantity", quantity)
                .param("now", Timestamp.from(now))
                .update();
    }

    int setQuantity(StoreId storeId, UUID cartId, UUID variantId, int quantity, Instant now) {
        return jdbc.sql("""
                UPDATE cart_items SET quantity = :quantity, updated_at = :now
                 WHERE store_id = :storeId AND cart_id = :cartId AND variant_id = :variantId
                """)
                .param("storeId", storeId.value())
                .param("cartId", cartId)
                .param("variantId", variantId)
                .param("quantity", quantity)
                .param("now", Timestamp.from(now))
                .update();
    }

    int removeItem(StoreId storeId, UUID cartId, UUID variantId) {
        return jdbc.sql("DELETE FROM cart_items WHERE store_id = :storeId AND cart_id = :cartId AND variant_id = :variantId")
                .param("storeId", storeId.value())
                .param("cartId", cartId)
                .param("variantId", variantId)
                .update();
    }

    void markConverted(StoreId storeId, UUID cartId, Instant now) {
        jdbc.sql("UPDATE carts SET status = 'CONVERTED', updated_at = :now WHERE store_id = :storeId AND id = :id")
                .param("storeId", storeId.value())
                .param("id", cartId)
                .param("now", Timestamp.from(now))
                .update();
    }
}
