package dev.storeforge.orders.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.inventory.Inventory;
import dev.storeforge.orders.internal.domain.Order;
import dev.storeforge.orders.internal.domain.OrderExceptions;
import dev.storeforge.orders.internal.persistence.OrderQueries;
import dev.storeforge.orders.internal.persistence.OrderQueries.Filter;
import dev.storeforge.orders.internal.persistence.OrderQueries.Row;
import dev.storeforge.orders.internal.persistence.OrderRecords;
import dev.storeforge.orders.internal.persistence.OrderRecords.Event;
import dev.storeforge.orders.internal.persistence.OrderRecords.Item;
import dev.storeforge.orders.internal.persistence.OrderRepository;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Merchant order operations. Each transition: tenant transaction → permission → LOCK the order row
 * → state-machine method → inventory side effect → timeline event + audit entry → commit. The
 * inventory call is in the same transaction, so "cancelled" and "stock released" can't diverge.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OrderRecords records;
    private final OrderQueries queries;
    private final Inventory inventory;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    OrderService(OrderRepository orders, OrderRecords records, OrderQueries queries, Inventory inventory, StoreAccess access,
                 AuditLog audit, TenantTransactions tenant, MeterRegistry meterRegistry, Clock clock) {
        this.orders = orders;
        this.records = records;
        this.queries = queries;
        this.inventory = inventory;
        this.access = access;
        this.audit = audit;
        this.tenant = tenant;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public record OrderDetails(Order order, List<Item> items, List<Event> timeline) {
    }

    public Page<Row> list(UUID userId, StoreId storeId, Filter filter, int limit, @Nullable Cursor after) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.ORDERS_READ);
            return Page.of(queries.page(storeId, filter, limit + 1, after), limit, row -> new Cursor(row.createdAt(), row.id()));
        });
    }

    public OrderDetails get(UUID userId, StoreId storeId, UUID orderId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.ORDERS_READ);
            Order order = orders.findByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
            return details(storeId, order);
        });
    }

    /** "Mark as paid" for payments taken outside the platform (cash, bank transfer). Sells the reserved stock. */
    public OrderDetails markPaid(UUID userId, StoreId storeId, UUID orderId, @Nullable String note) {
        return transition(userId, storeId, orderId, Permission.ORDERS_WRITE, order -> {
            Instant now = clock.instant();
            order.markPaid(now);
            inventory.commit(storeId, order.checkoutId());
            records.event(storeId, order.id(), "PAID", userId, note == null ? Map.of("method", "manual")
                    : Map.of("method", "manual", "note", note), now);
            audit.recordUserAction(storeId, userId, "order.marked_paid", "order", order.id().toString(), Map.of("number", order.number()));
            meterRegistry.counter("storeforge.orders.paid", "method", "manual").increment();
        });
    }

    public OrderDetails fulfill(UUID userId, StoreId storeId, UUID orderId) {
        return transition(userId, storeId, orderId, Permission.ORDERS_WRITE, order -> {
            Instant now = clock.instant();
            order.fulfill(now);
            records.event(storeId, order.id(), "FULFILLED", userId, Map.of(), now);
            audit.recordUserAction(storeId, userId, "order.fulfilled", "order", order.id().toString(), Map.of("number", order.number()));
        });
    }

    public OrderDetails cancel(UUID userId, StoreId storeId, UUID orderId, String reason) {
        return transition(userId, storeId, orderId, Permission.ORDERS_WRITE, order -> {
            Instant now = clock.instant();
            order.cancel(reason, now);
            int released = inventory.release(storeId, order.checkoutId());
            records.event(storeId, order.id(), "CANCELLED", userId, Map.of("reason", reason, "releasedReservations", released), now);
            audit.recordUserAction(storeId, userId, "order.cancelled", "order", order.id().toString(),
                    Map.of("number", order.number(), "reason", reason));
            meterRegistry.counter("storeforge.orders.cancelled", "reason", "merchant").increment();
        });
    }

    /** Used by the expiry job; runs in the job's tenant transaction. */
    boolean expireIfOverdue(StoreId storeId, UUID orderId, Instant now) {
        Order order = orders.lockByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
        if (!order.expireIfOverdue(now)) {
            return false;
        }
        int released = inventory.release(storeId, order.checkoutId());
        records.event(storeId, order.id(), "EXPIRED", null, Map.of("releasedReservations", released), now);
        meterRegistry.counter("storeforge.orders.cancelled", "reason", "payment_timeout").increment();
        return true;
    }

    private OrderDetails transition(UUID userId, StoreId storeId, UUID orderId, Permission permission,
                                    java.util.function.Consumer<Order> change) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, permission);
            Order order = orders.lockByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
            change.accept(order);
            orders.flush();
            return details(storeId, order);
        });
    }

    private OrderDetails details(StoreId storeId, Order order) {
        return new OrderDetails(order, records.items(storeId, order.id()), records.events(storeId, order.id()));
    }
}
