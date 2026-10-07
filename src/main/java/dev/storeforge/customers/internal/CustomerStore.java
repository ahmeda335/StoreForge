package dev.storeforge.customers.internal;

import dev.storeforge.customers.Customers;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class CustomerStore implements Customers {

    private final JdbcClient jdbc;
    private final Clock clock;

    record CustomerRow(UUID id, String email, @Nullable String firstName, @Nullable String lastName, Instant createdAt) {
    }

    /**
     * "Upsert" in one statement: INSERT ... ON CONFLICT DO UPDATE always returns the row's id, whether
     * it was just created or already existed. A SELECT-then-INSERT would race (two concurrent
     * checkouts both see "no customer" and the second INSERT fails on the unique constraint).
     * Names are only filled in when still empty, so a guest checkout can't overwrite them.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID findOrCreate(StoreId storeId, String normalizedEmail, @Nullable String firstName, @Nullable String lastName) {
        Instant now = clock.instant();
        return jdbc.sql("""
                INSERT INTO customers (id, store_id, email, first_name, last_name, created_at, updated_at)
                VALUES (:id, :storeId, :email, :firstName, :lastName, :now, :now)
                ON CONFLICT (store_id, email) DO UPDATE
                   SET first_name = coalesce(customers.first_name, excluded.first_name),
                       last_name = coalesce(customers.last_name, excluded.last_name),
                       updated_at = excluded.updated_at
                RETURNING id
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("email", normalizedEmail)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .single();
    }

    List<CustomerRow> page(StoreId storeId, int limitPlusOne, @Nullable Cursor after) {
        String keyset = after == null ? "" : "AND (created_at, id) < (:afterTime, :afterId)";
        var statement = jdbc.sql("""
                SELECT id, email, first_name, last_name, created_at FROM customers
                 WHERE store_id = :storeId %s ORDER BY created_at DESC, id DESC LIMIT :limit
                """.formatted(keyset))
                .param("storeId", storeId.value())
                .param("limit", limitPlusOne);
        if (after != null) {
            statement = statement.param("afterTime", Timestamp.from(after.timestamp())).param("afterId", after.id());
        }
        return statement.query((rs, rowNum) -> row(rs)).list();
    }

    Optional<CustomerRow> find(StoreId storeId, UUID customerId) {
        return jdbc.sql("SELECT id, email, first_name, last_name, created_at FROM customers WHERE store_id = :storeId AND id = :id")
                .param("storeId", storeId.value())
                .param("id", customerId)
                .query((rs, rowNum) -> row(rs))
                .optional();
    }

    private static CustomerRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getTimestamp("created_at").toInstant());
    }
}
