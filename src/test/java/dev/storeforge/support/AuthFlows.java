package dev.storeforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/** Reusable HTTP-level auth steps for integration tests. */
@RequiredArgsConstructor
public final class AuthFlows {

    public static final String PASSWORD = "correct horse battery staple";
    public static final Map<String, String> SAME_SITE = Map.of("X-Requested-With", "XMLHttpRequest");

    private static final Pattern REFRESH_COOKIE = Pattern.compile("sf_refresh=([^;]*)");

    private final HttpTestClient api;
    private final RecordingEmailSender mailbox;

    public record Session(String accessToken, String refreshToken) {
    }

    public HttpResponse<String> register(String email, String password) {
        return api.post("/auth/v1/registrations", Json.object("email", email, "password", password));
    }

    public void registerAndVerify(String email, String password) {
        assertThat(register(email, password).statusCode()).isEqualTo(202);
        String token = mailbox.latestToken(email, "Verify").orElseThrow();
        assertThat(api.post("/auth/v1/email-verifications/confirmation", Json.object("token", token)).statusCode())
                .isEqualTo(204);
    }

    public HttpResponse<String> login(String email, String password) {
        return api.post("/auth/v1/token", Json.object("email", email, "password", password));
    }

    public Session newVerifiedSession(String email) {
        registerAndVerify(email, PASSWORD);
        return sessionFrom(login(email, PASSWORD));
    }

    public HttpResponse<String> refresh(String refreshToken) {
        return api.post("/auth/v1/token/refresh", "", withCookie(refreshToken));
    }

    public HttpResponse<String> me(String accessToken) {
        return api.get("/admin/v1/me", Map.of("Authorization", "Bearer " + accessToken));
    }

    public static Map<String, String> withCookie(String refreshToken) {
        return Map.of("X-Requested-With", "XMLHttpRequest", "Cookie", "sf_refresh=" + refreshToken);
    }

    public static Session sessionFrom(HttpResponse<String> tokenResponse) {
        assertThat(tokenResponse.statusCode()).as(tokenResponse.body()).isEqualTo(200);
        String accessToken = Json.parse(tokenResponse.body()).get("access_token").asString();
        return new Session(accessToken, refreshCookie(tokenResponse));
    }

    public static String refreshCookie(HttpResponse<String> response) {
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        Matcher matcher = REFRESH_COOKIE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
