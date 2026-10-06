package dev.storeforge.orders.internal.application;

import dev.storeforge.orders.internal.persistence.OrderRecords;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels orders whose payment window passed and gives their stock back. Same shape as the
 * reservation sweeper (ADR-011): store ids via a definer function, then per store a tenant
 * transaction that claims overdue orders with SKIP LOCKED and runs the normal state transition.
 * A concurrent "mark as paid" on the same order waits for or skips this claim: never both.
 */
@Component
public class UnpaidOrderExpiry {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderExpiry.class);

    private final OrderRecords records;
    private final OrderService orderService;
    private final TenantTransactions tenant;
    private final Clock clock;

    UnpaidOrderExpiry(OrderRecords records, OrderService orderService, TenantTransactions tenant, Clock clock) {
        this.records = records;
        this.orderService = orderService;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${storeforge.orders.expiry-interval:30s}", initialDelayString = "${storeforge.orders.expiry-interval:30s}")
    public void run() {
        int expired = expireOverdue(clock.instant());
        if (expired > 0) {
            log.atInfo().addKeyValue("expiredOrders", expired).log("Cancelled unpaid orders past their payment window");
        }
    }

    /** @param now the cutoff (a parameter so tests can move time forward instead of sleeping) */
    public int expireOverdue(Instant now) {
        int total = 0;
        for (UUID storeId : records.storesWithOverdueOrders(now, 100)) {
            StoreId id = StoreId.of(storeId);
            total += tenant.inStore(id, () -> {
                int expired = 0;
                for (UUID orderId : records.claimOverdue(id, now, 200)) {
                    expired += orderService.expireIfOverdue(id, orderId, now) ? 1 : 0;
                }
                return expired;
            });
        }
        return total;
    }
}
