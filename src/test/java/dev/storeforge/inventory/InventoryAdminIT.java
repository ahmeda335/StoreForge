package dev.storeforge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryAdminIT extends IntegrationTest {

    CatalogFlows catalog;
    Owner owner;
    UUID variantId;

    @BeforeEach
    void variant() {
        catalog = new CatalogFlows(api);
        owner = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        variantId = catalog.createVariant(owner, "Stocked Item");
    }

    @Test
    void adjustmentsChangeLevelsAndAreRecordedInTheLedger() {
        assertThat(level().get("onHand").asInt()).isZero();

        catalog.adjustStock(owner, variantId, 10, "RECEIVED");
        HttpResponse<String> afterDamage = catalog.adjustStock(owner, variantId, -3, "DAMAGED");

        var level = Json.parse(afterDamage.body());
        assertThat(level.get("onHand").asInt()).isEqualTo(7);
        assertThat(level.get("available").asInt()).isEqualTo(7);
        var ledger = Json.parse(api.get(base() + "/transactions", owner.auth()).body()).get("data");
        assertThat(ledger.valueStream().map(row -> row.get("reason").asString() + ":" + row.get("deltaOnHand").asInt()))
                .containsExactly("DAMAGED:-3", "RECEIVED:10");
    }

    @Test
    void stockCannotGoNegativeAndReasonsAreValidated() {
        catalog.adjustStock(owner, variantId, 2, "RECEIVED");

        HttpResponse<String> tooMuch = catalog.adjustStock(owner, variantId, -3, "CORRECTION");
        HttpResponse<String> badReason = catalog.adjustStock(owner, variantId, 1, "STOLEN_BY_ALIENS");

        assertThat(tooMuch.statusCode()).isEqualTo(409);
        assertThat(code(tooMuch)).isEqualTo("stock_below_reserved");
        assertThat(code(badReason)).isEqualTo("invalid_adjustment");
        assertThat(level().get("onHand").asInt()).isEqualTo(2);
    }

    @Test
    void unknownOrForeignVariantsAreNotFound() {
        Owner other = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID foreignVariant = catalog.createVariant(other, "Other store item");

        HttpResponse<String> foreign = catalog.adjustStock(owner, foreignVariant, 5, "RECEIVED");
        HttpResponse<String> unknown = catalog.adjustStock(owner, UUID.randomUUID(), 5, "RECEIVED");

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(code(foreign)).isEqualTo("variant_not_found");
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    void storefrontShowsAvailability() {
        String handle = Json.parse(api.get("/admin/v1/stores/" + owner.storeId(), owner.auth()).body()).get("handle").asString();
        Map<String, String> host = Map.of("X-Forwarded-Host", handle + ".storeforge.localhost");

        assertThat(storefrontAvailable(host)).as("never stocked").isFalse();
        catalog.adjustStock(owner, variantId, 1, "RECEIVED");
        assertThat(storefrontAvailable(host)).isTrue();
        catalog.adjustStock(owner, variantId, -1, "CORRECTION");
        api.patch(base(), "{\"tracked\":false}", owner.auth());
        assertThat(storefrontAvailable(host)).as("untracked items are always available").isTrue();
    }

    private boolean storefrontAvailable(Map<String, String> host) {
        return Json.parse(api.get("/storefront/v1/products", host).body()).get("data").get(0)
                .get("variants").get(0).get("available").asBoolean();
    }

    private tools.jackson.databind.JsonNode level() {
        return Json.parse(api.get(base(), owner.auth()).body());
    }

    private String base() {
        return "/admin/v1/stores/" + owner.storeId() + "/variants/" + variantId + "/inventory";
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
