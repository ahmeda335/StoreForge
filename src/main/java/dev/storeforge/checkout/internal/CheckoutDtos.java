package dev.storeforge.checkout.internal;

import dev.storeforge.checkout.internal.CartService.CartView;
import dev.storeforge.checkout.internal.CheckoutService.CheckoutView;
import dev.storeforge.checkout.internal.CheckoutStore.Address;
import dev.storeforge.checkout.internal.CheckoutStore.Line;
import dev.storeforge.shared.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class CheckoutDtos {

    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    private CheckoutDtos() {
    }

    record MoneyDto(String amount, String currency) {
        static MoneyDto of(long minor, String currency) {
            return new MoneyDto(Money.ofMinor(minor, Currency.getInstance(currency)).toDecimalString(), currency);
        }
    }

    record CreatedCartResponse(UUID cartId, String cartToken) {
        @Override
        public String toString() {
            return "CreatedCartResponse[" + cartId + ", token=REDACTED]";
        }
    }

    record AddItemRequest(@NotNull UUID variantId, @NotNull @Min(1) @Max(100) Integer quantity) {
    }

    record SetQuantityRequest(@NotNull @Min(0) @Max(100) Integer quantity) {
    }

    record CartLineResponse(UUID variantId, String productTitle, String variantTitle, int quantity, MoneyDto unitPrice,
                            MoneyDto lineTotal, boolean sellable, boolean inStock) {
    }

    record CartResponse(UUID id, String status, List<CartLineResponse> items, MoneyDto subtotal, int itemCount) {
        static CartResponse of(CartView view) {
            return new CartResponse(view.id(), view.status(), view.lines().stream().map(line -> new CartLineResponse(
                    line.line().variantId(), line.line().productTitle(), line.line().variantTitle(), line.line().quantity(),
                    MoneyDto.of(line.line().unitPriceMinor(), view.currency()), MoneyDto.of(line.line().lineTotalMinor(), view.currency()),
                    line.sellable(), line.inStock())).toList(), MoneyDto.of(view.subtotalMinor(), view.currency()), view.itemCount());
        }
    }

    record AddressDto(@NotBlank @Size(max = 100) String name,
                      @NotBlank @Size(max = 200) String line1,
                      @Nullable @Size(max = 200) String line2,
                      @NotBlank @Size(max = 100) String city,
                      @Nullable @Size(max = 100) String region,
                      @NotBlank @Size(max = 20) String postalCode,
                      @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code like US") String country) {

        Address toAddress() {
            if (!COUNTRIES.contains(country)) {
                throw new CheckoutExceptions.InvalidCountry(country);
            }
            return new Address(name.trim(), line1.trim(), line2, city.trim(), region, postalCode.trim(), country);
        }

        static @Nullable AddressDto of(@Nullable Address address) {
            return address == null ? null : new AddressDto(address.name(), address.line1(), address.line2(), address.city(),
                    address.region(), address.postalCode(), address.country());
        }
    }

    record StartCheckoutRequest(@NotNull UUID cartId, @Nullable @Email @Size(max = 254) String email,
                                @Nullable @Valid AddressDto shippingAddress) {
    }

    record UpdateCheckoutRequest(@Nullable @Email @Size(max = 254) String email, @Nullable @Valid AddressDto shippingAddress) {
    }

    record CompletionRequest(@NotBlank @Pattern(regexp = "\\d{1,12}(\\.\\d{1,4})?", message = "must be a decimal amount like 29.98")
                             String expectedTotal) {
    }

    record CheckoutLineResponse(UUID variantId, String productTitle, String variantTitle, int quantity, MoneyDto unitPrice,
                                MoneyDto lineTotal) {
        static CheckoutLineResponse of(Line line, String currency) {
            return new CheckoutLineResponse(line.variantId(), line.productTitle(), line.variantTitle(), line.quantity(),
                    MoneyDto.of(line.unitPriceMinor(), currency), MoneyDto.of(line.lineTotalMinor(), currency));
        }
    }

    record CheckoutResponse(UUID id, String status, @Nullable String email, @Nullable AddressDto shippingAddress,
                            List<CheckoutLineResponse> lines, List<UUID> unavailableItems, MoneyDto subtotal, MoneyDto shipping,
                            MoneyDto tax, MoneyDto total, @Nullable UUID customerId, @Nullable Instant completedAt,
                            @Nullable Instant paymentDueAt, @Nullable UUID orderId, @Nullable String orderName) {
        static CheckoutResponse of(CheckoutView view) {
            String c = view.currency();
            return new CheckoutResponse(view.id(), view.status(), view.email(), AddressDto.of(view.shippingAddress()),
                    view.lines().stream().map(line -> CheckoutLineResponse.of(line, c)).toList(),
                    view.unavailable().stream().map(Line::variantId).toList(),
                    MoneyDto.of(view.subtotalMinor(), c), MoneyDto.of(view.shippingMinor(), c), MoneyDto.of(view.taxMinor(), c),
                    MoneyDto.of(view.totalMinor(), c), view.customerId(), view.completedAt(), view.reservationExpiresAt(),
                    view.orderId(), view.orderNumber() == null ? null : "#" + view.orderNumber());
        }
    }
}
