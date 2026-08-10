package com.concentus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What this machine's Claude Code login has actually consumed, measured at the source.
 *
 * <p>Anthropic exposes no API for a subscription's quota or credit balance — that number lives
 * only in the CLI's own {@code /usage} screen. What CAN be known exactly is consumption: Claude
 * Code writes a transcript line for every assistant message, with the model and the token counts,
 * under {@code ~/.claude/projects/}. This service reads those — every session on the machine, not
 * just Concentus runs — and aggregates them into the windows a subscription thinks in: the rolling
 * 5-hour block, today, the last 7 days.
 *
 * <p>The UI labels the result for what it is: measured consumption and its API-equivalent value,
 * not the official meter. Honest and always available beats official and unreachable.
 */
@Service
public class ClaudeUsageService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeUsageService.class);

    /** Transcripts older than this cannot contribute to any window we show; skip their IO. */
    private static final long LOOKBACK_MILLIS = 8L * 24 * 60 * 60 * 1000;
    private static final long CACHE_MILLIS = 30_000;

    private final ObjectMapper mapper;
    private final PricingTable pricing;
    private final Path projectsDir;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    // Explicit: the package-private constructor below exists for tests, and Spring will not
    // choose between two candidates on its own — the same lesson BuiltInEmbedder taught.
    @org.springframework.beans.factory.annotation.Autowired
    public ClaudeUsageService(ObjectMapper mapper, PricingTable pricing) {
        this(mapper, pricing,
                Path.of(System.getProperty("user.home"), ".claude", "projects"));
    }

    ClaudeUsageService(ObjectMapper mapper, PricingTable pricing, Path projectsDir) {
        this.mapper = mapper;
        this.pricing = pricing;
        this.projectsDir = projectsDir;
    }

    /** One assistant message's worth of usage. */
    record Sample(long at, String model, long in, long out, long cacheRead, long cacheWrite) {
    }

    public synchronized Map<String, Object> summary() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MILLIS) return cached;

        List<Sample> samples = new ArrayList<>();
        if (Files.isDirectory(projectsDir)) {
            try (Stream<Path> dirs = Files.list(projectsDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> collectDir(dir, now, samples));
            } catch (IOException e) {
                log.debug("usage scan: {}", e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Files.isDirectory(projectsDir));
        out.put("windows", Map.of(
                "last5h", window(samples, now - 5L * 60 * 60 * 1000),
                "today", window(samples, startOfToday()),
                "week", window(samples, now - 7L * 24 * 60 * 60 * 1000)));
        out.put("models", perModel(samples, now - 7L * 24 * 60 * 60 * 1000));
        cached = out;
        cachedAt = now;
        return out;
    }

    private void collectDir(Path dir, long now, List<Sample> samples) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".jsonl"))
                    .filter(f -> {
                        try {
                            return Files.getLastModifiedTime(f).toMillis() >= now - LOOKBACK_MILLIS;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(f -> collectFile(f, samples));
        } catch (IOException e) {
            log.debug("usage scan {}: {}", dir, e.getMessage());
        }
    }

    private void collectFile(Path file, List<Sample> samples) {
        // Line-streamed with a cheap substring pre-filter: transcripts run to tens of megabytes
        // and only assistant lines carry usage.
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"usage\"") || !line.contains("\"assistant\"")) continue;
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode usage = node.path("message").path("usage");
                    if (!usage.isObject()) continue;
                    String model = node.path("message").path("model").asText("unknown");
                    // Synthetic rows are the CLI talking to itself (retries, summaries) at no cost.
                    if (model.startsWith("<")) continue;
                    long at = Instant.parse(node.path("timestamp").asText()).toEpochMilli();
                    samples.add(new Sample(at,
                            model,
                            usage.path("input_tokens").asLong(0),
                            usage.path("output_tokens").asLong(0),
                            usage.path("cache_read_input_tokens").asLong(0),
                            usage.path("cache_creation_input_tokens").asLong(0)));
                } catch (RuntimeException ignored) {
                    // One malformed line must not hide a week of usage.
                }
            }
        } catch (IOException e) {
            log.debug("usage read {}: {}", file, e.getMessage());
        }
    }

    private Map<String, Object> window(List<Sample> samples, long since) {
        long in = 0;
        long out = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        double usd = 0;
        int messages = 0;
        for (Sample s : samples) {
            if (s.at() < since) continue;
            in += s.in();
            out += s.out();
            cacheRead += s.cacheRead();
            cacheWrite += s.cacheWrite();
            usd += pricing.costUsd(s.model(), s.in(), s.cacheRead(), s.cacheWrite(), s.out());
            messages++;
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("inputTokens", in);
        w.put("outputTokens", out);
        w.put("cacheReadTokens", cacheRead);
        w.put("cacheWriteTokens", cacheWrite);
        w.put("estimatedUsd", usd);
        w.put("messages", messages);
        return w;
    }

    private List<Map<String, Object>> perModel(List<Sample> samples, long since) {
        Map<String, long[]> byModel = new LinkedHashMap<>();
        Map<String, Double> usdByModel = new LinkedHashMap<>();
        for (Sample s : samples) {
            if (s.at() < since) continue;
            long[] agg = byModel.computeIfAbsent(s.model(), k -> new long[4]);
            agg[0] += s.in();
            agg[1] += s.out();
            agg[2] += s.cacheRead();
            agg[3] += s.cacheWrite();
            usdByModel.merge(s.model(),
                    pricing.costUsd(s.model(), s.in(), s.cacheRead(), s.cacheWrite(), s.out()),
                    Double::sum);
        }
        return byModel.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("model", e.getKey());
                    m.put("inputTokens", e.getValue()[0]);
                    m.put("outputTokens", e.getValue()[1]);
                    m.put("cacheReadTokens", e.getValue()[2]);
                    m.put("cacheWriteTokens", e.getValue()[3]);
                    m.put("estimatedUsd", usdByModel.get(e.getKey()));
                    return m;
                })
                .sorted(Comparator.comparingDouble(
                        (Map<String, Object> m) -> (Double) m.get("estimatedUsd")).reversed())
                .toList();
    }

    private static long startOfToday() {
        return java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
