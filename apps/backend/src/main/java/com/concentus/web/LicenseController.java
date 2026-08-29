package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.license.InvalidLicenseException;
import com.concentus.license.LicenseService;
import com.concentus.license.LicenseStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * What the UI's license screen reads and writes: the current status, and installing a new key.
 *
 * <p>Reading is every signed-in role — the security filter chain already opens {@code GET
 * /api/**} that widely, and there is nothing in a status worth hiding from a Viewer. Installing a
 * new key is admin only, the same {@link OrgContext#requireAdmin()} gate {@code
 * SettingsController#save} uses: a license changes what the whole organization may do, not
 * something any signed-in member should be able to paste over.
 */
@RestController
@RequestMapping("/api/license")
public class LicenseController {

    private final LicenseService license;
    private final OrgContext orgContext;
    /** A new license changes what everyone here may do; who installed which one is on record. */
    private final com.concentus.audit.AuditService audit;

    public LicenseController(LicenseService license, OrgContext orgContext,
                             com.concentus.audit.AuditService audit) {
        this.license = license;
        this.orgContext = orgContext;
        this.audit = audit;
    }

    @GetMapping
    public LicenseStatus status() {
        return license.status();
    }

    /**
     * Installs a license token. 400 with the reason on an unverifiable token or a refusal (an
     * {@code CONCENTUS_LICENSE} environment variable already winning) — {@link InvalidLicenseException}
     * carries a message written to be read by whoever pasted the token in, so it is returned as-is
     * rather than mapped through the generic handler that hides internals.
     */
    @PostMapping
    public ResponseEntity<?> install(@RequestBody InstallRequest body) {
        orgContext.requireAdmin();
        try {
            license.install(body.token());
            LicenseStatus installed = license.status();
            // The token itself is the one thing NOT written: it is the key, and the trail is
            // readable by every admin. Tier, licensee, seats and expiry say what was installed.
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("tier", installed.tier());
            detail.put("seats", installed.seats());
            detail.put("expires", installed.expires());
            audit.record(com.concentus.audit.AuditKinds.LICENSE_INSTALLED, "license", null,
                    installed.licensee(), detail);
            return ResponseEntity.ok(installed);
        } catch (InvalidLicenseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record InstallRequest(String token) { }
}
