package dev.storeforge.orders.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.orders.internal.application.OrderService;
import dev.storeforge.orders.internal.application.OrderService.OrderDetails;
import dev.storeforge.orders.internal.domain.Order;
import dev.storeforge.orders.internal.persistence.OrderQueries.Filter;
import dev.storeforge.orders.internal.persistence.OrderQueries.Row;
import dev.storeforge.shared.error.InvalidParameterException;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant orders: list, detail with timeline, and state transitions as sub-resources (blueprint 08). */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/orders")
@RequiredArgsConstructor
class AdminOrdersController {

    private final OrderService orders;

    record MoneyDto(String amount, String currency) {
        static MoneyDto of(long minor, String currency) {
            return new MoneyDto(Money.ofMinor(minor, Currency.getInstance(currency)).toDecimalString(), currency);
        }
    }

    record OrderSummary(UUID id, String name, long number, String email, MoneyDto total, String status, String financialStatus,
                        String fulfillmentStatus, int itemCount, Instant createdAt) {
        static OrderSummary of(Row row) {
            return new OrderSummary(row.id(), "#" + row.number(), row.number(), row.email(), MoneyDto.of(row.totalMinor(), row.currency()),
                    row.status(), row.financialStatus(), row.fulfillmentStatus(), row.itemCount(), row.createdAt());
        }
    }

    record Address(String name, String line1, @Nullable String line2, String city, @Nullable String region, String postalCode,
                   String country) {
    }

    record Item(UUID variantId, String productTitle, String variantTitle, @Nullable String sku, int quantity, MoneyDto unitPrice,
                MoneyDto lineTotal) {
    }

    record TimelineEntry(String type, @Nullable UUID actorId, Map<String, Object> details, Instant at) {
    }

    record OrderResponse(UUID id, String name, long number, String email, @Nullable UUID customerId, String status,
                         String financialStatus, String fulfillmentStatus, MoneyDto subtotal, MoneyDto shipping, MoneyDto tax,
                         MoneyDto total, Address shippingAddress, List<Item> items, List<TimelineEntry> timeline,
                         Instant paymentDueAt, @Nullable Instant paidAt, @Nullable Instant fulfilledAt,
                         @Nullable Instant cancelledAt, @Nullable String cancelReason, Instant createdAt) {
        static OrderResponse of(OrderDetails details) {
            Order o = details.order();
            String c = o.currency();
            return new OrderResponse(o.id(), "#" + o.number(), o.number(), o.email(), o.customerId(), o.status().name(),
                    o.financialStatus().name(), o.fulfillmentStatus().name(), MoneyDto.of(o.subtotalMinor(), c),
                    MoneyDto.of(o.shippingMinor(), c), MoneyDto.of(o.taxMinor(), c), MoneyDto.of(o.totalMinor(), c),
                    new Address(o.shipName(), o.shipLine1(), o.shipLine2(), o.shipCity(), o.shipRegion(), o.shipPostalCode(),
                            o.shipCountry()),
                    details.items().stream().map(i -> new Item(i.variantId(), i.productTitle(), i.variantTitle(), i.sku(),
                            i.quantity(), MoneyDto.of(i.unitPriceMinor(), c), MoneyDto.of(i.lineTotalMinor(), c))).toList(),
                    details.timeline().stream().map(e -> new TimelineEntry(e.type(), e.actorId(), e.details(), e.createdAt())).toList(),
                    o.paymentDueAt(), o.paidAt(), o.fulfilledAt(), o.cancelledAt(), o.cancelReason(), o.createdAt());
        }
    }

    record CancellationRequest(@NotBlank @Pattern(regexp = "customer_request|fraud|inventory|other",
            message = "must be one of customer_request, fraud, inventory, other") String reason) {
    }

    record ManualPaymentRequest(@Nullable @Size(max = 500) String note) {
    }

    @GetMapping
    Page<OrderSummary> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                            @RequestParam(required = false) @Nullable String status,
                            @RequestParam(required = false) @Nullable String financialStatus,
                            @RequestParam(required = false) @Nullable String fulfillmentStatus,
                            @RequestParam(required = false) @Nullable String q,
                            @RequestParam(required = false) @Nullable Integer limit,
                            @RequestParam(required = false) @Nullable String cursor) {
        Long number = null;
        String emailPrefix = null;
        if (q != null && !q.isBlank()) {
            String query = q.trim().replaceFirst("^#", "");
            if (query.matches("\\d{1,18}")) {
                number = Long.parseLong(query);
            } else {
                emailPrefix = query.toLowerCase(Locale.ROOT);
            }
        }
        Filter filter = new Filter(allowed("status", status, Order.Status.values()),
                allowed("financialStatus", financialStatus, Order.FinancialStatus.values()),
                allowed("fulfillmentStatus", fulfillmentStatus, Order.FulfillmentStatus.values()), number, emailPrefix);
        var page = orders.list(user.userId(), StoreId.of(storeId), filter, Page.limit(limit), cursor == null ? null : Cursor.decode(cursor));
        return new Page<>(page.data().stream().map(OrderSummary::of).toList(), page.page());
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId) {
        return OrderResponse.of(orders.get(user.userId(), StoreId.of(storeId), orderId));
    }

    @PostMapping("/{orderId}/cancellation")
    OrderResponse cancel(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId,
                         @Valid @RequestBody CancellationRequest body) {
        return OrderResponse.of(orders.cancel(user.userId(), StoreId.of(storeId), orderId, body.reason()));
    }

    @PostMapping("/{orderId}/manual-payment")
    OrderResponse markPaid(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId,
                           @Valid @RequestBody ManualPaymentRequest body) {
        return OrderResponse.of(orders.markPaid(user.userId(), StoreId.of(storeId), orderId, body.note()));
    }

    @PostMapping("/{orderId}/fulfillment")
    ResponseEntity<OrderResponse> fulfill(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                          @PathVariable UUID orderId) {
        return ResponseEntity.ok(OrderResponse.of(orders.fulfill(user.userId(), StoreId.of(storeId), orderId)));
    }

    private static <E extends Enum<E>> @Nullable String allowed(String parameter, @Nullable String value, E[] values) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values).map(Enum::name).filter(name -> name.equalsIgnoreCase(value.trim())).findFirst()
                .orElseThrow(() -> new InvalidParameterException(parameter, Arrays.toString(values)));
    }
}
