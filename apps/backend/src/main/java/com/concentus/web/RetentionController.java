package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.service.RetentionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The retention policy, read and applied on demand.
 *
 * <p>The nightly job is the normal path; {@code run-now} exists for the administrator who just
 * lowered the Enterprise window and wants to see it take effect rather than wait for three in the
 * morning — and for a test of the deployment that wants the purge to happen while someone is
 * watching. Admin only: it deletes.
 */
@RestController
@RequestMapping("/api/retention")
public class RetentionController {

    private final RetentionService retention;
    private final OrgContext orgContext;

    public RetentionController(RetentionService retention, OrgContext orgContext) {
        this.retention = retention;
        this.orgContext = orgContext;
    }

    @GetMapping
    public RetentionService.Policy policy() {
        orgContext.requireAdmin();
        return retention.policy();
    }

    @PostMapping("/run-now")
    public RetentionService.Report runNow() {
        orgContext.requireAdmin();
        return retention.runNow();
    }
}
