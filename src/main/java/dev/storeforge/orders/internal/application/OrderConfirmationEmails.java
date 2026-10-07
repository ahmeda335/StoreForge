package dev.storeforge.orders.internal.application;

import dev.storeforge.jobs.Event;
import dev.storeforge.jobs.EventHandler;
import dev.storeforge.jobs.PermanentJobFailure;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.Notifications;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.orders.internal.domain.Order;
import dev.storeforge.orders.internal.persistence.OrderRecords;
import dev.storeforge.orders.internal.persistence.OrderRepository;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * OrderPlaced → order confirmation email. Idempotent by construction: the email's dedup key is the
 * order id, so a redelivered event (at-least-once) finds the email already queued and does nothing.
 * Reads the order inside a tenant transaction (RLS), since the event carries only ids.
 */
@Component
@RequiredArgsConstructor
class OrderConfirmationEmails implements EventHandler {

    private final OrderRepository orders;
    private final OrderRecords records;
    private final Notifications notifications;
    private final StoreDirectory stores;
    private final TenantTransactions tenant;

    @Override
    public String name() {
        return "order-confirmation-emails";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OrderEvents.PLACED);
    }

    @Override
    public void handle(Event event) {
        StoreId storeId = event.storeId();
        UUID orderId = UUID.fromString(event.payload().get("orderId").asString());
        tenant.runInStore(storeId, () -> {
            Order order = orders.findByStoreIdAndId(storeId.value(), orderId)
                    .orElseThrow(() -> new PermanentJobFailure("Order " + orderId + " not found"));
            String storeName = stores.find(storeId).map(StoreInfo::name).orElse("our store");
            Currency currency = Currency.getInstance(order.currency());
            String lines = records.items(storeId, orderId).stream()
                    .map(item -> "  " + item.quantity() + " × " + item.productTitle()
                            + ("Default".equals(item.variantTitle()) ? "" : " (" + item.variantTitle() + ")")
                            + "  " + Money.ofMinor(item.lineTotalMinor(), currency))
                    .collect(Collectors.joining("\n"));
            notifications.enqueueEmail(storeId, new EmailMessage(order.email(),
                    "Order #" + order.number() + " confirmed: " + storeName,
                    "Thank you for your order at " + storeName + "!\n\n" + lines + "\n\nTotal: "
                            + Money.ofMinor(order.totalMinor(), currency) + "\n\nWe'll let you know when it ships."),
                    "order-confirmation:" + orderId, false);
        });
    }
}
