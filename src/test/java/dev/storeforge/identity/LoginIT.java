package dev.storeforge.identity;

import static dev.storeforge.support.AuthFlows.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginIT extends IntegrationTest {

    AuthFlows auth;

    @BeforeEach
    void flows() {
        auth = new AuthFlows(api, mailbox);
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() {
        String email = uniqueEmail();
        auth.registerAndVerify(email, PASSWORD);

        HttpResponse<String> wrongPassword = auth.login(email, "wrong password 123");
        HttpResponse<String> unknownEmail = auth.login(uniqueEmail(), "wrong password 123");

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownEmail.statusCode()).isEqualTo(401);
        assertThat(code(wrongPassword)).isEqualTo("invalid_credentials").isEqualTo(code(unknownEmail));
    }

    @Test
    void accountIsTemporarilyLockedAfterRepeatedFailures() {
        String email = uniqueEmail();
        auth.registerAndVerify(email, PASSWORD);

        for (int i = 0; i < 5; i++) {
            assertThat(auth.login(email, "wrong password 123").statusCode()).isEqualTo(401);
        }
        HttpResponse<String> correctPasswordWhileLocked = auth.login(email, PASSWORD);

        // Same answer as a wrong password: the response must not reveal that the account exists and is locked.
        assertThat(correctPasswordWhileLocked.statusCode()).isEqualTo(401);
        assertThat(code(correctPasswordWhileLocked)).isEqualTo("invalid_credentials");
    }

    @Test
    void attemptsPerTargetEmailAreRateLimited() {
        String email = uniqueEmail();
        for (int i = 0; i < 10; i++) {
            auth.login(email, "wrong password 123");
        }

        HttpResponse<String> limited = auth.login(email, "wrong password 123");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("rate_limited");
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                assertThat(Integer.parseInt(seconds)).isPositive());
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
