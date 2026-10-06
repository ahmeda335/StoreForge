package dev.storeforge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.AuthFlows.Session;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenRefreshIT extends IntegrationTest {

    AuthFlows auth;

    @BeforeEach
    void flows() {
        auth = new AuthFlows(api, mailbox);
    }

    @Test
    void refreshRotatesTheRefreshTokenAndIssuesAWorkingAccessToken() {
        Session session = auth.newVerifiedSession(uniqueEmail());

        Session refreshed = AuthFlows.sessionFrom(auth.refresh(session.refreshToken()));

        assertThat(refreshed.refreshToken()).isNotEqualTo(session.refreshToken());
        assertThat(auth.me(refreshed.accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeSessionFamily() {
        Session session = auth.newVerifiedSession(uniqueEmail());
        Session rotated = AuthFlows.sessionFrom(auth.refresh(session.refreshToken()));

        HttpResponse<String> replay = auth.refresh(session.refreshToken());   // e.g. an attacker with the stolen old token

        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(Json.parse(replay.body()).get("code").asString()).isEqualTo("invalid_refresh_token");
        assertThat(auth.refresh(rotated.refreshToken()).statusCode())
                .as("the legitimate client's newer token dies too: the family is compromised")
                .isEqualTo(401);
    }

    @Test
    void concurrentRefreshesWithTheSameTokenSucceedAtMostOnce() throws Exception {
        Session session = auth.newVerifiedSession(uniqueEmail());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return auth.refresh(session.refreshToken()).statusCode();
                }));
            }
            start.countDown();
            long successes = 0;
            for (Future<Integer> result : results) {
                int status = result.get();
                assertThat(status).isIn(200, 401);
                successes += status == 200 ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);
        }
    }

    @Test
    void logoutRevokesTheSession() {
        Session session = auth.newVerifiedSession(uniqueEmail());

        HttpResponse<String> logout = api.post("/auth/v1/token/revocation", "", AuthFlows.withCookie(session.refreshToken()));

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("Set-Cookie")).hasValueSatisfying(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(auth.refresh(session.refreshToken()).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshRequiresTheCustomHeaderAndAnAllowedOrigin() {
        Session session = auth.newVerifiedSession(uniqueEmail());
        String cookie = "sf_refresh=" + session.refreshToken();

        HttpResponse<String> noHeader = api.post("/auth/v1/token/refresh", "", Map.of("Cookie", cookie));
        HttpResponse<String> foreignOrigin = api.post("/auth/v1/token/refresh", "",
                Map.of("Cookie", cookie, "X-Requested-With", "XMLHttpRequest", "Origin", "https://evil.example"));

        assertThat(noHeader.statusCode()).isEqualTo(403);
        assertThat(foreignOrigin.statusCode()).isEqualTo(403);
        assertThat(Json.parse(foreignOrigin.body()).get("code").asString()).isEqualTo("cross_site_request_rejected");
        assertThat(auth.refresh(session.refreshToken()).statusCode())
                .as("rejected attempts must not consume the token").isEqualTo(200);
    }
}
