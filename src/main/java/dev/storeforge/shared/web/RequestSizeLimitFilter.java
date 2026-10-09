package dev.storeforge.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caps request bodies (OWASP API4: unrestricted resource consumption). Tomcat's own limit only
 * covers form posts; a JSON body was read and parsed whatever its size (20 MB measured, P14).
 * <ul>
 *   <li>Declared {@code Content-Length} too large: 413 at once, nothing is read.</li>
 *   <li>Chunked (no length): the stream is counted and fails once the limit is passed; the
 *       exception handler turns that into the same 413.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final HttpEdgeProperties properties;
    private final JsonMapper jsonMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long max = properties.maxRequestBody().toBytes();
        if (request.getContentLengthLong() > max) {
            ProblemResponses.write(jsonMapper, request, response, HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                    "The request body exceeds " + max + " bytes.");
            return;
        }
        chain.doFilter(request.getContentLengthLong() >= 0 ? request : new Limited(request, max), response);
    }

    /** Thrown while reading a body that passes the limit (only possible without Content-Length). */
    public static class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(long max) {
            super("The request body exceeds " + max + " bytes.");
        }
    }

    private static final class Limited extends HttpServletRequestWrapper {

        private final long max;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream delegate = super.getInputStream();
                stream = new ServletInputStream() {
                    private long read;

                    @Override
                    public int read() throws IOException {
                        int b = delegate.read();
                        if (b >= 0) {
                            count(1);
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int n = delegate.read(buffer, offset, length);
                        if (n > 0) {
                            count(n);
                        }
                        return n;
                    }

                    private void count(int n) throws PayloadTooLargeException {
                        read += n;
                        if (read > max) {
                            throw new PayloadTooLargeException(max);
                        }
                    }

                    @Override
                    public boolean isFinished() {
                        return delegate.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        delegate.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
