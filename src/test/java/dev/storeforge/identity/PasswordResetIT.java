package dev.storeforge.identity;

import static dev.storeforge.support.AuthFlows.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.AuthFlows.Session;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasswordResetIT extends IntegrationTest {

    static final String NEW_PASSWORD = "an entirely new passphrase";

    AuthFlows auth;

    @BeforeEach
    void flows() {
        auth = new AuthFlows(api, mailbox);
    }

    @Test
    void resetChangesThePasswordAndRevokesEverySession() {
        String email = uniqueEmail();
        Session oldSession = auth.newVerifiedSession(email);

        assertThat(api.post("/auth/v1/password-resets", Json.object("email", email)).statusCode()).isEqualTo(202);
        String token = mailbox.latestToken(email, "Reset").orElseThrow();
        HttpResponse<String> confirm = api.post("/auth/v1/password-resets/confirmation",
                Json.object("token", token, "newPassword", NEW_PASSWORD));

        assertThat(confirm.statusCode()).isEqualTo(204);
        assertThat(auth.me(oldSession.accessToken()).statusCode()).as("old access token").isEqualTo(401);
        assertThat(auth.refresh(oldSession.refreshToken()).statusCode()).as("old refresh token").isEqualTo(401);
        assertThat(auth.login(email, PASSWORD).statusCode()).as("old password").isEqualTo(401);
        Session newSession = AuthFlows.sessionFrom(auth.login(email, NEW_PASSWORD));
        assertThat(auth.me(newSession.accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void resetTokensAreSingleUse() {
        String email = uniqueEmail();
        auth.registerAndVerify(email, PASSWORD);
        api.post("/auth/v1/password-resets", Json.object("email", email));
        String token = mailbox.latestToken(email, "Reset").orElseThrow();

        api.post("/auth/v1/password-resets/confirmation", Json.object("token", token, "newPassword", NEW_PASSWORD));
        HttpResponse<String> reuse = api.post("/auth/v1/password-resets/confirmation",
                Json.object("token", token, "newPassword", "yet another passphrase"));

        assertThat(reuse.statusCode()).isEqualTo(422);
        assertThat(Json.parse(reuse.body()).get("code").asString()).isEqualTo("invalid_or_expired_token");
    }

    @Test
    void onlyTheNewestResetLinkWorks() {
        String email = uniqueEmail();
        auth.registerAndVerify(email, PASSWORD);
        api.post("/auth/v1/password-resets", Json.object("email", email));
        String first = mailbox.latestToken(email, "Reset").orElseThrow();
        api.post("/auth/v1/password-resets", Json.object("email", email));

        HttpResponse<String> withOldLink = api.post("/auth/v1/password-resets/confirmation",
                Json.object("token", first, "newPassword", NEW_PASSWORD));

        assertThat(withOldLink.statusCode()).isEqualTo(422);
    }

    @Test
    void unknownEmailGetsTheSameResponseAndNoEmail() {
        String unknown = uniqueEmail();

        HttpResponse<String> response = api.post("/auth/v1/password-resets", Json.object("email", unknown));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(mailbox.sentTo(unknown)).isEmpty();
    }
}
