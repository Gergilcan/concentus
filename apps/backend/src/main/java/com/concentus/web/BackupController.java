package com.concentus.web;

import com.concentus.service.BackupService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The whole configuration as one file: download it here, upload it on another machine. */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backup;

    public BackupController(BackupService backup) {
        this.backup = backup;
    }

    @GetMapping
    public ResponseEntity<JsonNode> export() {
        // The date in the name is for the human sorting a Downloads folder, nothing else.
        String filename = "concentus-export-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(backup.export());
    }

    @PostMapping
    public BackupService.ImportReport importBundle(@RequestBody JsonNode bundle) {
        return backup.importBundle(bundle);
    }
}
