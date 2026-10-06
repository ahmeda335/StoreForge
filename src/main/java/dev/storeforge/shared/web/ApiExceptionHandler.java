package dev.storeforge.shared.web;

import dev.storeforge.shared.error.StoreForgeException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 Problem Details response
 * ({@code application/problem+json}) with two StoreForge extensions:
 *
 * <ul>
 *   <li>{@code code}: a stable, machine-readable identifier clients can switch on
 *       (titles and details are for humans and may change);</li>
 *   <li>{@code requestId}: the correlation ID, so a client-visible error can be found in the logs.</li>
 * </ul>
 *
 * <p>The base class already maps Spring MVC's own exceptions (404 no handler, 405, 415, malformed
 * JSON, validation...). This class adds the extensions to all of them and handles anything
 * unexpected as a 500 that never leaks internals (stack traces, SQL, class names).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://docs.storeforge.dev/errors/";

    private final Tracer tracer;

    ApiExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(StoreForgeException.class)
    ResponseEntity<Object> handleDomain(StoreForgeException exception, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setProperty("code", exception.code());
        return handleExceptionInternal(exception, problem, exception.headers(), exception.status(), request);
    }

    /**
     * Two writers changed the same aggregate concurrently and JPA's {@code @Version} check caught the
     * second one at flush time. Retrying blindly would overwrite the other change; the client must
     * reload and decide.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException exception, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was modified concurrently. Reload it and retry.");
        problem.setProperty("code", "concurrent_modification");
        return handleExceptionInternal(exception, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * Bean Validation failures on request bodies: 422 with one entry per invalid field, so clients
     * can show errors next to the right input. (Spring's default is a 400 without field details.)
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Request validation failed.");
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "code", error.getCode() == null ? "invalid" : error.getCode(),
                        "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        // The only place unexpected failures are logged: once, with the stack trace, at ERROR.
        log.error("Unhandled exception while processing request", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the requestId when contacting support.");
        return handleExceptionInternal(exception, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            enrich(problem, statusCode, request);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private void enrich(ProblemDetail problem, HttpStatusCode statusCode, WebRequest request) {
        String code = codeFor(statusCode);
        if (problem.getType() == null || URI.create("about:blank").equals(problem.getType())) {
            problem.setType(URI.create(TYPE_BASE + code.replace('_', '-')));
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", code);
        }
        Object requestId = request.getAttribute(RequestIds.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        Span span = tracer.currentSpan();
        if (span != null) {
            problem.setProperty("traceId", span.context().traceId());
        }
    }

    /** Generic codes per status; domain exceptions will supply specific ones (e.g. insufficient_stock). */
    private static String codeFor(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            return "error";
        }
        return switch (status) {
            case BAD_REQUEST -> "bad_request";
            case UNAUTHORIZED -> "unauthenticated";
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "not_found";
            case METHOD_NOT_ALLOWED -> "method_not_allowed";
            case NOT_ACCEPTABLE -> "not_acceptable";
            case CONFLICT -> "conflict";
            case UNSUPPORTED_MEDIA_TYPE -> "unsupported_media_type";
            case UNPROCESSABLE_CONTENT -> "validation_failed";
            case TOO_MANY_REQUESTS -> "rate_limited";
            case SERVICE_UNAVAILABLE -> "service_unavailable";
            default -> status.is5xxServerError() ? "internal_error" : "error";
        };
    }
}
