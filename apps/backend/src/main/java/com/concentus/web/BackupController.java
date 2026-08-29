package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.service.BackupService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The whole configuration as one file: download it here, upload it on another machine. */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backup;
    private final OrgContext orgContext;

    public BackupController(BackupService backup, OrgContext orgContext) {
        this.backup = backup;
        this.orgContext = orgContext;
    }

    /**
     * @param includeSecrets carry credential values, in the clear, in the file. Off by default and
     *                       admin-only: the plain export is safe to mail around, and this one is
     *                       every password the installation holds. The file name says which it is,
     *                       for the person finding it in a Downloads folder a year later.
     */
    @GetMapping
    public ResponseEntity<JsonNode> export(
            @RequestParam(name = "includeSecrets", defaultValue = "false") boolean includeSecrets) {
        if (includeSecrets) orgContext.requireAdmin();
        // The date in the name is for the human sorting a Downloads folder, nothing else.
        String filename = "concentus-export-" + (includeSecrets ? "with-secrets-" : "")
                + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(backup.export(includeSecrets));
    }

    @PostMapping
    public BackupService.ImportReport importBundle(@RequestBody JsonNode bundle) {
        return backup.importBundle(bundle);
    }
}
