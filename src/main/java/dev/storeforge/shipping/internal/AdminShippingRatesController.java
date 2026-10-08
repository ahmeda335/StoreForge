package dev.storeforge.shipping.internal;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.money.Money;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Merchant shipping rates (store settings: {@code store:manage}). */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/shipping-rates")
@RequiredArgsConstructor
class AdminShippingRatesController {

    private final ShippingRateService rates;
    private final StoreDirectory stores;

    record CreateRequest(@NotBlank @Size(max = 100) String name, @Nullable List<@Pattern(regexp = "[A-Za-z]{2}") String> countries,
                         @Nullable @Min(0) Integer minWeightGrams, @Nullable @Min(1) Integer maxWeightGrams,
                         @NotBlank @Pattern(regexp = "\\d{1,9}(\\.\\d{1,2})?") String price,
                         @Nullable @Pattern(regexp = "\\d{1,9}(\\.\\d{1,2})?") String freeOver) {
    }

    record ActiveRequest(@NotNull Boolean active) {
    }

    record RateResponse(UUID id, String name, List<String> countries, int minWeightGrams, @Nullable Integer maxWeightGrams, String price,
                        @Nullable String freeOver, boolean active) {
        static RateResponse of(ShippingRateService.StoredRate r, StoreInfo store) {
            return new RateResponse(r.id(), r.name(), r.countries(), r.minWeightGrams(), r.maxWeightGrams(),
                    Money.ofMinor(r.priceMinor(), store.currency()).toDecimalString(),
                    r.freeOverMinor() == null ? null : Money.ofMinor(r.freeOverMinor(), store.currency()).toDecimalString(), r.active());
        }
    }

    @PostMapping
    ResponseEntity<RateResponse> create(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                        @Valid @RequestBody CreateRequest body) {
        StoreInfo store = store(storeId);
        var created = rates.create(user.userId(), StoreId.of(storeId), new ShippingRateService.NewRate(body.name(),
                body.countries() == null ? List.of() : body.countries(), body.minWeightGrams() == null ? 0 : body.minWeightGrams(),
                body.maxWeightGrams(), Money.parse(body.price(), store.currency()).amountMinor(),
                body.freeOver() == null ? null : Money.parse(body.freeOver(), store.currency()).amountMinor()));
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/shipping-rates/" + created.id()))
                .body(RateResponse.of(created, store));
    }

    @GetMapping
    Map<String, List<RateResponse>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        StoreInfo store = store(storeId);
        return Map.of("data", rates.list(user.userId(), StoreId.of(storeId)).stream().map(r -> RateResponse.of(r, store)).toList());
    }

    @PatchMapping("/{rateId}")
    RateResponse setActive(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID rateId,
                           @Valid @RequestBody ActiveRequest body) {
        return RateResponse.of(rates.setActive(user.userId(), StoreId.of(storeId), rateId, body.active()), store(storeId));
    }

    private StoreInfo store(UUID storeId) {
        return stores.find(StoreId.of(storeId)).orElseThrow(ShippingRateService.RateNotFound::new);
    }
}
