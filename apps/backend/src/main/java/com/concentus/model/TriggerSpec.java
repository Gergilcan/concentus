package com.concentus.model;

import static com.concentus.support.MapValues.bool;
import static com.concentus.support.MapValues.lng;
import static com.concentus.support.MapValues.str;

import java.util.Map;
import java.util.Set;

/**
 * How a flow starts, read from its {@code input} node. One flow has at most one input node.
 *
 * <ul>
 *   <li>{@code manual}  — run starts idle; the user sends the first message.</li>
 *   <li>{@code prompt}  — the run auto-starts with {@link #prompt} as the initial input.</li>
 *   <li>{@code cron}    — as {@code prompt}, and also runs automatically on {@link #cron}.</li>
 *   <li>{@code webhook} — an external POST (e.g. a Linear event) starts a run; the payload is the
 *       input, prefixed by {@link #prompt}. Authenticated with {@link #secret}, presented in the
 *       request parameter named {@link #authParam}.</li>
 *   <li>{@code watch}   — files appearing or changing under {@link #watchPath} start a run, with
 *       the changed paths as the input, prefixed by {@link #prompt}. Polled, and debounced by
 *       {@link #watchDebounceSeconds} so a batch of files arriving together is one run.</li>
 * </ul>
 *
 * <p>Independently of the mode, a flow may be {@link #published}: then a POST bearing
 * {@link #publishToken} starts a run through {@code /api/public/flows/{id}/run}. Not a mode,
 * because it does not replace how the flow otherwise starts — a cron flow stays a cron flow, and
 * also answers the endpoint.
 */
public record TriggerSpec(String mode, String prompt, String cron, String secret, String authParam,
                          String permissionMode, boolean shadow,
                          String watchPath, String watchGlob, long watchDebounceSeconds,
                          boolean published, String publishToken) {

    /** The pre-watch shape: no folder, nothing published. */
    public TriggerSpec(String mode, String prompt, String cron, String secret, String authParam,
                       String permissionMode, boolean shadow) {
        this(mode, prompt, cron, secret, authParam, permissionMode, shadow,
                "", "", DEFAULT_WATCH_DEBOUNCE_SECONDS, false, "");
    }

    /**
     * How long a watched folder must stay quiet before its changes become a run.
     *
     * <p>Five seconds is long enough for a copy of several files to finish, and short enough
     * that a person dropping one file does not wonder whether anything noticed.
     */
    public static final long DEFAULT_WATCH_DEBOUNCE_SECONDS = 5;

    /** Used when a flow doesn't name one, so existing Linear webhooks keep working untouched. */
    public static final String DEFAULT_AUTH_PARAM = "Linear-Signature";

    /**
     * How much the {@code claude} CLI may do without asking, when a flow names it.
     *
     * <p>Blank means "use the deployment's configured default", which is what every flow saved
     * before this existed says — so nothing about them changes.
     *
     * <p>Validated against this set rather than passed through. Two reasons, and the second is the
     * important one: an unrecognised value is rejected by the CLI at launch, and a typo must never
     * be able to <em>widen</em> what a flow is allowed to do.
     */
    public static final Set<String> PERMISSION_MODES =
            Set.of("default", "acceptEdits", "bypassPermissions", "plan");

    /** A recognised mode, or blank to defer to the deployment default. */
    private static String permissionModeOf(String configured) {
        if (configured == null) return "";
        String trimmed = configured.trim();
        return PERMISSION_MODES.contains(trimmed) ? trimmed : "";
    }

    public static TriggerSpec from(FlowGraph flow) {
        String permissions = permissionModeFor(flow);
        for (FlowNode n : flow.nodesOrEmpty()) {
            if ("input".equalsIgnoreCase(n.type())) {
                Map<String, Object> d = n.dataOrEmpty();
                return new TriggerSpec(
                        str(d, "mode", "manual"),
                        str(d, "prompt", ""),
                        str(d, "cron", ""),
                        str(d, "secret", ""),
                        str(d, "authParam", DEFAULT_AUTH_PARAM),
                        permissions,
                        bool(d, "shadow", false),
                        str(d, "watchPath", "").trim(),
                        str(d, "watchGlob", "").trim(),
                        debounceOf(lng(d, "watchDebounceSeconds", DEFAULT_WATCH_DEBOUNCE_SECONDS)),
                        bool(d, "published", false),
                        str(d, "publishToken", "").trim());
            }
        }
        return new TriggerSpec("manual", "", "", "", DEFAULT_AUTH_PARAM, permissions, false);
    }

    /**
     * A usable debounce. Zero or less would fire on every tick while a copy is still in
     * progress — the exact behaviour the debounce exists to avoid — so anything below one second
     * reads as "the default" rather than as a request for that.
     */
    private static long debounceOf(long configured) {
        return configured < 1 ? DEFAULT_WATCH_DEBOUNCE_SECONDS : configured;
    }

    /**
     * The run's permission mode: the coordinator's, or the input node's for flows saved before it
     * moved there.
     *
     * <p>It is set on the coordinator because that is the node it corresponds to — a local run
     * launches one {@code claude} process for the whole flow, and {@code --permission-mode}
     * governs that process. It used to be on the input node, which read as a property of how the
     * flow starts rather than of what it may do.
     *
     * <p>The fallback is what keeps that move from being a silent widening of permissions. A flow
     * saved with {@code plan} on its trigger and nothing on its coordinator must keep planning; if
     * the old value were ignored it would quietly become the default instead, which is
     * {@code bypassPermissions} — the change nobody would notice until an agent had already done
     * something. The coordinator wins when both are set, so editing it is what takes effect.
     */
    private static String permissionModeFor(FlowGraph flow) {
        String fromInput = "";
        for (FlowNode n : flow.nodesOrEmpty()) {
            Map<String, Object> d = n.dataOrEmpty();
            // The role is on the node; canvases also write it inside the data, and older flows
            // only there. Either says lead.
            boolean lead = "coordinator".equalsIgnoreCase(n.role())
                    || "coordinator".equalsIgnoreCase(str(d, "role", ""));
            if ("agent".equalsIgnoreCase(n.type()) && lead) {
                String mode = permissionModeOf(str(d, "permissionMode", ""));
                if (!mode.isBlank()) return mode;
            } else if ("input".equalsIgnoreCase(n.type())) {
                fromInput = permissionModeOf(str(d, "permissionMode", ""));
            }
        }
        return fromInput;
    }

    /** The run should immediately fire {@link #prompt} as its first turn (Run button / prompt & cron modes). */
    public boolean autoStart() {
        return ("prompt".equalsIgnoreCase(mode) || "cron".equalsIgnoreCase(mode))
                && prompt != null && !prompt.isBlank();
    }

    /** The flow should be registered on a cron schedule. */
    public boolean scheduled() {
        return "cron".equalsIgnoreCase(mode) && cron != null && !cron.isBlank();
    }

    /** The flow is triggered by an inbound webhook. */
    public boolean webhook() {
        return "webhook".equalsIgnoreCase(mode);
    }

    /**
     * The flow is triggered by mail arriving in an IMAP folder.
     *
     * <p>The connection and match conditions are read separately by {@code MailTriggerSpec} rather
     * than being added here: mail needs a dozen fields, and folding them into this record would
     * make every other trigger mode carry them.
     */
    public boolean mail() {
        return "mail".equalsIgnoreCase(mode);
    }

    /** The flow is triggered by files appearing or changing in a host folder. */
    public boolean watch() {
        return "watch".equalsIgnoreCase(mode);
    }

    /**
     * The flow answers its public endpoint. Both halves are required: a toggle with no token
     * would be an endpoint anyone could call, and a token left behind after the toggle was turned
     * off must not keep working.
     */
    public boolean publishedWithToken() {
        return published && publishToken != null && !publishToken.isBlank();
    }
}
