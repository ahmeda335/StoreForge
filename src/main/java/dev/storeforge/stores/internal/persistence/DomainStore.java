package dev.storeforge.stores.internal.persistence;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Hostnames → store (tenant directory). Resolution for the storefront arrives in P4. */
@Repository
@RequiredArgsConstructor
public class DomainStore {

    private final JdbcClient jdbc;

    /** Platform subdomains are verified by construction (we own the parent domain). */
    public void insertPlatformDomain(StoreId storeId, String hostname, Instant now) {
        jdbc.sql("""
                INSERT INTO store_domains (id, store_id, hostname, is_primary, verified_at, created_at)
                VALUES (:id, :storeId, :hostname, true, :now, :now)
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("hostname", hostname)
                .param("now", Timestamp.from(now))
                .update();
    }
}
