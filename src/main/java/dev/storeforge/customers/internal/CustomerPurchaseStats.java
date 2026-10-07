package dev.storeforge.customers.internal;

import dev.storeforge.jobs.Event;
import dev.storeforge.jobs.EventHandler;
import dev.storeforge.jobs.Inbox;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * OrderPaid → customer's order count and total spent. An increment is NOT idempotent: handled twice,
 * it counts twice. The inbox row (consumer, event id) is inserted in the SAME transaction as the
 * increment, so a redelivered event finds it and skips: exactly-once EFFECT on top of at-least-once
 * DELIVERY.
 */
@Component
@RequiredArgsConstructor
class CustomerPurchaseStats implements EventHandler {

    static final String NAME = "customer-purchase-stats";

    private final Inbox inbox;
    private final JdbcClient jdbc;
    private final TenantTransactions tenant;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OrderEvents.PAID);
    }

    @Override
    public void handle(Event event) {
        var customer = event.payload().get("customerId");
        if (customer == null || customer.isNull()) {
            return;
        }
        StoreId storeId = event.storeId();
        tenant.runInStore(storeId, () -> {
            if (!inbox.firstDelivery(storeId, NAME, event.eventId())) {
                return;   // redelivery: the effect is already committed
            }
            jdbc.sql("""
                    UPDATE customers SET orders_count = orders_count + 1, total_spent_minor = total_spent_minor + :total,
                                         updated_at = now()
                     WHERE store_id = :storeId AND id = :id
                    """)
                    .param("total", event.payload().get("totalMinor").asLong())
                    .param("storeId", storeId.value())
                    .param("id", UUID.fromString(customer.asString()))
                    .update();
        });
    }
}
