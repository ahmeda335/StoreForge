package dev.storeforge.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Minimal black-box HTTP client: tests talk to the app exactly as a real client would. */
public final class HttpTestClient {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;

    public HttpTestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public HttpResponse<String> get(String path) {
        return get(path, Map.of());
    }

    public HttpResponse<String> get(String path, Map<String, String> headers) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET(), headers);
    }

    public HttpResponse<String> post(String path, String jsonBody) {
        return post(path, jsonBody, Map.of());
    }

    public HttpResponse<String> post(String path, String jsonBody, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        return send(request, headers);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, Map<String, String> headers) {
        headers.forEach(request::header);
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
