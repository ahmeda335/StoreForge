package dev.storeforge.customers.internal;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.shared.privacy.PersonalData;
import dev.storeforge.shared.privacy.PersonalData.DataSubject;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * A customer's right to their data (ADR-027): export everything the store holds about them, or
 * erase it. Every module contributes through {@link PersonalData}; all of it runs in ONE tenant
 * transaction (complete export, all-or-nothing erasure). Both are audited, without the data itself.
 */
@Service
@RequiredArgsConstructor
class CustomerPrivacyService {

    private final JdbcClient jdbc;
    private final CustomerStore customers;
    private final List<PersonalData> modules;
    private final StoreAccess access;
    private final TenantTransactions tenant;
    private final AuditLog audit;
    private final Clock clock;

    Map<String, Object> export(UUID userId, StoreId storeId, UUID customerId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.CUSTOMERS_READ);
            var customer = customers.find(storeId, customerId).orElseThrow(CustomersController.CustomerNotFound::new);
            DataSubject subject = subject(customer.id(), customer.email());
            Map<String, Object> export = new LinkedHashMap<>();
            export.put("customer", Map.of("id", customer.id(), "email", customer.email(), "firstName", String.valueOf(customer.firstName()),
                    "lastName", String.valueOf(customer.lastName()), "createdAt", customer.createdAt().toString()));
            for (PersonalData module : modules) {
                Object section = module.export(storeId, subject);
                if (section != null) {
                    export.put(module.section(), section);
                }
            }
            export.put("exportedAt", clock.instant().toString());
            audit.recordUserAction(storeId, userId, "customer.exported", "customer", customerId.toString(), Map.of());
            return export;
        });
    }

    void erase(UUID userId, StoreId storeId, UUID customerId, String reason) {
        tenant.runInStore(storeId, () -> {
            access.require(userId, storeId, Permission.CUSTOMERS_ERASE);
            String email = jdbc.sql("SELECT email FROM customers WHERE store_id = :store AND id = :id AND erased_at IS NULL FOR UPDATE")
                    .param("store", storeId.value()).param("id", customerId).query(String.class).optional()
                    .orElseThrow(CustomersController.CustomerNotFound::new);
            DataSubject subject = subject(customerId, email);
            modules.forEach(module -> module.checkErasable(storeId, subject));
            modules.forEach(module -> module.erase(storeId, subject));
            jdbc.sql("""
                    UPDATE customers SET email = :replacement, first_name = NULL, last_name = NULL, erased_at = :now, updated_at = :now
                     WHERE store_id = :store AND id = :id
                    """)
                    .param("replacement", subject.replacementEmail()).param("now", Timestamp.from(clock.instant()))
                    .param("store", storeId.value()).param("id", customerId).update();
            audit.recordUserAction(storeId, userId, "customer.erased", "customer", customerId.toString(),
                    Map.of("reason", reason, "sections", modules.stream().map(PersonalData::section).sorted().toList()));
        });
    }

    private static DataSubject subject(UUID customerId, String email) {
        return new DataSubject(customerId, email, "erased+" + customerId + "@erased.invalid");
    }
}
