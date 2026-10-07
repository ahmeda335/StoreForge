package dev.storeforge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXP-004 as executable documentation: the SAME "stock = 1, 50 buyers" race against three
 * implementations. Only the conditional UPDATE is used in production (ADR-004); the other two
 * exist here to show WHY.
 *
 * <p>The naive version sleeps 20 ms between its read and its write (pg_sleep), standing in for
 * the work real code does there (validation, a remote call...). That makes the race window
 * deterministic instead of lucky.
 */
@Slf4j
class OversellExperimentIT extends IntegrationTest {

    static final int BUYERS = 50;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcClient jdbc;

    StoreId storeId;
    UUID itemId;

    @BeforeEach
    void oneItemInStock() {
        CatalogFlows catalog = new CatalogFlows(api);
        Owner owner = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        storeId = StoreId.of(owner.storeId());
        UUID variant = catalog.createVariant(owner, "Contested");
        catalog.adjustStock(owner, variant, 1, "RECEIVED");
        itemId = tenant.inStore(storeId, () -> jdbc.sql("SELECT id FROM inventory_items WHERE variant_id = :v")
                .param("v", variant).query(UUID.class).single());
    }

    /** Read-modify-write: every buyer reads reserved = 0, decides "1 available", writes reserved = 1. */
    @Test
    void naiveReadModifyWriteOversells() throws Exception {
        int successes = race(() -> tenant.inStore(storeId, () -> {
            int reserved = jdbc.sql("SELECT reserved FROM inventory_items WHERE id = :id").param("id", itemId).query(Integer.class).single();
            int onHand = jdbc.sql("SELECT on_hand FROM inventory_items WHERE id = :id").param("id", itemId).query(Integer.class).single();
            jdbc.sql("SELECT pg_sleep(0.02)").query(Object.class).list();
            if (onHand - reserved < 1) {
                return false;
            }
            jdbc.sql("UPDATE inventory_items SET reserved = :value WHERE id = :id")
                    .param("value", reserved + 1).param("id", itemId).update();
            return true;
        }));

        log.info("EXP-004 naive: {} of {} buyers succeeded for 1 unit; reserved counter = {}", successes, BUYERS, reserved());
        assertThat(successes).as("buyers told 'you got it' for ONE item (lost updates)").isGreaterThan(1);
        assertThat(reserved()).as("...while the counter says only 1 is reserved").isEqualTo(1);
    }

    /** Production approach (ADR-004): one conditional statement. */
    @Test
    void conditionalUpdateSellsExactlyOnce() throws Exception {
        int successes = race(() -> tenant.inStore(storeId, () -> jdbc.sql("""
                UPDATE inventory_items SET reserved = reserved + 1 WHERE id = :id AND on_hand - reserved >= 1
                """).param("id", itemId).update() == 1));

        log.info("EXP-004 conditional update: {} of {} buyers succeeded", successes, BUYERS);
        assertThat(successes).isEqualTo(1);
        assertThat(reserved()).isEqualTo(1);
    }

    /** The naive code under SERIALIZABLE: correct, but losers fail with 40001 and would need retries. */
    @Test
    void serializableMakesTheNaiveCodeCorrectAtTheCostOfAborts() throws Exception {
        TransactionTemplate serializable = new TransactionTemplate(transactionTemplate.getTransactionManager());
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        int successes = race(() -> {
            try {
                return serializable.execute(status -> tenant.inStore(storeId, () -> {
                    int reserved = jdbc.sql("SELECT reserved FROM inventory_items WHERE id = :id").param("id", itemId).query(Integer.class).single();
                    jdbc.sql("SELECT pg_sleep(0.02)").query(Object.class).list();
                    if (1 - reserved < 1) {
                        return false;
                    }
                    jdbc.sql("UPDATE inventory_items SET reserved = :value WHERE id = :id")
                            .param("value", reserved + 1).param("id", itemId).update();
                    return true;
                }));
            } catch (RuntimeException e) {
                failures.add(e.getClass().getSimpleName());
                return false;
            }
        });

        assertThat(successes).isEqualTo(1);
        assertThat(reserved()).isEqualTo(1);
        log.info("EXP-004 serializable: {} succeeded, {} aborted ({})", successes, failures.size(),
                failures.stream().distinct().toList());
        assertThat(failures).as("serialization failures (SQLSTATE 40001) that a real system must retry").isNotEmpty();
    }

    private int race(java.util.concurrent.Callable<Boolean> attempt) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(BUYERS)) {
            for (int i = 0; i < BUYERS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return attempt.call();
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                successes += Boolean.TRUE.equals(result.get()) ? 1 : 0;
            }
            return successes;
        }
    }

    private int reserved() {
        return tenant.inStore(storeId, () -> jdbc.sql("SELECT reserved FROM inventory_items WHERE id = :id")
                .param("id", itemId).query(Integer.class).single());
    }
}
