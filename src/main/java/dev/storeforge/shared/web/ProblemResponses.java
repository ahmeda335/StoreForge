package dev.storeforge.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes Problem Details from code that runs OUTSIDE Spring MVC (security filters reject requests
 * before any controller or {@code @RestControllerAdvice} is involved). Produces the same shape as
 * {@link ApiExceptionHandler} so clients see one error format everywhere.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(JsonMapper jsonMapper, HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String code, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://docs.storeforge.dev/errors/" + code.replace('_', '-'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        Object requestId = request.getAttribute(RequestIds.REQUEST_ATTRIBUTE);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
