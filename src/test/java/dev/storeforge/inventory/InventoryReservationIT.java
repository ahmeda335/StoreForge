package dev.storeforge.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.inventory.internal.application.ReservationSweeper;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The reservation state machine through the module's public API (as checkout will use it). */
class InventoryReservationIT extends IntegrationTest {

    @Autowired
    Inventory inventory;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    ReservationSweeper sweeper;

    CatalogFlows catalog;
    Owner owner;
    StoreId storeId;
    UUID shirt;
    UUID mug;

    @BeforeEach
    void stockedStore() {
        catalog = new CatalogFlows(api);
        owner = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        storeId = StoreId.of(owner.storeId());
        shirt = catalog.createVariant(owner, "Shirt");
        mug = catalog.createVariant(owner, "Mug");
        catalog.adjustStock(owner, shirt, 5, "RECEIVED");
        catalog.adjustStock(owner, mug, 1, "RECEIVED");
    }

    @Test
    void reserveCommitSellsAndReleaseGivesBack() {
        UUID sold = UUID.randomUUID();
        UUID abandoned = UUID.randomUUID();

        reserve(sold, new ReservationLine(shirt, 2));
        reserve(abandoned, new ReservationLine(shirt, 1));
        assertThat(level(shirt)).containsExactly(5, 3);

        tenant.runInStore(storeId, () -> inventory.commit(storeId, sold));
        assertThat(level(shirt)).as("onHand, reserved after commit").containsExactly(3, 1);
        tenant.runInStore(storeId, () -> inventory.release(storeId, abandoned));
        assertThat(level(shirt)).containsExactly(3, 0);
    }

    @Test
    void reservationIsAllOrNothing() {
        UUID checkout = UUID.randomUUID();

        assertThatThrownBy(() -> reserve(checkout, new ReservationLine(shirt, 1), new ReservationLine(mug, 2)))
                .isInstanceOfSatisfying(InsufficientStockException.class, e -> assertThat(e.variantId()).isEqualTo(mug));

        assertThat(level(shirt)).as("the shirt line was rolled back").containsExactly(5, 0);
        assertThat(level(mug)).containsExactly(1, 0);
    }

    @Test
    void reservingAndSettlingAreIdempotent() {
        UUID checkout = UUID.randomUUID();
        reserve(checkout, new ReservationLine(shirt, 2));
        reserve(checkout, new ReservationLine(shirt, 2));   // retried request

        assertThat(level(shirt)).containsExactly(5, 2);
        assertThat((int) tenant.inStore(storeId, () -> inventory.commit(storeId, checkout))).isEqualTo(1);
        assertThat((int) tenant.inStore(storeId, () -> inventory.commit(storeId, checkout))).isZero();
        assertThat((int) tenant.inStore(storeId, () -> inventory.release(storeId, checkout))).as("already committed").isZero();
        assertThat(level(shirt)).containsExactly(3, 0);
    }

    @Test
    void sweeperExpiresAbandonedReservationsExactlyOnceEvenWhenRunConcurrently() throws Exception {
        for (int i = 0; i < 4; i++) {
            UUID checkout = UUID.randomUUID();
            tenant.runInStore(storeId, () -> inventory.reserve(storeId, checkout, List.of(new ReservationLine(shirt, 1)), Duration.ZERO));
        }
        reserve(UUID.randomUUID(), new ReservationLine(shirt, 1));   // still valid (15 min)
        assertThat(level(shirt)).containsExactly(5, 5);

        CountDownLatch start = new CountDownLatch(1);
        int expired;
        try (var executor = Executors.newFixedThreadPool(3)) {
            List<Future<Integer>> runs = List.of(
                    executor.submit(() -> { start.await(); return sweeper.sweepOnce(); }),
                    executor.submit(() -> { start.await(); return sweeper.sweepOnce(); }),
                    executor.submit(() -> { start.await(); return sweeper.sweepOnce(); }));
            start.countDown();
            expired = runs.get(0).get() + runs.get(1).get() + runs.get(2).get();
        }

        assertThat(expired).as("4 expired across all sweepers, none twice").isEqualTo(4);
        assertThat(level(shirt)).containsExactly(5, 1);
        var reasons = Json.parse(api.get("/admin/v1/stores/" + owner.storeId() + "/variants/" + shirt
                + "/inventory/transactions?limit=100", owner.auth()).body()).get("data")
                .valueStream().filter(row -> row.get("reason").asString().equals("EXPIRED")).count();
        assertThat(reasons).isEqualTo(4);
    }

    @Test
    void untrackedItemsAreNeverShort() {
        api.patch("/admin/v1/stores/" + owner.storeId() + "/variants/" + mug + "/inventory", "{\"tracked\":false}", owner.auth());

        reserve(UUID.randomUUID(), new ReservationLine(mug, 50));

        assertThat(level(mug)).as("counters untouched").containsExactly(1, 0);
    }

    private void reserve(UUID checkoutId, ReservationLine... lines) {
        tenant.runInStore(storeId, () -> inventory.reserve(storeId, checkoutId, List.of(lines), Duration.ofMinutes(15)));
    }

    private int[] level(UUID variantId) {
        var level = Json.parse(api.get("/admin/v1/stores/" + owner.storeId() + "/variants/" + variantId + "/inventory",
                owner.auth()).body());
        return new int[] {level.get("onHand").asInt(), level.get("reserved").asInt()};
    }
}
