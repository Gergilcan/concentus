package com.concentus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeUsageServiceTest {

    private static String line(Instant at, String model, long in, long out) {
        return "{\"type\":\"assistant\",\"timestamp\":\"" + at + "\",\"message\":{\"model\":\""
                + model + "\",\"usage\":{\"input_tokens\":" + in
                + ",\"cache_creation_input_tokens\":5,\"cache_read_input_tokens\":10,"
                + "\"output_tokens\":" + out + "}}}";
    }

    @Test
    void aggregatesTranscriptUsageIntoWindows(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("C--fake-project");
        Files.createDirectories(project);
        Instant now = Instant.now();
        Files.writeString(project.resolve("s1.jsonl"), String.join("\n",
                line(now.minusSeconds(60), "claude-opus-4-8", 100, 50),
                line(now.minusSeconds(6 * 3600), "claude-opus-4-8", 1000, 500),
                // Malformed lines and non-assistant lines must be skipped, not fatal.
                "{\"type\":\"user\",\"message\":{}}",
                "not json at all"));

        ClaudeUsageService service = new ClaudeUsageService(
                new ObjectMapper(), new PricingTable("", 3.0, 15.0), temp);
        Map<String, Object> summary = service.summary();

        assertThat(summary.get("available")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> windows = (Map<String, Map<String, Object>>) summary.get("windows");
        // The 6-hours-ago message is outside the 5h window but inside the week.
        assertThat(windows.get("last5h").get("inputTokens")).isEqualTo(100L);
        assertThat(windows.get("week").get("inputTokens")).isEqualTo(1100L);
        assertThat((Double) windows.get("week").get("estimatedUsd")).isGreaterThan(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) summary.get("models");
        assertThat(models).singleElement()
                .satisfies(m -> assertThat(m.get("model")).isEqualTo("claude-opus-4-8"));
    }

    @Test
    void aMissingTranscriptDirectoryIsReportedNotThrown(@TempDir Path temp) {
        ClaudeUsageService service = new ClaudeUsageService(
                new ObjectMapper(), new PricingTable("", 3.0, 15.0), temp.resolve("nope"));

        assertThat(service.summary().get("available")).isEqualTo(false);
    }
}
