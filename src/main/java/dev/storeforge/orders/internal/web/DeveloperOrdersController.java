package dev.storeforge.orders.internal.web;

import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.developer.DeveloperApi;
import dev.storeforge.developer.RequiresScope;
import dev.storeforge.developer.Scope;
import dev.storeforge.orders.internal.application.OrderService;
import dev.storeforge.orders.internal.domain.Order;
import dev.storeforge.orders.internal.persistence.OrderQueries.Row;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer API (2026-10): orders. The list is a SYNC feed: {@code updatedAfter} + cursor, oldest
 * change first, so an integration can catch up after downtime (webhooks are hints; this is the truth).
 */
@RestController
@RequestMapping(DeveloperApi.STORE_PATH + "/orders")
@RequiredArgsConstructor
class DeveloperOrdersController {

    private final OrderService orders;

    record Price(String amount, String currency) {
        static Price of(long minor, String currency) {
            return new Price(Money.ofMinor(minor, Currency.getInstance(currency)).toDecimalString(), currency);
        }
    }

    record OrderSummary(UUID id, String name, long number, String email, Price total, String status, String financialStatus,
                        String fulfillmentStatus, int itemCount, Instant createdAt, Instant updatedAt) {
        static OrderSummary of(Row r) {
            return new OrderSummary(r.id(), "#" + r.number(), r.number(), r.email(), Price.of(r.totalMinor(), r.currency()), r.status(),
                    r.financialStatus(), r.fulfillmentStatus(), r.itemCount(), r.createdAt(), r.updatedAt());
        }
    }

    record LineItem(UUID variantId, String productTitle, String variantTitle, @Nullable String sku, int quantity, Price unitPrice) {
    }

    record OrderDetail(UUID id, String name, long number, String email, String status, String financialStatus, String fulfillmentStatus,
                       Price subtotal, Price discount, @Nullable String discountCode, Price shipping, Price tax, Price total,
                       Price refunded, List<LineItem> lineItems,
                       Instant createdAt, Instant updatedAt) {
    }

    @GetMapping
    @RequiresScope(Scope.READ_ORDERS)
    Page<OrderSummary> changedSince(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion,
                                    @PathVariable UUID storeId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant updatedAfter,
                                    @RequestParam(required = false) @Nullable Integer limit,
                                    @RequestParam(required = false) @Nullable String cursor) {
        var page = orders.changedSince(client.actor(), client.storeId(), updatedAfter == null ? Instant.EPOCH : updatedAfter,
                Page.limit(limit), cursor == null ? null : Cursor.decode(cursor));
        return new Page<>(page.data().stream().map(OrderSummary::of).toList(), page.page());
    }

    @GetMapping("/{orderId}")
    @RequiresScope(Scope.READ_ORDERS)
    OrderDetail get(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion, @PathVariable UUID storeId,
                    @PathVariable UUID orderId) {
        var details = orders.get(client.actor(), client.storeId(), orderId);
        Order o = details.order();
        String c = o.currency();
        return new OrderDetail(o.id(), "#" + o.number(), o.number(), o.email(), o.status().name(), o.financialStatus().name(),
                o.fulfillmentStatus().name(), Price.of(o.subtotalMinor(), c), Price.of(o.discountMinor(), c), o.discountCode(),
                Price.of(o.shippingMinor(), c), Price.of(o.taxMinor(), c),
                Price.of(o.totalMinor(), c), Price.of(o.refundedMinor(), c),
                details.items().stream().map(i -> new LineItem(i.variantId(), i.productTitle(), i.variantTitle(), i.sku(), i.quantity(),
                        Price.of(i.unitPriceMinor(), c))).toList(),
                o.createdAt(), o.updatedAt());
    }
}
