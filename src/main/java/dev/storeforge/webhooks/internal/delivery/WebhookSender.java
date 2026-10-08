package dev.storeforge.webhooks.internal.delivery;

import dev.storeforge.webhooks.internal.security.DestinationNotAllowedException;
import dev.storeforge.webhooks.internal.security.EndpointPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;

/**
 * Sends one webhook request to a URL we don't control. Defenses, each against a concrete attack or failure:
 * <ul>
 *   <li>DNS goes through {@link EndpointPolicy}: the client connects ONLY to addresses that passed
 *       the SSRF check, at the moment of connecting (no rebinding window between check and use);</li>
 *   <li>redirects are never followed (a 302 to http://169.254.169.254 is the classic SSRF);</li>
 *   <li>connect timeout + a hard total deadline (the request is cancelled even if the server
 *       keeps dripping bytes, which a per-read timeout would allow forever);</li>
 *   <li>at most N connections per endpoint: one slow receiver can't take all connections (bulkhead);</li>
 *   <li>only the first few hundred bytes of the response are read and kept.</li>
 * </ul>
 */
public class WebhookSender implements AutoCloseable {

    /** {@code statusCode} null: no HTTP response (timeout, refused, blocked). */
    public record Response(@Nullable Integer statusCode, long durationMs, @Nullable String error, @Nullable String excerpt,
                           @Nullable Duration retryAfter, boolean blocked) {
        public boolean succeeded() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }

    private final CloseableHttpClient client;
    private final Duration requestTimeout;
    private final int excerptBytes;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public WebhookSender(EndpointPolicy policy, Duration connectTimeout, Duration requestTimeout, int maxConnectionsPerEndpoint,
                         int excerptBytes, String userAgent) {
        this.requestTimeout = requestTimeout;
        this.excerptBytes = excerptBytes;
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new org.apache.hc.client5.http.DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        return policy.resolveAllowed(host);
                    }

                    @Override
                    public String resolveCanonicalHostname(String host) {
                        return host;
                    }
                })
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout))
                        .setSocketTimeout(Timeout.of(requestTimeout))
                        .build())
                .setMaxConnPerRoute(maxConnectionsPerEndpoint)
                .setMaxConnTotal(500)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()   // retries are OUR decision (schedule, idempotent receivers)
                .disableCookieManagement()
                .setUserAgent(userAgent)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(requestTimeout))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2))   // endpoint at its connection cap: fail fast
                        .build())
                .build();
    }

    public Response send(URI url, Map<String, String> headers, String body) {
        HttpPost post = new HttpPost(url);
        headers.forEach(post::setHeader);
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        ScheduledFuture<?> deadline = deadlines.schedule(post::cancel, requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        long started = System.nanoTime();
        try (ClassicHttpResponse response = client.executeOpen(null, post, null)) {
            String excerpt = excerpt(response.getEntity());
            post.cancel();   // don't read (or reuse) whatever else the receiver sends
            return new Response(response.getCode(), elapsed(started), null, excerpt, retryAfter(response.getFirstHeader("Retry-After")),
                    false);
        } catch (DestinationNotAllowedException e) {
            return new Response(null, elapsed(started), "destination not allowed: " + e.getMessage(), null, null, true);
        } catch (IOException e) {
            String error = post.isCancelled() && elapsed(started) >= requestTimeout.toMillis() - 50
                    ? "timeout: no complete response within " + requestTimeout.toMillis() + " ms"
                    : e instanceof SocketTimeoutException ? "timeout: " + e.getMessage()
                    : e instanceof ConnectException ? "connection failed: " + e.getMessage()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            return new Response(null, elapsed(started), error, null, null, false);
        } finally {
            deadline.cancel(false);
        }
    }

    private @Nullable String excerpt(@Nullable HttpEntity entity) throws IOException {
        if (entity == null || excerptBytes == 0) {
            return null;
        }
        try (InputStream in = entity.getContent()) {
            byte[] bytes = in.readNBytes(excerptBytes);
            return new String(bytes, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        }
    }

    private static @Nullable Duration retryAfter(@Nullable Header header) {
        if (header == null) {
            return null;
        }
        String value = header.getValue().trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException e) {
            Instant at = DateUtils.parseStandardDate(value);
            return at == null ? null : Duration.between(Instant.now(), at);
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
