package dev.storeforge.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.jobs.internal.Housekeeping;
import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.CatalogFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import dev.storeforge.support.StorefrontFlows;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** End-to-end effects of async work: durable email, idempotent consumers, housekeeping. */
class ReliableEffectsIT extends IntegrationTest {

    @Autowired
    JobWorker worker;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    Housekeeping housekeeping;

    /** EXP-002 revisited: an SMTP outage no longer loses the email, it delays it. */
    @Test
    void emailsSurviveAnSmtpOutageAndSecretsAreErasedAfterDelivery() {
        String email = uniqueEmail();
        mailbox.failNext(2);

        assertThat(new AuthFlows(api, mailbox).register(email, AuthFlows.PASSWORD).statusCode()).isEqualTo(202);
        assertThat(mailbox.sentTo(email)).as("first attempt failed, retry not due yet").isEmpty();
        assertThat(emailStatus(email)).isEqualTo("PENDING");

        Instant later = Instant.now().plus(Duration.ofHours(1));
        worker.runOnce(later);
        worker.runOnce(later.plus(Duration.ofHours(1)));

        assertThat(mailbox.sentTo(email)).hasSize(1);
        assertThat(emailStatus(email)).isEqualTo("SENT");
        assertThat(jdbc.sql("SELECT body IS NULL FROM email_messages WHERE recipient = :email").param("email", email)
                .query(Boolean.class).single()).as("verification link erased once delivered").isTrue();
    }

    @Test
    void orderConfirmationIsSentOnceEvenIfTheEventIsDeliveredTwice() {
        Owner shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID variant = new CatalogFlows(api).createVariant(shop, "Confirmed lamp");
        new CatalogFlows(api).adjustStock(shop, variant, 1, "RECEIVED");
        String buyer = uniqueEmail();
        var completed = new StorefrontFlows(api, shop).placeOrder(variant, 1, buyer);

        assertThat(mailbox.sentTo(buyer)).extracting(m -> m.subject()).singleElement().asString()
                .startsWith("Order " + completed.get("orderName").asString() + " confirmed");

        redeliver("order-confirmation-emails", completed.get("orderId").asString());

        assertThat(mailbox.sentTo(buyer)).as("dedup key = order id").hasSize(1);
        assertThat(mailbox.sentTo(buyer).getFirst().body()).contains("Confirmed lamp").contains("10.00");
    }

    /** The inbox: a non-idempotent increment applied exactly once despite redelivery. */
    @Test
    void customerStatisticsCountEachPaidOrderOnce() {
        Owner shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID variant = new CatalogFlows(api).createVariant(shop, "Stats item");
        new CatalogFlows(api).adjustStock(shop, variant, 5, "RECEIVED");
        String buyer = uniqueEmail();
        String orderId = new StorefrontFlows(api, shop).placeOrder(variant, 2, buyer).get("orderId").asString();
        api.post("/admin/v1/stores/" + shop.storeId() + "/orders/" + orderId + "/manual-payment", "{}", shop.auth());
        worker.drain();

        redeliver("customer-purchase-stats", orderId);

        var customer = Json.parse(api.get("/admin/v1/stores/" + shop.storeId() + "/customers", shop.auth()).body()).get("data").get(0);
        assertThat(customer.get("ordersCount").asInt()).isEqualTo(1);
        assertThat(customer.get("totalSpentMinor").asLong()).isEqualTo(2000);
    }

    @Test
    void housekeepingRemovesExpiredRowsButNeverUnfinishedWork() {
        Owner shop = new StoreFlows(api, mailbox).newOwnerWithStore(uniqueEmail());
        UUID variant = new CatalogFlows(api).createVariant(shop, "Old order");
        new CatalogFlows(api).adjustStock(shop, variant, 1, "RECEIVED");
        new StorefrontFlows(api, shop).placeOrder(variant, 1, uniqueEmail());
        worker.drain();
        StoreId storeId = StoreId.of(shop.storeId());
        assertThat(idempotencyKeys(storeId)).isPositive();

        var result = housekeeping.run(Instant.now().plus(Duration.ofDays(40)));

        assertThat(result.doneJobs()).isPositive();
        assertThat(result.emails()).isPositive();
        assertThat(idempotencyKeys(storeId)).as("purged across tenants by the definer function").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE status IN ('READY', 'RUNNING') AND completed_at IS NOT NULL")
                .query(Integer.class).single()).isZero();
    }

    /** Puts the consumer's finished job for this order's event back to READY: a redelivery. */
    private void redeliver(String consumer, String orderId) {
        int reset = jdbc.sql("""
                UPDATE jobs SET status = 'READY', run_at = now() - interval '1 second'
                 WHERE type = :type AND event_id IN (SELECT id FROM outbox_events WHERE aggregate_id = :orderId)
                """)
                .param("type", "event:" + consumer).param("orderId", orderId).update();
        assertThat(reset).as("a job to redeliver").isEqualTo(1);
        worker.drain();
    }

    private String emailStatus(String recipient) {
        return jdbc.sql("SELECT status FROM email_messages WHERE recipient = :email").param("email", recipient)
                .query(String.class).single();
    }

    private int idempotencyKeys(StoreId storeId) {
        return tenant.inStore(storeId, () -> jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Integer.class).single());
    }
}
