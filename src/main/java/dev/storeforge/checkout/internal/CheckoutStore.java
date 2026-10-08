package dev.storeforge.checkout.internal;

import dev.storeforge.shared.tenancy.StoreId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class CheckoutStore {

    private final JdbcClient jdbc;

    record Address(String name, String line1, @Nullable String line2, String city, @Nullable String region,
                   String postalCode, String country) {
    }

    record Checkout(UUID id, UUID cartId, String status, @Nullable String email, @Nullable Address address,
                    @Nullable UUID customerId, String currency, @Nullable Long subtotalMinor, @Nullable Long shippingMinor,
                    @Nullable Long taxMinor, @Nullable Long totalMinor, @Nullable Instant completedAt,
                    @Nullable Instant reservationExpiresAt, @Nullable UUID orderId, @Nullable Long orderNumber,
                    @Nullable String discountCode, @Nullable Long discountMinor) {
    }

    record Line(UUID variantId, String productTitle, String variantTitle, @Nullable String sku, long unitPriceMinor,
                int quantity, long lineTotalMinor) {
    }

    private static final String COLUMNS = """
            c.id, c.cart_id, c.status, c.email, c.ship_name, c.ship_line1, c.ship_line2, c.ship_city, c.ship_region,
            c.ship_postal_code, c.ship_country, c.customer_id, c.currency, c.subtotal_minor, c.shipping_minor, c.tax_minor,
            c.total_minor, c.completed_at, c.reservation_expires_at, c.order_id, c.discount_code, c.discount_minor,
            (SELECT o.number FROM orders o WHERE o.store_id = c.store_id AND o.id = c.order_id) AS order_number""";

    void create(StoreId storeId, UUID checkoutId, UUID cartId, String currency, Instant now) {
        jdbc.sql("""
                INSERT INTO checkouts (id, store_id, cart_id, status, currency, created_at, updated_at)
                VALUES (:id, :storeId, :cartId, 'OPEN', :currency, :now, :now)
                """)
                .param("id", checkoutId)
                .param("storeId", storeId.value())
                .param("cartId", cartId)
                .param("currency", currency)
                .param("now", Timestamp.from(now))
                .update();
    }

    Optional<Checkout> openForCart(StoreId storeId, UUID cartId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM checkouts c WHERE c.store_id = :storeId AND c.cart_id = :cartId AND c.status = 'OPEN'")
                .param("storeId", storeId.value())
                .param("cartId", cartId)
                .query(CheckoutStore::checkout)
                .optional();
    }

    /**
     * The checkout, authorized by the token of the cart it was created from. With {@code lock}, the
     * row is locked FOR UPDATE: concurrent completions of the same checkout run one after another,
     * and the second sees status COMPLETED.
     */
    Optional<Checkout> find(StoreId storeId, UUID checkoutId, String cartTokenHash, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM checkouts c JOIN carts k ON k.store_id = c.store_id AND k.id = c.cart_id
                WHERE c.store_id = :storeId AND c.id = :id AND k.token_hash = :tokenHash
                """ + (lock ? " FOR UPDATE OF c" : ""))
                .param("storeId", storeId.value())
                .param("id", checkoutId)
                .param("tokenHash", cartTokenHash)
                .query(CheckoutStore::checkout)
                .optional();
    }

    void updateContact(StoreId storeId, UUID checkoutId, @Nullable String email, @Nullable Address address, Instant now) {
        jdbc.sql("""
                UPDATE checkouts SET email = coalesce(:email, email),
                       ship_name = coalesce(:name, ship_name), ship_line1 = coalesce(:line1, ship_line1),
                       ship_line2 = CASE WHEN :hasAddress THEN :line2 ELSE ship_line2 END,
                       ship_city = coalesce(:city, ship_city),
                       ship_region = CASE WHEN :hasAddress THEN :region ELSE ship_region END,
                       ship_postal_code = coalesce(:postalCode, ship_postal_code), ship_country = coalesce(:country, ship_country),
                       updated_at = :now
                 WHERE store_id = :storeId AND id = :id AND status = 'OPEN'
                """)
                .param("email", email)
                .param("hasAddress", address != null)
                .param("name", address == null ? null : address.name())
                .param("line1", address == null ? null : address.line1())
                .param("line2", address == null ? null : address.line2())
                .param("city", address == null ? null : address.city())
                .param("region", address == null ? null : address.region())
                .param("postalCode", address == null ? null : address.postalCode())
                .param("country", address == null ? null : address.country())
                .param("now", Timestamp.from(now))
                .param("storeId", storeId.value())
                .param("id", checkoutId)
                .update();
    }

    void insertLines(StoreId storeId, UUID checkoutId, List<Line> lines) {
        for (Line line : lines) {
            jdbc.sql("""
                    INSERT INTO checkout_lines (store_id, checkout_id, variant_id, product_title, variant_title, sku,
                                                unit_price_minor, quantity, line_total_minor)
                    VALUES (:storeId, :checkoutId, :variantId, :productTitle, :variantTitle, :sku, :unitPrice, :quantity, :lineTotal)
                    """)
                    .param("storeId", storeId.value())
                    .param("checkoutId", checkoutId)
                    .param("variantId", line.variantId())
                    .param("productTitle", line.productTitle())
                    .param("variantTitle", line.variantTitle())
                    .param("sku", line.sku())
                    .param("unitPrice", line.unitPriceMinor())
                    .param("quantity", line.quantity())
                    .param("lineTotal", line.lineTotalMinor())
                    .update();
        }
    }

    List<Line> lines(StoreId storeId, UUID checkoutId) {
        return jdbc.sql("""
                SELECT variant_id, product_title, variant_title, sku, unit_price_minor, quantity, line_total_minor
                  FROM checkout_lines WHERE store_id = :storeId AND checkout_id = :checkoutId ORDER BY product_title, variant_id
                """)
                .param("storeId", storeId.value())
                .param("checkoutId", checkoutId)
                .query((rs, rowNum) -> new Line(rs.getObject("variant_id", UUID.class), rs.getString("product_title"),
                        rs.getString("variant_title"), rs.getString("sku"), rs.getLong("unit_price_minor"), rs.getInt("quantity"),
                        rs.getLong("line_total_minor")))
                .list();
    }

    void setDiscountCode(StoreId storeId, UUID checkoutId, @Nullable String code, Instant now) {
        jdbc.sql("UPDATE checkouts SET discount_code = :code, updated_at = :now WHERE store_id = :storeId AND id = :id AND status = 'OPEN'")
                .param("code", code).param("now", Timestamp.from(now)).param("storeId", storeId.value()).param("id", checkoutId).update();
    }

    void complete(StoreId storeId, UUID checkoutId, UUID customerId, UUID orderId, long subtotal, long discount, long shipping, long tax,
                  long total, Instant reservationExpiresAt, Instant now) {
        jdbc.sql("""
                UPDATE checkouts SET status = 'COMPLETED', customer_id = :customerId, order_id = :orderId, subtotal_minor = :subtotal,
                       discount_minor = :discount,
                       shipping_minor = :shipping, tax_minor = :tax, total_minor = :total, completed_at = :now,
                       reservation_expires_at = :reservationExpiresAt, updated_at = :now
                 WHERE store_id = :storeId AND id = :id AND status = 'OPEN'
                """)
                .param("customerId", customerId)
                .param("orderId", orderId)
                .param("subtotal", subtotal)
                .param("discount", discount)
                .param("shipping", shipping)
                .param("tax", tax)
                .param("total", total)
                .param("reservationExpiresAt", Timestamp.from(reservationExpiresAt))
                .param("now", Timestamp.from(now))
                .param("storeId", storeId.value())
                .param("id", checkoutId)
                .update();
    }

    private static Checkout checkout(ResultSet rs, int rowNum) throws SQLException {
        Address address = rs.getString("ship_line1") == null ? null : new Address(rs.getString("ship_name"), rs.getString("ship_line1"),
                rs.getString("ship_line2"), rs.getString("ship_city"), rs.getString("ship_region"), rs.getString("ship_postal_code"),
                rs.getString("ship_country"));
        return new Checkout(rs.getObject("id", UUID.class), rs.getObject("cart_id", UUID.class), rs.getString("status"),
                rs.getString("email"), address, rs.getObject("customer_id", UUID.class), rs.getString("currency").trim(),
                rs.getObject("subtotal_minor", Long.class), rs.getObject("shipping_minor", Long.class), rs.getObject("tax_minor", Long.class),
                rs.getObject("total_minor", Long.class), instant(rs, "completed_at"), instant(rs, "reservation_expires_at"),
                rs.getObject("order_id", UUID.class), rs.getObject("order_number", Long.class), rs.getString("discount_code"),
                rs.getObject("discount_minor", Long.class));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
