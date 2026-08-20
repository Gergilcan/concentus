package com.concentus.config;

import java.util.List;

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
    public static final String GROUP_PRICING = "Pricing";

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

            text("pricing.input-usd-per-mtok", GROUP_PRICING, "Default input price",
                    "What a model with no price of its own is assumed to cost, in dollars per "
                            + "million tokens read. Used to price a run after the fact; it does not "
                            + "change what anything is billed.", true),
            text("pricing.output-usd-per-mtok", GROUP_PRICING, "Default output price",
                    "The same, for what the model writes.", true)
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
