package com.concentus.service;

import com.concentus.auth.OrgContext;
import com.concentus.llm.McpOAuthStore;
import com.concentus.model.DoctorFinding;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.secrets.CredentialResolver;
import com.concentus.store.AgentLibraryStore;
import com.concentus.store.RunStore;
import com.concentus.store.VariableStore;
import com.concentus.support.LocalClaudeSupport;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything that would fail at run time, found before the run.
 *
 * <p>The individual checks all existed already — scattered across the code that hits them, each
 * one surfacing as its own failure minutes into a run, in the wording of whatever component threw.
 * This asks all of them at once, up front, and phrases each answer as something to DO.
 *
 * <p>It never blocks a run. A flow with findings still runs, because some of them are guesses (an
 * MCP server that needs no auth, a command this app does not manage) and a doctor that refuses to
 * let you try would be worse than the failure it predicted. Read-only and fast by construction:
 * nothing here spawns an agent, and the two probes that touch the host — the CLI check and the
 * runtime check — are both cached.
 */
@Service
public class FlowDoctor {

    private final LocalClaudeSupport claude;
    private final FlowCompiler compiler;
    private final CredentialResolver credentials;
    private final McpOAuthStore mcpOAuth;
    private final PluginRegistry plugins;
    private final RuntimeProbe runtimes;
    private final RunStore runStore;
    private final VariableStore variables;
    private final OrgContext orgContext;
    /** Resolves the profile a worker names, to say what it will withhold. */
    private final com.concentus.store.FacadeProfileStore facades;
    /** Resolves the library agent a linked block names, to say whether it moved on. */
    private final AgentLibraryStore agentLibrary;

    public FlowDoctor(LocalClaudeSupport claude, FlowCompiler compiler, CredentialResolver credentials,
                      McpOAuthStore mcpOAuth, PluginRegistry plugins, RuntimeProbe runtimes,
                      RunStore runStore, VariableStore variables, OrgContext orgContext,
                      com.concentus.store.FacadeProfileStore facades, AgentLibraryStore agentLibrary) {
        this.claude = claude;
        this.compiler = compiler;
        this.credentials = credentials;
        this.mcpOAuth = mcpOAuth;
        this.plugins = plugins;
        this.runtimes = runtimes;
        this.runStore = runStore;
        this.variables = variables;
        this.orgContext = orgContext;
        this.facades = facades;
        this.agentLibrary = agentLibrary;
    }

    /** Everything worth knowing before pressing Run. An empty list means "nothing found". */
    public List<DoctorFinding> check(FlowGraph flow) {
        List<DoctorFinding> findings = new ArrayList<>();
        checkCli(flow, findings);
        checkGraph(flow, findings);
        checkNodes(flow, findings);
        checkTrigger(flow, findings);
        checkBudget(flow, findings);
        return findings;
    }

    // ---------------------------------------------------------------- the machine

    private void checkCli(FlowGraph flow, List<DoctorFinding> findings) {
        if (claude.available()) return;
        boolean local = "local".equals(flow.modeOrDefault());
        findings.add(new DoctorFinding(local ? "error" : "warn", "cli",
                "Claude Code has no sign-in on this machine.",
                "Run `claude` in a terminal and sign in — the first-run wizard can install it for you.",
                null));
    }

    // ---------------------------------------------------------------- the graph

    /**
     * The compiler's own validation, asked early. It is the same check the run does, so a flow
     * that passes here cannot fail to start for a reason this page could have named.
     */
    private void checkGraph(FlowGraph flow, List<DoctorFinding> findings) {
        Set<String> unresolved = new LinkedHashSet<>();
        try {
            checkFanout(compiler.compile(flow, variables.merged(flow.variables()), unresolved),
                    findings);
        } catch (FlowCompiler.MissingLibraryAgent e) {
            // The compiler's own message, pointed at the block: the fix is on the block (unlink)
            // or under Resources → Agents, and "fix the flow on the canvas" would send someone
            // looking for a wire.
            findings.add(DoctorFinding.error("library", e.getMessage(),
                    "Open the block and press \"Unlink (keep a copy)\", or link it to an agent "
                            + "that exists — a run would refuse to start with this.", e.nodeId()));
            return;
        } catch (RuntimeException e) {
            findings.add(DoctorFinding.error("graph",
                    e.getMessage() == null ? "This flow does not compile." : e.getMessage(),
                    "Fix the flow on the canvas — a run would refuse to start with this.", null));
            return;
        }
        for (String name : unresolved) {
            // Not an error: the run starts, the agent just receives a prompt with a hole in it.
            findings.add(DoctorFinding.warn("variables",
                    "No value for {{" + name + "}} — prompts using it keep the placeholder.",
                    "Set it in the flow's settings, or under Resources → Variables.", null));
        }
    }

    // ------------------------------------------------------- independent workers

    /**
     * What a flow running as independent workers gets that a flow of sub-agents does not — and
     * what it silently does without.
     *
     * <p>These are the facts a run only tells you halfway through, in a line nobody is watching
     * for, and one of them is expensive: a worker that reaches none of its servers spends its
     * whole turn discovering that, on every model in the flow, and reports the source as
     * unreachable rather than as unconfigured.
     *
     * <p>Only for fan-out. On one shared session every wired server reaches every agent, so none
     * of this applies and saying it anyway would be noise on the flows most people run.
     */
    private void checkFanout(CompiledFlow compiled, List<DoctorFinding> findings) {
        if (!compiled.fanout()) return;

        for (com.concentus.config.AgentSpec worker : compiled.subAgents()) {
            checkWorkerReach(worker, findings);
        }
        if (compiled.merger() != null) checkWorkerReach(compiled.merger(), findings);

        com.concentus.config.AgentSpec verifier = compiled.verifier();
        if (verifier != null && !verifier.mcpServers.isEmpty()) {
            findings.add(DoctorFinding.warn("fanout",
                    "The verifier is wired to " + verifier.mcpServers.size() + " MCP server(s), "
                            + "which it does not get: it judges what the workers produced and "
                            + "submits verdicts, and nothing else.",
                    "Wire those servers to the workers or to the merge step instead.",
                    verifier.nodeId));
        }

    }

    /** Whether this worker's profile leaves it able to reach the servers drawn onto its node. */
    private void checkWorkerReach(com.concentus.config.AgentSpec spec, List<DoctorFinding> findings) {
        if (spec.mcpServers.isEmpty()) return;
        String profileId = spec.facadeProfileId;
        if (profileId == null || profileId.isBlank()) return;

        var profile = facades.get(profileId);
        if (profile.isEmpty()) {
            findings.add(DoctorFinding.warn("fanout",
                    "\"" + spec.name + "\" names a facade profile that no longer exists, so "
                            + "nothing filters what it reaches.",
                    "Pick a profile under Resources → Facades, or clear the field.", spec.nodeId));
            return;
        }
        if (!profile.get().withholdsAnything()) return;

        long local = spec.mcpServers.stream()
                .filter(com.concentus.config.AgentSpec.McpServerSpec::isStdio).count();
        if (local == 0) return;
        // The expensive one. A profile is enforced at the facade, and a local server never passes
        // through it — so a restricted worker is given none of them, and finds out by looking for
        // tools that are not there.
        findings.add(DoctorFinding.error("fanout",
                "\"" + spec.name + "\" runs behind the profile \"" + profile.get().name()
                        + "\", which withholds its " + local + " local MCP server(s) entirely: a "
                        + "facade cannot enforce a rule on a server it does not proxy.",
                "Clear the profile on that node to let the worker launch them, or move that work "
                        + "to a remote server.", spec.nodeId));
    }

    // ---------------------------------------------------------------- the nodes

    private void checkNodes(FlowGraph flow, List<DoctorFinding> findings) {
        Set<String> installedPlugins = installedPluginIds();
        for (FlowNode node : flow.nodesOrEmpty()) {
            Map<String, Object> data = node.dataOrEmpty();
            checkCredential(node, text(data, "credentialId"), findings);
            switch (node.type() == null ? "" : node.type()) {
                case "mcp" -> checkMcpNode(node, data, findings);
                case "agent" -> checkAgentNode(node, data, installedPlugins, findings);
                case "mail" -> checkMailNode(node, findings);
                default -> {
                    // Every other node type's credential is already covered above.
                }
            }
        }
    }

    /** A credential id that names nothing: the classic after a machine change or an import. */
    private void checkCredential(FlowNode node, String credentialId, List<DoctorFinding> findings) {
        if (credentialId.isBlank() || credentials.resolve(credentialId) != null) return;
        findings.add(DoctorFinding.error("credential",
                "\"" + label(node) + "\" points at a credential that no longer exists (" + credentialId + ").",
                "Create it under Resources → Credentials and pick it on the node.", node.id()));
    }

    private void checkMcpNode(FlowNode node, Map<String, Object> data, List<DoctorFinding> findings) {
        String command = text(data, "command");
        if (!command.isBlank()) {
            checkStdioMcp(node, data, command, findings);
            return;
        }
        String url = text(data, "url");
        if (url.isBlank()) {
            findings.add(DoctorFinding.error("mcp",
                    "\"" + label(node) + "\" has neither a URL nor a command.",
                    "Give it a server URL, or a command to launch it with.", node.id()));
            return;
        }
        if (!text(data, "credentialId").isBlank()) return;
        if (mcpOAuth.accessToken(organizationId(), url).isPresent()) return;
        // A warning, not an error: plenty of servers need no auth at all (a local Figma bridge,
        // a public docs server), and calling those broken would be wrong.
        findings.add(DoctorFinding.warn("mcp",
                "\"" + label(node) + "\" has no stored token and no OAuth sign-in.",
                "If this server needs auth, press \"Sign in to this server\" on the node or set a "
                        + "credential. If it is open, nothing to do.", node.id()));
    }

    /** A local MCP server: it must be launchable, and its credential: references must resolve. */
    private void checkStdioMcp(FlowNode node, Map<String, Object> data, String command,
                               List<DoctorFinding> findings) {
        var check = runtimes.check(command, false);
        if (!check.satisfied() && check.runtime() != null) {
            findings.add(DoctorFinding.error("runtime",
                    check.runtime().label() + " is not installed, so \"" + label(node)
                            + "\" cannot be launched.",
                    "Install it from the MCP server's panel — the button runs the official installer.",
                    node.id()));
        }
        if (data.get("env") instanceof Map<?, ?> env) {
            for (Map.Entry<?, ?> entry : env.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue().toString();
                if (!value.startsWith("credential:")) continue;
                String id = value.substring("credential:".length());
                if (credentials.resolve(id) != null) continue;
                findings.add(DoctorFinding.error("credential",
                        "\"" + label(node) + "\" reads " + entry.getKey()
                                + " from a credential that no longer exists (" + id + ").",
                        "Create it under Resources → Credentials, or change the value.", node.id()));
            }
        }
    }

    /**
     * A Send mail node that could not send. Warnings rather than errors: the run itself starts and
     * finishes fine, it is the mail at the end that quietly does not go out — which is exactly the
     * kind of thing to hear about before the run rather than from an empty inbox afterwards.
     */
    private static void checkMailNode(FlowNode node, List<DoctorFinding> findings) {
        com.concentus.mail.MailHandOffSpec spec = com.concentus.mail.MailHandOffSpec.from(node);
        if (spec.to().isBlank()) {
            findings.add(DoctorFinding.warn("mail",
                    "\"" + label(node) + "\" has no recipient, so it sends nothing.",
                    "Type one or more addresses in the node's To field.", node.id()));
        }
        if (spec.credentialId().isBlank()) {
            findings.add(DoctorFinding.warn("mail",
                    "\"" + label(node) + "\" has no mail account credential, so it cannot sign in to send.",
                    "Store the mailbox password under Resources → Credentials and pick it on the node.",
                    node.id()));
        }
        if (spec.smtpHost().isBlank()) {
            findings.add(DoctorFinding.warn("mail",
                    "\"" + label(node) + "\" has no SMTP host, so it has no server to send through.",
                    "Fill in your provider's SMTP host on the node (Gmail: smtp.gmail.com · "
                            + "Microsoft 365: smtp.office365.com).", node.id()));
        }
        if (spec.sender().isBlank()) {
            findings.add(DoctorFinding.warn("mail",
                    "\"" + label(node) + "\" has no From address and no username, so there is nobody to send as.",
                    "Fill in the mailbox address on the node.", node.id()));
        }
    }

    private void checkAgentNode(FlowNode node, Map<String, Object> data, Set<String> installed,
                                List<DoctorFinding> findings) {
        checkLibraryLink(node, data, findings);
        // Null means the CLI could not be asked. Reporting every selected plugin as missing when
        // the CHECK is what failed would send someone reinstalling things they already have.
        if (installed == null) return;
        if (!(data.get("plugins") instanceof List<?> selected)) return;
        for (Object id : selected) {
            String pluginId = id == null ? "" : id.toString();
            if (pluginId.isBlank() || installed.contains(pluginId)) continue;
            findings.add(DoctorFinding.error("plugin",
                    "\"" + label(node) + "\" uses the plugin " + pluginId + ", which is not installed.",
                    "Install it under Resources → Plugins, or clear it on the agent.", node.id()));
        }
    }

    /**
     * A linked block whose library agent moved on since the block last took it.
     *
     * <p>A warning, not an error: the run works, and it runs the NEW version — a link exists so
     * that it does. What the person has not seen is the change, and the block's own fields still
     * show the old copy, so this says where to look. A missing agent is not reported here — the
     * compiler refuses it with the block named, and {@link #checkGraph} places that finding.
     */
    private void checkLibraryLink(FlowNode node, Map<String, Object> data, List<DoctorFinding> findings) {
        String id = text(data, "libraryAgentId");
        if (id.isBlank()) return;
        var current = agentLibrary.get(id);
        if (current.isEmpty()) return;
        long stamped = com.concentus.support.MapValues.lng(data, "libraryVersion", 0);
        if (stamped >= current.get().version()) return;
        findings.add(DoctorFinding.warn("library",
                "\"" + label(node) + "\" links the library agent \"" + current.get().name()
                        + "\", which changed since this block linked it (v" + stamped + " → v"
                        + current.get().version() + "). The next run uses the new version.",
                "Open the block to see the diff and press \"Take the current version\" to re-stamp it.",
                node.id()));
    }

    /**
     * Installed plugin ids, or null-as-empty when the CLI could not be asked. An empty set would
     * report every selected plugin as missing, which is the opposite of helpful on a machine where
     * the check itself is what failed.
     */
    private Set<String> installedPluginIds() {
        try {
            Set<String> ids = new LinkedHashSet<>();
            plugins.list().forEach(p -> ids.add(p.id()));
            return ids;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the trigger

    private void checkTrigger(FlowGraph flow, List<DoctorFinding> findings) {
        for (FlowNode node : flow.nodesOrEmpty()) {
            if (!"input".equals(node.type())) continue;
            Map<String, Object> data = node.dataOrEmpty();
            if (!"cron".equals(text(data, "mode"))) continue;
            String cron = text(data, "cron");
            if (cron.isBlank()) {
                findings.add(DoctorFinding.error("trigger", "The schedule is empty.",
                        "Set one on the Input node — it never fires as it is.", node.id()));
                continue;
            }
            try {
                new CronTrigger(ScheduleService.normalize(cron));
            } catch (RuntimeException e) {
                findings.add(DoctorFinding.error("trigger",
                        "\"" + cron + "\" is not a valid schedule, so this flow is never scheduled.",
                        "Rebuild it with the schedule picker on the Input node.", node.id()));
            }
        }
        if (!flow.enabledOrDefault()) {
            findings.add(DoctorFinding.warn("trigger", "This flow is paused.",
                    "Scheduled runs are off until it is enabled again; manual runs still work.", null));
        }
    }

    // ---------------------------------------------------------------- the budget

    private void checkBudget(FlowGraph flow, List<DoctorFinding> findings) {
        if (flow.id() == null || flow.budgetUsd() == null || flow.budgetUsd() <= 0) return;
        long monthStart = java.time.LocalDate.now().withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        double spent = runStore.spendUsdSince(flow.id(), monthStart);
        if (spent < flow.budgetUsd()) return;
        // Which runs it stops depends on where they execute, and that is decided per run — so the
        // finding says both halves rather than promising a refusal this flow may never meet.
        findings.add(DoctorFinding.error("budget",
                String.format(java.util.Locale.ROOT,
                        "This month's spend ($%.2f) has reached the $%.2f ceiling, so new runs on "
                                + "an API key are refused. Runs on your Claude subscription or on "
                                + "a self-hosted model are not: there is no bill for the ceiling "
                                + "to protect.",
                        spent, flow.budgetUsd()),
                "Raise the budget in the flow's settings, or wait for next month.", null));
    }

    // ---------------------------------------------------------------- helpers

    /** The default organization: a doctor request has a principal, but node data is not tenanted. */
    private String organizationId() {
        try {
            return orgContext.requireOrganizationId();
        } catch (RuntimeException e) {
            return orgContext.defaultOrganizationId();
        }
    }

    private static String label(FlowNode node) {
        Map<String, Object> data = node.dataOrEmpty();
        String name = text(data, "name");
        if (!name.isBlank()) return name;
        String labelled = text(data, "label");
        return labelled.isBlank() ? String.valueOf(node.id()) : labelled;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
