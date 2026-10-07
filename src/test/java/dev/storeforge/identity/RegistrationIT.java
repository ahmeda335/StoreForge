package dev.storeforge.identity;

import static dev.storeforge.support.AuthFlows.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class RegistrationIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    AuthFlows auth;

    @BeforeEach
    void flows() {
        auth = new AuthFlows(api, mailbox);
    }

    @Test
    void registrationRequiresEmailVerificationBeforeLogin() {
        String email = uniqueEmail();

        assertThat(auth.register(email, PASSWORD).statusCode()).isEqualTo(202);
        HttpResponse<String> beforeVerification = auth.login(email, PASSWORD);
        assertThat(beforeVerification.statusCode()).isEqualTo(403);
        assertThat(Json.parse(beforeVerification.body()).get("code").asString()).isEqualTo("email_not_verified");

        String token = mailbox.latestToken(email, "Verify").orElseThrow();
        assertThat(api.post("/auth/v1/email-verifications/confirmation", Json.object("token", token)).statusCode()).isEqualTo(204);

        HttpResponse<String> login = auth.login(email, PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(login.headers().firstValue("Set-Cookie")).hasValueSatisfying(cookie -> assertThat(cookie)
                .contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/auth/v1/token"));
        var body = Json.parse(login.body());
        assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asLong()).isEqualTo(900);
    }

    @Test
    void verificationLinksAreSingleUse() {
        String email = uniqueEmail();
        auth.register(email, PASSWORD);
        String token = mailbox.latestToken(email, "Verify").orElseThrow();

        api.post("/auth/v1/email-verifications/confirmation", Json.object("token", token));
        HttpResponse<String> second = api.post("/auth/v1/email-verifications/confirmation", Json.object("token", token));

        assertThat(second.statusCode()).isEqualTo(422);
        assertThat(Json.parse(second.body()).get("code").asString()).isEqualTo("invalid_or_expired_token");
    }

    @Test
    void registeringAnExistingEmailLooksIdenticalToTheCallerButNotifiesTheOwner() {
        String email = uniqueEmail();
        HttpResponse<String> first = auth.register(email, PASSWORD);
        HttpResponse<String> second = auth.register(email, "a different password 123");

        assertThat(second.statusCode()).isEqualTo(first.statusCode());
        assertThat(second.body()).isEqualTo(first.body());
        // Emails go through the job queue, which does not promise ordering (retries, several workers).
        assertThat(mailbox.sentTo(email)).extracting(message -> message.subject())
                .containsExactlyInAnyOrder("Verify your StoreForge email address", "You already have a StoreForge account");
    }

    @Test
    void emailIsNormalizedSoCaseVariantsAreTheSameAccount() {
        String email = uniqueEmail();
        auth.registerAndVerify(email.toUpperCase(), PASSWORD);

        assertThat(auth.login("  " + email + " ", PASSWORD).statusCode()).isEqualTo(200);
        String stored = jdbc.sql("SELECT email FROM users WHERE email = :email").param("email", email).query(String.class).single();
        assertThat(stored).isEqualTo(email);
    }

    @Test
    void concurrentRegistrationsOfTheSameEmailCreateExactlyOneUser() throws Exception {
        String email = uniqueEmail();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return auth.register(email, PASSWORD).statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(202);
            }
        }
        Integer users = jdbc.sql("SELECT count(*) FROM users WHERE email = :email").param("email", email).query(Integer.class).single();
        assertThat(users).isEqualTo(1);
    }

    @Test
    void invalidInputIsRejectedWithFieldErrors() {
        HttpResponse<String> response = auth.register("not-an-email", "short");

        assertThat(response.statusCode()).isEqualTo(422);
        var body = Json.parse(response.body());
        assertThat(body.get("code").asString()).isEqualTo("validation_failed");
        assertThat(body.get("errors").valueStream().map(error -> error.get("field").asString()))
                .containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void unknownJsonPropertiesAreRejected() {
        HttpResponse<String> response = api.post("/auth/v1/registrations",
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.parse(response.body()).get("code").asString()).isEqualTo("bad_request");
    }
}
