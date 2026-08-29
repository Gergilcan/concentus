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
    private final com.concentus.config.Settings settings;
    private final com.concentus.store.RunStore runStore;

    /** Rolling seven days: conservative against a plan whose week resets on a fixed day. */
    static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** Where a run starting now is told the allowance is nearly gone. */
    public static final int WARN_PERCENT = 80;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    // Explicit: the package-private constructor below exists for tests, and Spring will not
    // choose between two candidates on its own — the same lesson BuiltInEmbedder taught.
    @org.springframework.beans.factory.annotation.Autowired
    public ClaudeUsageService(ObjectMapper mapper, PricingTable pricing,
                              com.concentus.config.Settings settings,
                              com.concentus.store.RunStore runStore) {
        this(mapper, pricing,
                Path.of(System.getProperty("user.home"), ".claude", "projects"), settings, runStore);
    }

    ClaudeUsageService(ObjectMapper mapper, PricingTable pricing, Path projectsDir) {
        this(mapper, pricing, projectsDir, null, null);
    }

    ClaudeUsageService(ObjectMapper mapper, PricingTable pricing, Path projectsDir,
                       com.concentus.config.Settings settings, com.concentus.store.RunStore runStore) {
        this.mapper = mapper;
        this.pricing = pricing;
        this.projectsDir = projectsDir;
        this.settings = settings;
        this.runStore = runStore;
    }

    /**
     * Where this machine's runs stand against the plan's weekly allowance for non-interactive
     * use.
     *
     * @param allowanceUsd the plan's figure, API-equivalent dollars
     * @param runsUsd      what Concentus runs on the subscription cost in the last seven days —
     *                     the part of the allowance this app is responsible for
     * @param machineUsd   every Claude Code session on the machine in the same window, for scale
     * @param percent      runs against the allowance, whole percent, may exceed 100
     * @param state        ok | warn | exhausted
     */
    public record Allowance(double allowanceUsd, double runsUsd, double machineUsd, int percent, String state) {

        public double remainingUsd() {
            return Math.max(0d, allowanceUsd - runsUsd);
        }

        public boolean nearlyGone() {
            return percent >= WARN_PERCENT;
        }

        public boolean exhausted() {
            return percent >= 100;
        }
    }

    /**
     * The allowance meter, or null when no allowance is configured. Measured, not asked: the
     * runs' own cost records are the numerator. Anthropic's meter also counts headless use that
     * never went through this app — a GitHub Action, a script — which this cannot see, so the
     * figure is a floor and the page says so.
     */
    public Allowance allowance() {
        if (settings == null || runStore == null) return null;
        double allowance = settings.decimal("usage.weekly-allowance-usd", 0d);
        if (allowance <= 0) return null;
        long since = System.currentTimeMillis() - WEEK_MILLIS;
        double runs = runStore.spendUsdOnBackendSince("local", since);
        double machine = 0d;
        Object windows = summary().get("windows");
        if (windows instanceof Map<?, ?> w && w.get("week") instanceof Map<?, ?> week
                && week.get("estimatedUsd") instanceof Number n) {
            machine = n.doubleValue();
        }
        int percent = (int) Math.floor(runs / allowance * 100d);
        String state = percent >= 100 ? "exhausted" : percent >= WARN_PERCENT ? "warn" : "ok";
        return new Allowance(allowance, runs, machine, percent, state);
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
        out.put("days", perDay(samples));
        cached = out;
        cachedAt = now;
        return out;
    }

    /** The summary with the allowance meter beside it, for the page. */
    public Map<String, Object> summaryWithAllowance() {
        Map<String, Object> out = new LinkedHashMap<>(summary());
        Allowance a = allowance();
        if (a != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("allowanceUsd", a.allowanceUsd());
            m.put("runsUsd", a.runsUsd());
            m.put("machineUsd", a.machineUsd());
            m.put("remainingUsd", a.remainingUsd());
            m.put("percent", a.percent());
            m.put("state", a.state());
            out.put("allowance", m);
        }
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

    /**
     * The running figures a window or a per-model row reports.
     *
     * <p>One accumulator rather than the parallel {@code long[4]} plus a second map of costs the
     * per-model aggregation used to keep: two structures keyed the same way are two chances to
     * update one and forget the other, and the array's indices said nothing about which token
     * bucket they held.
     */
    private final class Totals {
        long in;
        long out;
        long cacheRead;
        long cacheWrite;
        double usd;
        int messages;

        void add(Sample s) {
            in += s.in();
            out += s.out();
            cacheRead += s.cacheRead();
            cacheWrite += s.cacheWrite();
            usd += pricing.costUsd(s.model(), s.in(), s.cacheRead(), s.cacheWrite(), s.out());
            messages++;
        }

        /** The token figures, under the keys and in the order the UI has always read them. */
        void putTokensInto(Map<String, Object> target) {
            target.put("inputTokens", in);
            target.put("outputTokens", out);
            target.put("cacheReadTokens", cacheRead);
            target.put("cacheWriteTokens", cacheWrite);
            target.put("estimatedUsd", usd);
        }
    }

    private Map<String, Object> window(List<Sample> samples, long since) {
        Totals totals = new Totals();
        for (Sample s : samples) {
            if (s.at() >= since) totals.add(s);
        }
        Map<String, Object> w = new LinkedHashMap<>();
        totals.putTokensInto(w);
        w.put("messages", totals.messages);
        return w;
    }

    /**
     * The last seven days, one entry each, oldest first.
     *
     * <p>Three totals answer "how much"; they cannot answer "is today unusual", which is the
     * question a total raises and the reason anybody opens this page twice. Days are calendar
     * days in the machine's own zone, not rolling 24-hour windows: the comparison people make is
     * with yesterday, and yesterday ends at midnight where they are.
     *
     * <p>Empty days are present with zeros rather than absent. A chart that omits them draws a
     * quiet week and a busy one identically.
     */
    private List<Map<String, Object>> perDay(List<Sample> samples) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        Map<java.time.LocalDate, Totals> byDay = new LinkedHashMap<>();
        for (int back = 6; back >= 0; back--) {
            byDay.put(today.minusDays(back), new Totals());
        }
        for (Sample s : samples) {
            java.time.LocalDate day = Instant.ofEpochMilli(s.at()).atZone(zone).toLocalDate();
            Totals totals = byDay.get(day);
            if (totals != null) totals.add(s);
        }
        return byDay.entrySet().stream()
                .map(e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("date", e.getKey().toString());
                    e.getValue().putTokensInto(d);
                    d.put("messages", e.getValue().messages);
                    return d;
                })
                .toList();
    }

    private List<Map<String, Object>> perModel(List<Sample> samples, long since) {
        Map<String, Totals> byModel = new LinkedHashMap<>();
        for (Sample s : samples) {
            if (s.at() < since) continue;
            byModel.computeIfAbsent(s.model(), k -> new Totals()).add(s);
        }
        return byModel.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("model", e.getKey());
                    e.getValue().putTokensInto(m);
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
