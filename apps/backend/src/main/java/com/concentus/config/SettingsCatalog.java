package com.concentus.config;

import java.util.List;

import static com.concentus.config.SettingDef.choice;
import static com.concentus.config.SettingDef.flag;
import static com.concentus.config.SettingDef.secret;
import static com.concentus.config.SettingDef.number;
import static com.concentus.config.SettingDef.text;

/**
 * Everything about this installation that a person may change, and where it belongs on screen.
 *
 * <p>What is <em>not</em> here is as deliberate as what is. Four things cannot be settings, because
 * they are what has to be known before there is anywhere to keep a setting: the database this
 * table lives in ({@code PERSIST_DB_*}), the key that decrypts what is in it
 * ({@code CONCENTUS_SECRET_KEY}), the data directory and port, and the bootstrap administrator for
 * a deployment nobody sits in front of. Those stay in the environment. Everything else was an
 * environment variable only because there was nowhere better to put it.
 *
 * <p>A few more stay out for a different reason: the desktop shell computes them per launch
 * ({@code CLAUDE_COMMAND}, {@code APP_DATA_DIR}, {@code CONCENTUS_APP_VERSION}), so a stored value
 * would be a stale copy of something the process already knows.
 *
 * <p><b>An entry here is a promise that the value is actually read through {@link Settings}.</b>
 * A setting whose consumer still reads a placeholder would render a field that saves a row nothing
 * looks at — worse than no field, because it looks like it worked. The list therefore grows as
 * consumers are converted, one group at a time, rather than being written out in full first.
 */
public final class SettingsCatalog {

    private SettingsCatalog() {
    }

    public static final String GROUP_RUNS = "Runs";
    public static final String GROUP_WORKERS = "Independent workers";
    public static final String GROUP_ATTACHMENTS = "Attachments";
    public static final String GROUP_PRICING = "Pricing";
    public static final String GROUP_TELEMETRY = "Traces and metrics";
    public static final String GROUP_KNOWLEDGE = "Knowledge";

    private static final List<SettingDef> ALL = List.of(
            number("runs.max-concurrent", GROUP_RUNS, "Runs at once",
                    "How many flows may execute simultaneously. Each one costs a process and its "
                            + "context, so more than the machine can carry makes every run slower "
                            + "rather than the work faster.", true),
            number("runs.queue-capacity", GROUP_RUNS, "Queue length",
                    "How many runs may wait for a slot before new ones are refused. A refusal is an "
                            + "answer; a queue nobody will reach in time is not.", true),
            number("runs.max-retained", GROUP_RUNS, "Runs kept ready to stream",
                    "Older runs stay in the database and are still readable — this is only how many "
                            + "are held in memory for the console to attach to instantly.", true),
            number("execution.max-processes", GROUP_RUNS, "Claude processes at once (total)",
                    "The machine-wide ceiling over every claude process, whichever run or fan-out "
                            + "started it. The two limits above and below multiply — eight runs of "
                            + "four workers is thirty-two processes — and this is the cap on the "
                            + "product. A worker that does not fit waits, and its run says so. "
                            + "Applies without a restart.", true),

            number("knowledge.context-tokens", GROUP_KNOWLEDGE, "Retrieved text per source (tokens)",
                    "How much of a knowledge base may reach an agent in one run, in tokens — the "
                            + "unit a context window is measured in. Estimated, not counted: "
                            + "there is no tokeniser for every model an agent may run on, so the "
                            + "figure is within about a fifth of the truth. Retrieval finds more "
                            + "than this; the most relevant passages are kept and the agent is "
                            + "told some were left out. Raise it when answers miss context that "
                            + "was clearly in the documents; lower it when three knowledge "
                            + "sources crowd out the agent's own instructions. 0 leaves the "
                            + "character ceiling below in charge.", false),
            number("knowledge.context-chars", GROUP_KNOWLEDGE, "Retrieved text per source (characters)",
                    "The same ceiling in characters (roughly four per token), used only while "
                            + "the token budget above is 0 — which is how every installation "
                            + "started, so nothing changes until somebody sets tokens.", false),
            text("knowledge.ocr-languages", GROUP_KNOWLEDGE, "OCR languages",
                    "Which languages Tesseract reads scanned PDFs and images in, joined with +, "
                            + "as its language codes: eng, spa, cat, fra, deu… Only the packs "
                            + "installed with Tesseract are used; the log names any that are "
                            + "missing and where to get them.", false),
            number("knowledge.ocr-max-pages", GROUP_KNOWLEDGE, "OCR pages per PDF",
                    "How many pages of a scanned PDF are read before the ingest stops and says "
                            + "so. Each page is a few seconds of OCR, so a three-hundred-page scan "
                            + "would otherwise hold an ingest for minutes.", false),

            text("pricing.input-usd-per-mtok", GROUP_PRICING, "Default input price",
                    "What a model with no price of its own is assumed to cost, in dollars per "
                            + "million tokens read. Used to price a run after the fact; it does not "
                            + "change what anything is billed.", true),
            text("pricing.output-usd-per-mtok", GROUP_PRICING, "Default output price",
                    "The same, for what the model writes.", true),
            text("pricing.models", GROUP_PRICING, "Model prices",
                    "What each model costs, one per line as model=input/output in dollars per "
                            + "million tokens. Anything not listed uses the two defaults above.",
                    true),

            number("workers.max-concurrent", GROUP_WORKERS, "Workers at once",
                    "The width of a fan-out: how many independent agents may run in parallel for "
                            + "one block. Each is a process of its own, so this is a limit on the "
                            + "machine rather than on the work.", true),
            number("workers.timeout-seconds", GROUP_WORKERS, "Worker timeout",
                    "How long one worker may take before it is stopped and reported as failed.",
                    true),
            number("workers.retries", GROUP_WORKERS, "Retries per worker",
                    "How many times a worker that failed is started again before the fan-out gives "
                            + "up on that item.", true),
            choice("local.permission-mode", GROUP_WORKERS, "What agents may do unasked",
                    "The Claude CLI's permission mode. \"bypassPermissions\" is what an unattended "
                            + "run needs, because \"default\" stops to ask and there is nobody there "
                            + "to answer.", true,
                    "bypassPermissions", "acceptEdits", "default", "plan"),

            number("integration.attachments.max-bytes", GROUP_ATTACHMENTS, "Largest file",
                    "In bytes. A file bigger than this is refused with its size, rather than "
                            + "filling a context window with something nobody will read.", true),
            number("integration.attachments.max-total-bytes", GROUP_ATTACHMENTS, "Largest message",
                    "In bytes, across every attachment on one message.", true),
            number("integration.attachments.max-count", GROUP_ATTACHMENTS, "Most files per message",
                    "How many attachments one message may carry.", true),

            flag("management.otlp.tracing.export.enabled", GROUP_TELEMETRY, "Send traces",
                    "Whether anything leaves this machine. Off, the spans are still created — a run "
                            + "behaves identically either way — and simply go nowhere.", true),
            text("management.otlp.tracing.endpoint", GROUP_TELEMETRY, "Collector address",
                    "Where to send them, as an OTLP HTTP endpoint — an OpenTelemetry Collector, "
                            + "Tempo, Jaeger, Honeycomb. Read only when the switch above is on.",
                    true),
            secret("management.otlp.tracing.headers.authorization", GROUP_TELEMETRY,
                    "Collector authorization header",
                    "For a hosted collector that wants one. Sent as the Authorization header on "
                            + "every export.", true),
            text("management.tracing.sampling.probability", GROUP_TELEMETRY, "Fraction traced",
                    "1.0 traces every run, which is right for a handful a day. A busy deployment "
                            + "wants less: one run's trace carries every model and tool call "
                            + "underneath it.", true),
            flag("management.otlp.metrics.export.enabled", GROUP_TELEMETRY, "Send metrics too",
                    "Counters and timers — runs by outcome, workers, tool calls — alongside the "
                            + "traces.", true)
    );

    public static List<SettingDef> all() {
        return ALL;
    }

    public static java.util.Optional<SettingDef> byKey(String key) {
        return ALL.stream().filter(d -> d.key().equals(key)).findFirst();
    }

    /** Whether this key is one the settings API will accept at all. */
    public static boolean isKnown(String key) {
        return byKey(key).isPresent();
    }
}
