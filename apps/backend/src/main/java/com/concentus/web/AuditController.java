package com.concentus.web;

import com.concentus.audit.AuditEvent;
import com.concentus.audit.AuditKinds;
import com.concentus.auth.OrgContext;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.service.RetentionService;
import com.concentus.store.AuditStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit trail, read.
 *
 * <p>Admin only, on every tier: an administrator of a Team deployment may read what their people
 * did — that is half the reason to have members at all. What the tier decides is whether the trail
 * may <em>leave</em>: {@link #export} is {@link Feature#AUDIT_EXPORT}, and below Enterprise it
 * answers 403 with the same sentence the panel prints on its disabled button, so nobody discovers
 * the gate by clicking.
 *
 * <p>Paged by cursor rather than by offset: {@code before=<id>} is the last id of the page just
 * read, and a trail that grows while someone scrolls it never shows the same row twice or skips
 * one — which page numbers over a table with inserts at the top would.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    /** Rows per page when the caller does not say; the cap however hard they ask. */
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final AuditStore store;
    private final OrgContext orgContext;
    private final LicenseService license;
    private final RetentionService retention;

    public AuditController(AuditStore store, OrgContext orgContext, LicenseService license,
                           RetentionService retention) {
        this.store = store;
        this.orgContext = orgContext;
        this.license = license;
        this.retention = retention;
    }

    /**
     * The newest page. {@code from}/{@code to} take a date ({@code 2026-08-01}, inclusive at both
     * ends, in UTC) or epoch milliseconds; the panel sends dates.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String actor,
                                    @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Long before,
                                    @RequestParam(required = false) Integer limit) {
        orgContext.requireAdmin();
        int pageSize = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<AuditEvent> page = store.list(orgContext.requireOrganizationId(),
                new AuditStore.Filter(actor, kind, parseMoment(from, false), parseMoment(to, true)),
                before, pageSize);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", page);
        // A full page MAY have a next one; the client asks and finds out. A short page cannot.
        out.put("hasMore", page.size() == pageSize);
        out.put("nextBefore", page.isEmpty() ? null : page.get(page.size() - 1).id());
        return out;
    }

    /**
     * What the panel needs before it draws: the kinds it may filter by, whether export is allowed
     * (and the sentence to print when it is not), and the retention in force with its reason.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        orgContext.requireAdmin();
        RetentionService.Policy policy = retention.policy();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", store.isAvailable());
        out.put("kinds", AuditKinds.ALL);
        out.put("exportRefusal", license.refusal(Feature.AUDIT_EXPORT));
        out.put("retentionDays", policy.days());
        out.put("retentionReason", policy.reason());
        return out;
    }

    /**
     * The trail as a file, oldest row first, streamed: a year of it need not fit in memory here
     * or be assembled before the first byte leaves.
     *
     * @param format {@code csv} or {@code json}; anything else is a 400
     */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        orgContext.requireAdmin();
        String refusal = license.refusal(Feature.AUDIT_EXPORT);
        if (refusal != null) {
            throw new OrgContext.AccessDeniedForOrganization(refusal);
        }
        boolean json = "json".equalsIgnoreCase(format);
        if (!json && !"csv".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("format must be csv or json.");
        }
        String organizationId = orgContext.requireOrganizationId();
        AuditStore.Filter filter = new AuditStore.Filter(actor, kind, parseMoment(from, false),
                parseMoment(to, true));
        String filename = "concentus-audit-" + LocalDate.now(ZoneOffset.UTC) + (json ? ".json" : ".csv");
        StreamingResponseBody body = out -> {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            if (json) writeJson(w, organizationId, filter);
            else writeCsv(w, organizationId, filter);
            w.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(json ? MediaType.APPLICATION_JSON : new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private void writeCsv(Writer w, String organizationId, AuditStore.Filter filter) throws IOException {
        w.write("id,at,actor,role,kind,subject_type,subject_id,subject_label,detail\n");
        forEachRow(organizationId, filter, e ->
                w.write(e.id() + "," + Instant.ofEpochMilli(e.at()) + "," + csv(e.actorEmail()) + ","
                        + csv(e.actorRole()) + "," + csv(e.kind()) + "," + csv(e.subjectType()) + ","
                        + csv(e.subjectId()) + "," + csv(e.subjectLabel()) + "," + csv(e.detail()) + "\n"));
    }

    private void writeJson(Writer w, String organizationId, AuditStore.Filter filter) throws IOException {
        w.write("[");
        boolean[] first = {true};
        forEachRow(organizationId, filter, e -> {
            w.write(first[0] ? "\n" : ",\n");
            first[0] = false;
            w.write("{\"id\":" + e.id() + ",\"at\":\"" + Instant.ofEpochMilli(e.at()) + "\""
                    + ",\"actor\":" + jsonString(e.actorEmail())
                    + ",\"role\":" + jsonString(e.actorRole())
                    + ",\"kind\":" + jsonString(e.kind())
                    + ",\"subjectType\":" + jsonString(e.subjectType())
                    + ",\"subjectId\":" + jsonString(e.subjectId())
                    + ",\"subjectLabel\":" + jsonString(e.subjectLabel())
                    // Stored as a JSON object already; it goes out as one, not as a string of one.
                    + ",\"detail\":" + (e.detail() == null || e.detail().isBlank() ? "null" : e.detail())
                    + "}");
        });
        w.write("\n]\n");
    }

    /** One row of the export; may fail the way any write to a socket may. */
    @FunctionalInterface
    private interface RowWriter {
        void write(AuditEvent event) throws IOException;
    }

    /**
     * Every matching row, oldest first, handed to a writer that may throw.
     *
     * <p>The store's callback cannot throw a checked exception, and a client that disconnects
     * mid-export must stop the walk rather than have every remaining row written into a closed
     * stream — so the first failure is kept, the rest of the walk skipped, and it is rethrown once
     * the store returns.
     */
    private void forEachRow(String organizationId, AuditStore.Filter filter, RowWriter writer)
            throws IOException {
        IOException[] failure = new IOException[1];
        store.forEach(organizationId, filter, e -> {
            if (failure[0] != null) return;
            try {
                writer.write(e);
            } catch (IOException ex) {
                failure[0] = ex;
            }
        });
        if (failure[0] != null) throw failure[0];
    }

    /** A CSV field: quoted whenever it holds something a reader would split on, doubled quotes inside. */
    static String csv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * A moment from a query parameter: epoch milliseconds, or a date. A date names a whole day,
     * so as a lower bound it is that day's first millisecond and as an upper bound its last —
     * "to 2026-08-31" includes the 31st, which is what anyone typing it means.
     */
    static Long parseMoment(String raw, boolean endOfDay) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.chars().allMatch(Character::isDigit)) return Long.parseLong(s);
        try {
            LocalDate day = LocalDate.parse(s);
            Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            return endOfDay ? start.plusMillis(86_400_000L - 1).toEpochMilli() : start.toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a date (yyyy-mm-dd) or a timestamp.");
        }
    }
}
