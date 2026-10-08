package dev.storeforge.webhooks.internal.delivery;

import dev.storeforge.developer.Scope;
import dev.storeforge.inventory.InventoryEvents;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.shared.money.Money;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Public topics and their payloads (API version 2026-10). Internal event names and payloads stay
 * free to change; this mapping is the contract merchants code against. Payloads are thin: ids and
 * the values a receiver routes on. Receivers fetch the full resource from the API.
 */
public final class Topics {

    public static final Map<String, String> BY_EVENT_TYPE = Map.of(
            OrderEvents.PLACED, "orders/create",
            OrderEvents.PAID, "orders/paid",
            OrderEvents.CANCELLED, "orders/cancelled",
            OrderEvents.FULFILLED, "orders/fulfilled",
            OrderEvents.SHIPPED, "fulfillments/create",
            OrderEvents.REFUNDED, "refunds/create",
            InventoryEvents.LEVEL_CHANGED, "inventory_levels/update");

    public static final Set<String> ALL = Set.copyOf(BY_EVENT_TYPE.values());

    private Topics() {
    }

    /** An app may subscribe to a topic only if it could read the resource through the API. */
    public static Scope requiredScope(String topic) {
        return topic.startsWith("inventory_levels/") ? Scope.READ_INVENTORY : Scope.READ_ORDERS;
    }

    public static Map<String, Object> data(String eventType, JsonNode p) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (eventType.equals(InventoryEvents.LEVEL_CHANGED)) {
            data.put("variantId", p.get("variantId").asString());
            data.put("inventoryItemId", p.get("inventoryItemId").asString());
            data.put("onHand", p.get("onHand").asInt());
            data.put("available", p.get("available").asInt());
            data.put("delta", p.get("delta").asInt());
            data.put("reason", p.get("reason").asString());
            return data;
        }
        data.put("orderId", p.get("orderId").asString());
        data.put("orderNumber", p.get("number").asLong());
        data.put("name", "#" + p.get("number").asLong());
        String currency = p.has("currency") ? p.get("currency").asString() : null;
        switch (eventType) {
            case OrderEvents.PLACED, OrderEvents.PAID -> {
                data.put("total", money(p.get("totalMinor").asLong(), currency));
                data.put("customerId", text(p, "customerId"));
                if (eventType.equals(OrderEvents.PAID)) {
                    data.put("paymentMethod", p.get("method").asString());
                }
            }
            case OrderEvents.CANCELLED -> data.put("reason", p.get("reason").asString());
            case OrderEvents.SHIPPED -> {
                data.put("shipmentId", p.get("shipmentId").asString());
                data.put("carrier", text(p, "carrier"));
                data.put("trackingNumber", text(p, "trackingNumber"));
                data.put("complete", p.get("complete").asBoolean());
            }
            case OrderEvents.REFUNDED -> {
                data.put("refundId", p.get("refundId").asString());
                data.put("amount", money(p.get("amountMinor").asLong(), currency));
                data.put("refundedTotal", money(p.get("refundedTotalMinor").asLong(), currency));
            }
            default -> {
            }
        }
        return data;
    }

    private static Map<String, String> money(long minor, @Nullable String currency) {
        return Map.of("amount", Money.ofMinor(minor, Currency.getInstance(currency)).toDecimalString(), "currency", currency);
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
