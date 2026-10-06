package dev.storeforge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Invariants under contention, against the real database and connection pool. Assertions are on
 * INVARIANTS (exactly N sales, never negative, ledger == levels), not on "no exception".
 */
class InventoryConcurrencyIT extends IntegrationTest {

    @Autowired
    Inventory inventory;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    JdbcClient jdbc;

    CatalogFlows catalog;
    Owner owner;
    StoreId storeId;

    @BeforeEach
    void store() {
        catalog = new CatalogFlows(api);
        owner = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        storeId = StoreId.of(owner.storeId());
    }

    /** Stock = 1, 100 buyers at the same instant: exactly one wins, 99 get insufficient_stock, stock never negative. */
    @RepeatedTest(3)
    void lastItemIsSoldExactlyOnce() throws Exception {
        UUID variant = catalog.createVariant(owner, "Last one " + UUID.randomUUID());
        catalog.adjustStock(owner, variant, 1, "RECEIVED");

        int buyers = 100;
        int[] outcome = race(buyers, i -> List.of(new ReservationLine(variant, 1)));

        assertThat(outcome[0]).as("successful reservations").isEqualTo(1);
        assertThat(outcome[1]).as("insufficient stock").isEqualTo(buyers - 1);
        assertThat(outcome[2]).as("unexpected errors").isZero();
        assertThat(level(variant)).containsExactly(1, 1);
        assertLedgerMatchesLevels(variant);
    }

    /** 50 units, 80 buyers of 1: exactly 50 reservations. */
    @Test
    void limitedStockIsNeverExceeded() throws Exception {
        UUID variant = catalog.createVariant(owner, "Flash sale");
        catalog.adjustStock(owner, variant, 50, "RECEIVED");

        int[] outcome = race(80, i -> List.of(new ReservationLine(variant, 1)));

        assertThat(outcome[0]).isEqualTo(50);
        assertThat(outcome[1]).isEqualTo(30);
        assertThat(outcome[2]).isZero();
        assertThat(level(variant)).containsExactly(50, 50);
        assertLedgerMatchesLevels(variant);
    }

    /**
     * Half the checkouts list items as {X, Y}, half as {Y, X}. Locking in request order would
     * deadlock (40P01). Sorting lines by variant id gives every transaction the same lock order.
     */
    @Test
    void multiItemCheckoutsInOppositeOrderDoNotDeadlock() throws Exception {
        UUID x = catalog.createVariant(owner, "X");
        UUID y = catalog.createVariant(owner, "Y");
        catalog.adjustStock(owner, x, 1000, "RECEIVED");
        catalog.adjustStock(owner, y, 1000, "RECEIVED");

        int[] outcome = race(60, i -> i % 2 == 0
                ? List.of(new ReservationLine(x, 1), new ReservationLine(y, 1))
                : List.of(new ReservationLine(y, 1), new ReservationLine(x, 1)));

        assertThat(outcome[2]).as("deadlocks or other errors").isZero();
        assertThat(outcome[0]).isEqualTo(60);
        assertThat(level(x)).containsExactly(1000, 60);
        assertThat(level(y)).containsExactly(1000, 60);
    }

    /** @return {successes, insufficient stock, other errors} */
    private int[] race(int buyers, java.util.function.IntFunction<List<ReservationLine>> linesFor) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(buyers)) {
            for (int i = 0; i < buyers; i++) {
                List<ReservationLine> lines = linesFor.apply(i);
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        tenant.runInStore(storeId, () -> inventory.reserve(storeId, UUID.randomUUID(), lines, Duration.ofMinutes(15)));
                        return "ok";
                    } catch (InsufficientStockException e) {
                        return "insufficient";
                    } catch (RuntimeException e) {
                        return "error:" + e.getClass().getSimpleName() + ":" + e.getMessage();
                    }
                }));
            }
            start.countDown();
            int[] outcome = new int[3];
            for (Future<String> result : results) {
                String value = result.get();
                outcome[value.equals("ok") ? 0 : value.equals("insufficient") ? 1 : 2]++;
                if (value.startsWith("error")) {
                    System.err.println(value);
                }
            }
            return outcome;
        }
    }

    private int[] level(UUID variantId) {
        var level = Json.parse(api.get("/admin/v1/stores/" + owner.storeId() + "/variants/" + variantId + "/inventory",
                owner.auth()).body());
        return new int[] {level.get("onHand").asInt(), level.get("reserved").asInt()};
    }

    /** Reconciliation: the append-only ledger must explain the current level exactly. */
    private void assertLedgerMatchesLevels(UUID variantId) {
        int[] sums = tenant.inStore(storeId, () -> jdbc.sql("""
                SELECT coalesce(sum(t.delta_on_hand), 0) AS on_hand, coalesce(sum(t.delta_reserved), 0) AS reserved
                  FROM inventory_transactions t JOIN inventory_items i ON i.id = t.inventory_item_id
                 WHERE i.variant_id = :variantId
                """).param("variantId", variantId)
                .query((rs, rowNum) -> new int[] {rs.getInt("on_hand"), rs.getInt("reserved")}).single());
        assertThat(sums).as("ledger sums == levels").containsExactly(level(variantId));
    }
}
