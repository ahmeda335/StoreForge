package dev.storeforge.stores.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.shared.error.ConstraintViolations;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.StoreNotFoundException;
import dev.storeforge.stores.internal.domain.Role;
import dev.storeforge.stores.internal.domain.Store;
import dev.storeforge.stores.internal.domain.StoreHandle;
import dev.storeforge.stores.internal.persistence.DomainStore;
import dev.storeforge.stores.internal.persistence.MemberStore;
import dev.storeforge.stores.internal.persistence.MemberStore.StoreSummary;
import dev.storeforge.stores.internal.persistence.RoleRepository;
import dev.storeforge.stores.internal.persistence.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository stores;
    private final RoleRepository roles;
    private final MemberStore members;
    private final DomainStore domains;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final StoresProperties properties;
    private final Clock clock;

    /**
     * Provisions a tenant in ONE transaction: store row, platform domain, owner and default staff
     * roles, the creator's owner membership, and the first audit entry. A failure anywhere leaves
     * nothing behind: no half-created stores. The transaction is bound to the NEW store's id, so
     * the RLS WITH CHECK policies accept the role and audit rows.
     */
    public Store create(UUID userId, String rawHandle, String name, String currency) {
        StoreHandle handle;
        try {
            handle = new StoreHandle(rawHandle);
        } catch (IllegalArgumentException e) {
            throw new StoreExceptions.InvalidHandle();
        }
        if (handle.isReserved()) {
            throw new StoreExceptions.HandleUnavailable();
        }
        StoreId storeId = StoreId.of(Ids.newId());
        Instant now = clock.instant();
        try {
            return tenant.inStore(storeId, () -> {
                Store store = stores.saveAndFlush(new Store(storeId, handle, name.trim(), currency, userId, now));
                domains.insertPlatformDomain(storeId, handle.value() + "." + properties.platformDomain(), now);
                Role owner = roles.save(Role.owner(storeId, now));
                roles.save(Role.defaultStaff(storeId, now));
                roles.flush();
                members.insert(storeId, userId, owner.id(), now);
                audit.recordUserAction(storeId, userId, "store.created", "store", storeId.toString(),
                        Map.of("handle", handle.value(), "name", store.name()));
                return store;
            });
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isUniqueViolation(e, "uq_stores_handle")
                    || ConstraintViolations.isUniqueViolation(e, "uq_store_domains_hostname")) {
                throw new StoreExceptions.HandleUnavailable();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<StoreSummary> storesOf(UUID userId) {
        return members.storesOf(userId);
    }

    public Store get(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId);
            return stores.findById(storeId.value()).orElseThrow(StoreNotFoundException::new);
        });
    }

    public Store rename(UUID userId, StoreId storeId, String newName) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.STORE_MANAGE);
            Store store = stores.findById(storeId.value()).orElseThrow(StoreNotFoundException::new);
            String previous = store.name();
            store.rename(newName.trim(), clock.instant());
            audit.recordUserAction(storeId, userId, "store.renamed", "store", storeId.toString(),
                    Map.of("from", previous, "to", store.name()));
            return store;
        });
    }
}
