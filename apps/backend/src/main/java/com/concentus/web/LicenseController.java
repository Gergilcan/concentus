package com.concentus.web;

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

/** What the UI's license screen reads and writes: the current status, and installing a new key. */
@RestController
@RequestMapping("/api/license")
public class LicenseController {

    private final LicenseService license;

    public LicenseController(LicenseService license) {
        this.license = license;
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
        try {
            license.install(body.token());
            return ResponseEntity.ok(license.status());
        } catch (InvalidLicenseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record InstallRequest(String token) { }
}
