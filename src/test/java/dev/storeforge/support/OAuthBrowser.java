package dev.storeforge.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A browser for the OAuth install flow: keeps cookies (the authorization server's session), never
 * follows redirects (each hop is asserted), submits forms. Plus the app's side: PKCE and the token call.
 */
public final class OAuthBrowser {

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();

    public OAuthBrowser(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public HttpResponse<String> get(String pathOrUrl) {
        return send(HttpRequest.newBuilder(uri(pathOrUrl)).GET().build());
    }

    /** Form POST; a key may repeat (scope=a, scope=b), hence a list of pairs. */
    public HttpResponse<String> post(String pathOrUrl, List<Map.Entry<String, String>> form, Map<String, String> headers) {
        String body = form.stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(pathOrUrl)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return send(request.build());
    }

    public static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElseThrow(() -> new AssertionError("no redirect: " + response.statusCode()
                + " " + response.body()));
    }

    /** Value of {@code <input ... name="NAME" value="VALUE">} (our pages quote both). */
    public static String hidden(String html, String name) {
        Matcher m = Pattern.compile("name=\"?" + Pattern.quote(name) + "\"? value=\"([^\"]*)\"").matcher(html);
        if (!m.find()) {
            throw new AssertionError("no input " + name + " in " + html);
        }
        return m.group(1).replace("&amp;", "&");
    }

    public static String query(String url, String parameter) {
        Matcher m = Pattern.compile("[?&]" + Pattern.quote(parameter) + "=([^&]*)").matcher(url);
        return m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    public static String newVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((encode(clientId) + ":" + encode(secret)).getBytes(StandardCharsets.UTF_8));
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private URI uri(String pathOrUrl) {
        return URI.create(pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + pathOrUrl);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
