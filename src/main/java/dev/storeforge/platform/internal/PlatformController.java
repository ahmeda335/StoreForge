package dev.storeforge.platform.internal;

import dev.storeforge.audit.PlatformAuditLog;
import dev.storeforge.identity.PlatformAdminPrincipal;
import dev.storeforge.jobs.JobAdministration;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.StoreAdministration;
import dev.storeforge.stores.StoreAdministration.StoreSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /platform/v1: only reachable with a platform token (security chain in identity). Every write is audited. */
@RestController
@RequestMapping("/platform/v1")
@RequiredArgsConstructor
@Validated
class PlatformController {

    private final StoreAdministration stores;
    private final JobAdministration jobs;
    private final PlatformAuditLog audit;
    private final TransactionTemplate transaction;

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping("/stores")
    Map<String, List<StoreSummary>> stores(@RequestParam(required = false) @Nullable @Size(max = 100) String q,
                                           @RequestParam(required = false) @Nullable @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED") String status,
                                           @RequestParam(defaultValue = "50") int limit) {
        return Map.of("data", stores.list(q, status, limit));
    }

    @PostMapping("/stores/{storeId}/suspension")
    StoreSummary suspend(@AuthenticationPrincipal PlatformAdminPrincipal admin, @PathVariable UUID storeId, @Valid @RequestBody ReasonRequest body,
                         HttpServletRequest request) {
        return stores.suspend(StoreId.of(storeId), admin.userId(), body.reason(), request.getRemoteAddr());
    }

    @PostMapping("/stores/{storeId}/reinstatement")
    StoreSummary reinstate(@AuthenticationPrincipal PlatformAdminPrincipal admin, @PathVariable UUID storeId,
                           @Valid @RequestBody ReasonRequest body, HttpServletRequest request) {
        return stores.reinstate(StoreId.of(storeId), admin.userId(), body.reason(), request.getRemoteAddr());
    }

    @GetMapping("/jobs")
    JobAdministration.Overview jobs() {
        return jobs.overview(50);
    }

    @PostMapping("/jobs/{jobId}/requeue")
    Map<String, Object> requeue(@AuthenticationPrincipal PlatformAdminPrincipal admin, @PathVariable UUID jobId, HttpServletRequest request) {
        boolean requeued = transaction.execute(status -> {
            boolean done = jobs.requeue(jobId);
            audit.record(admin.userId(), "job.requeued", "job", jobId.toString(), Map.of("requeued", done), request.getRemoteAddr());
            return done;
        });
        return Map.of("jobId", jobId, "requeued", requeued);
    }

    @GetMapping("/audit")
    Map<String, List<PlatformAuditLog.Entry>> audit(@RequestParam(defaultValue = "50") int limit) {
        return Map.of("data", audit.recent(Math.max(1, Math.min(limit, 200))));
    }
}
