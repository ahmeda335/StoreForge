package dev.storeforge.payments.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.payments.internal.application.PaymentService;
import dev.storeforge.payments.internal.application.RefundService;
import dev.storeforge.payments.internal.domain.Payment;
import dev.storeforge.payments.internal.domain.Refund;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Merchant view of an order's payment, and refunds. */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/orders/{orderId}")
@RequiredArgsConstructor
class AdminPaymentsController {

    private final PaymentService payments;
    private final RefundService refunds;

    record MoneyDto(String amount, String currency) {
        static MoneyDto of(long minor, String currency) {
            return new MoneyDto(Money.ofMinor(minor, Currency.getInstance(currency)).toDecimalString(), currency);
        }
    }

    record RefundResponse(UUID id, MoneyDto amount, String reason, String status, @Nullable String providerRefundId, Instant createdAt) {
        static RefundResponse of(Refund refund, String currency) {
            return new RefundResponse(refund.id(), MoneyDto.of(refund.amountMinor(), currency), refund.reason(), refund.status().name(),
                    refund.providerRefundId(), refund.createdAt());
        }
    }

    record OperationResponse(String kind, String status, int attempts, @Nullable String errorCode, Instant updatedAt) {
    }

    record PaymentResponse(UUID id, String provider, @Nullable String providerPaymentId, String status, MoneyDto amount,
                           MoneyDto captured, MoneyDto refunded, @Nullable String failureCode, List<RefundResponse> refunds,
                           List<OperationResponse> operations, Instant createdAt) {
    }

    record RefundRequest(
            @NotBlank @Pattern(regexp = "\\d{1,12}(\\.\\d{1,4})?", message = "must be a decimal amount like 12.50") String amount,
            @NotBlank @Pattern(regexp = "customer_request|duplicate|fraud|returned|other",
                    message = "must be one of customer_request, duplicate, fraud, returned, other") String reason) {
    }

    @GetMapping("/payment")
    PaymentResponse payment(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId) {
        var details = payments.details(user.userId(), StoreId.of(storeId), orderId);
        Payment p = details.payment();
        String c = p.currency();
        return new PaymentResponse(p.id(), p.provider(), p.providerPaymentId(), p.status().name(), MoneyDto.of(p.amountMinor(), c),
                MoneyDto.of(p.capturedMinor(), c), MoneyDto.of(p.refundedMinor(), c), p.failureCode(),
                details.refunds().stream().map(r -> RefundResponse.of(r, c)).toList(),
                details.operations().stream().map(o -> new OperationResponse(o.kind(), o.status(), o.attempts(), o.errorCode(),
                        o.updatedAt())).toList(),
                p.createdAt());
    }

    /**
     * 201 for a new refund (status SUCCEEDED, PENDING if the provider hasn't confirmed yet, or
     * FAILED); 200 with {@code Idempotent-Replayed: true} when the same key is sent again.
     */
    @PostMapping("/refunds")
    ResponseEntity<RefundResponse> refund(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId,
                                          @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String key,
                                          @Valid @RequestBody RefundRequest body) {
        var requested = refunds.request(user.userId(), StoreId.of(storeId), orderId, body.amount(), body.reason(), key);
        RefundResponse response = RefundResponse.of(requested.refund(), requested.currency());
        return requested.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(response)
                : ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/orders/" + orderId + "/payment"))
                        .header("Idempotent-Replayed", "false").body(response);
    }
}
