package com.concentus.service;

import com.concentus.auth.OrgContext;
import com.concentus.llm.McpOAuthStore;
import com.concentus.model.DoctorFinding;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.LibraryAgent;
import com.concentus.model.RuntimeCheck;
import com.concentus.model.RuntimeStatus;
import com.concentus.secrets.CredentialResolver;
import com.concentus.store.AgentLibraryStore;
import com.concentus.store.RunStore;
import com.concentus.store.VariableStore;
import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FlowDoctor}: one per check, plus the two cases that matter most — a
 * healthy flow reporting nothing, and a check that cannot run staying quiet rather than guessing.
 * Every collaborator is a stub; nothing here touches a host, a CLI or a database.
 */
class FlowDoctorTest {

    private final LocalClaudeSupport claude = mock(LocalClaudeSupport.class);
    private final CredentialResolver credentials = mock(CredentialResolver.class);
    private final McpOAuthStore mcpOAuth = mock(McpOAuthStore.class);
    private final PluginRegistry plugins = mock(PluginRegistry.class);
    private final RuntimeProbe runtimes = mock(RuntimeProbe.class);
    private final RunStore runStore = mock(RunStore.class);
    private final VariableStore variables = mock(VariableStore.class);
    private final com.concentus.store.FacadeProfileStore facades =
            mock(com.concentus.store.FacadeProfileStore.class);
    private final AgentLibraryStore agentLibrary = mock(AgentLibraryStore.class);
    /** The organization's rules: a fresh mock has none, which is what a Team deployment sees. */
    private final com.concentus.policy.OrgPolicyService policies =
            mock(com.concentus.policy.OrgPolicyService.class);

    private final FlowDoctor doctor = doctorWith(new FlowCompiler());

    /** The doctor over a given compiler: the library tests need one that resolves a linked block. */
    private FlowDoctor doctorWith(FlowCompiler compiler) {
        return doctorWith(compiler, new ContextFolderResolver(""));
    }

    /** The same doctor over a different folder allowlist — what the watch checks vary on. */
    private FlowDoctor doctorWith(ContextFolderResolver contextRoots) {
        return doctorWith(new FlowCompiler(), contextRoots);
    }

    private FlowDoctor doctorWith(FlowCompiler compiler, ContextFolderResolver contextRoots) {
        return new FlowDoctor(claude, compiler, credentials, mcpOAuth, plugins, runtimes, runStore,
                variables, new OrgContext("default"), facades, agentLibrary, contextRoots, policies,
                com.concentus.config.Settings.of(Map.of()));
    }

    @BeforeEach
    void healthyMachine() {
        when(claude.available()).thenReturn(true);
        when(variables.merged(any())).thenReturn(new LinkedHashMap<>());
        when(plugins.list()).thenReturn(List.of());
        when(mcpOAuth.accessToken(anyString(), anyString())).thenReturn(Optional.of("token"));
        when(runtimes.check(anyString(), anyBoolean()))
                .thenAnswer(i -> RuntimeCheck.unmanaged(i.getArgument(0)));
        when(runStore.spendUsdSince(anyString(), anyLong())).thenReturn(0d);
    }

    // ---------------------------------------------------------------- flow builders

    private static FlowNode input(String mode, String cron) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", mode);
        data.put("cron", cron);
        return new FlowNode("in-1", "input", null, data);
    }

    private static FlowNode coordinator() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Coord");
        data.put("systemPrompt", "Do the thing.");
        return new FlowNode("a-1", "agent", "coordinator", data);
    }

    private static FlowGraph flow(List<FlowNode> nodes) {
        return new FlowGraph("f1", "Flow", "local", nodes,
                List.of(new FlowEdge("e1", "in-1", "a-1")), null, null, null, null, null);
    }

    private static FlowGraph healthy() {
        return flow(List.of(input("manual", ""), coordinator()));
    }

    private static List<DoctorFinding> ofArea(List<DoctorFinding> findings, String area) {
        return findings.stream().filter(f -> f.area().equals(area)).toList();
    }

    // ---------------------------------------------------------------- the quiet case

    @Test
    void aHealthyFlowReportsNothingAtAll() {
        assertThat(doctor.check(healthy())).isEmpty();
    }

    @Test
    void aNoteAndAFrameOnTheCanvasAreNothingToReport() {
        // Annotations carry no credential, no URL, no schedule — nothing any check could find
        // wanting. A doctor that flagged "unknown node type" here would nag on every flow
        // somebody bothered to document.
        FlowGraph annotated = flow(List.of(input("manual", ""), coordinator(),
                new FlowNode("n1", "note", null, Map.of("text", "Ask ops before changing", "color", "pink")),
                new FlowNode("g1", "group", null, Map.of("label", "Triage", "color", "green",
                        "_size", Map.of("w", 480, "h", 260)))));

        assertThat(doctor.check(annotated)).isEmpty();
    }

    // ---------------------------------------------------------------- the machine

    @Test
    void aMachineWithoutASignedInCliIsAnErrorForALocalFlow() {
        when(claude.available()).thenReturn(false);

        List<DoctorFinding> cli = ofArea(doctor.check(healthy()), "cli");

        assertThat(cli).hasSize(1);
        assertThat(cli.get(0).level()).isEqualTo("error");
        assertThat(cli.get(0).fix()).contains("sign in");
    }

    // ---------------------------------------------------------------- the graph

    @Test
    void aGraphTheRunnerWouldRefuseIsReportedAsSuchWithTheCompilersOwnReason() {
        // Two coordinators: the exact failure a run would hit, said before the run instead.
        FlowGraph broken = flow(List.of(input("manual", ""), coordinator(),
                new FlowNode("a-2", "agent", "coordinator", Map.of("name", "Second"))));

        List<DoctorFinding> graph = ofArea(doctor.check(broken), "graph");

        assertThat(graph).hasSize(1);
        assertThat(graph.get(0).level()).isEqualTo("error");
    }

    @Test
    void aVariableNothingDefinesIsAWarningBecauseTheRunStillStarts() {
        when(variables.merged(any())).thenReturn(new LinkedHashMap<>());
        Map<String, Object> data = new HashMap<>(coordinator().dataOrEmpty());
        data.put("systemPrompt", "Invoice {{CLIENT}} today.");
        FlowGraph withHole = flow(List.of(input("manual", ""),
                new FlowNode("a-1", "agent", "coordinator", data)));

        List<DoctorFinding> vars = ofArea(doctor.check(withHole), "variables");

        assertThat(vars).hasSize(1);
        assertThat(vars.get(0).level()).isEqualTo("warn");
        assertThat(vars.get(0).message()).contains("CLIENT");
    }

    // ---------------------------------------------------------------- credentials

    @Test
    void aCredentialThatNoLongerExistsIsAnErrorNamingTheNode() {
        when(credentials.resolve("cred_gone")).thenReturn(null);
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Linear");
        data.put("url", "https://mcp.linear.app/mcp");
        data.put("credentialId", "cred_gone");
        FlowGraph withMcp = flow(List.of(input("manual", ""), coordinator(),
                new FlowNode("m-1", "mcp", null, data)));

        List<DoctorFinding> creds = ofArea(doctor.check(withMcp), "credential");

        assertThat(creds).hasSize(1);
        assertThat(creds.get(0).message()).contains("Linear").contains("cred_gone");
        assertThat(creds.get(0).where()).isEqualTo("m-1");
    }

    /**
     * A credential that exists but is sealed under a key this installation does not have. The
     * finding must say "locked" and "enter it again", not "no longer exists": the node points at
     * the right credential, and sending somebody to create another would leave the locked one
     * behind and the flow pointing at it.
     */
    @Test
    void aLockedCredentialIsAnErrorThatAsksForTheValueAgainNotForANewCredential() {
        when(credentials.resolve("cred_locked")).thenReturn(null);
        when(credentials.isLocked("cred_locked")).thenReturn(true);
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Linear");
        data.put("url", "https://mcp.linear.app/mcp");
        data.put("credentialId", "cred_locked");
        FlowGraph withMcp = flow(List.of(input("manual", ""), coordinator(),
                new FlowNode("m-1", "mcp", null, data)));

        List<DoctorFinding> creds = ofArea(doctor.check(withMcp), "credential");

        assertThat(creds).hasSize(1);
        assertThat(creds.get(0).level()).isEqualTo("error");
        assertThat(creds.get(0).message()).contains("locked").doesNotContain("no longer exists");
        assertThat(creds.get(0).fix()).contains("enter its value again");
        assertThat(creds.get(0).where()).isEqualTo("m-1");
    }

    @Test
    void aCredentialReferenceInAStdioServersEnvIsCheckedToo() {
        when(credentials.resolve("cred_gone")).thenReturn(null);
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Mailchimp");
        data.put("command", "uvx");
        data.put("env", Map.of("MAILCHIMP_API_KEY", "credential:cred_gone"));
        FlowGraph withStdio = flow(List.of(input("manual", ""), coordinator(),
                new FlowNode("m-1", "mcp", null, data)));

        List<DoctorFinding> creds = ofArea(doctor.check(withStdio), "credential");

        assertThat(creds).hasSize(1);
        assertThat(creds.get(0).message()).contains("MAILCHIMP_API_KEY");
    }

    // ---------------------------------------------------------------- mcp

    @Test
    void aRemoteServerWithNeitherTokenNorSignInIsOnlyAWarning() {
        // Plenty of servers need no auth at all; calling those broken would be wrong.
        when(mcpOAuth.accessToken(anyString(), anyString())).thenReturn(Optional.empty());
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Docs");
        data.put("url", "https://learn.microsoft.com/api/mcp");
        FlowGraph withMcp = flow(List.of(input("manual", ""), coordinator(),
                new FlowNode("m-1", "mcp", null, data)));

        List<DoctorFinding> mcp = ofArea(doctor.check(withMcp), "mcp");

        assertThat(mcp).hasSize(1);
        assertThat(mcp.get(0).level()).isEqualTo("warn");
        assertThat(mcp.get(0).fix()).contains("If it is open, nothing to do");
    }

    @Test
    void aLocalServerWhoseRuntimeIsMissingCannotBeLaunched() {
        when(runtimes.check(anyString(), anyBoolean())).thenReturn(new RuntimeCheck("uvx x",
                new RuntimeStatus("uv", "uv", false, "", "uvx servers", "https://docs"), false));
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Mailchimp");
        data.put("command", "uvx");
        FlowGraph withStdio = flow(List.of(input("manual", ""), coordinator(),
                new FlowNode("m-1", "mcp", null, data)));

        List<DoctorFinding> runtime = ofArea(doctor.check(withStdio), "runtime");

        assertThat(runtime).hasSize(1);
        assertThat(runtime.get(0).level()).isEqualTo("error");
        assertThat(runtime.get(0).message()).contains("uv is not installed");
    }

    // ---------------------------------------------------------------- plugins

    @Test
    void aPluginAnAgentUsesButNobodyInstalledIsAnError() {
        when(plugins.list()).thenReturn(List.of(new PluginRegistry.PluginInfo("other@mkt", "1", "user", true)));
        Map<String, Object> data = new HashMap<>(coordinator().dataOrEmpty());
        data.put("plugins", List.of("missing@mkt"));
        FlowGraph withPlugin = flow(List.of(input("manual", ""),
                new FlowNode("a-1", "agent", "coordinator", data)));

        List<DoctorFinding> found = ofArea(doctor.check(withPlugin), "plugin");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).contains("missing@mkt");
    }

    @Test
    void whenThePluginListItselfCannotBeReadNothingIsClaimedAboutPlugins() {
        // The CHECK failing is not evidence the plugins are missing — reporting them would send
        // someone reinstalling what they already have.
        when(plugins.list()).thenThrow(new IllegalStateException("no CLI"));
        Map<String, Object> data = new HashMap<>(coordinator().dataOrEmpty());
        data.put("plugins", List.of("something@mkt"));
        FlowGraph withPlugin = flow(List.of(input("manual", ""),
                new FlowNode("a-1", "agent", "coordinator", data)));

        assertThat(ofArea(doctor.check(withPlugin), "plugin")).isEmpty();
    }

    // ---------------------------------------------------------------- trigger

    @Test
    void aScheduleThatCannotBeParsedMeansTheFlowNeverFires() {
        FlowGraph badCron = flow(List.of(input("cron", "every tuesday-ish"), coordinator()));

        List<DoctorFinding> trigger = ofArea(doctor.check(badCron), "trigger");

        assertThat(trigger).hasSize(1);
        assertThat(trigger.get(0).level()).isEqualTo("error");
        assertThat(trigger.get(0).message()).contains("never scheduled");
    }

    @Test
    void aValidFiveFieldScheduleIsAccepted() {
        // Five fields is what the picker writes; the scheduler prefixes the seconds itself.
        assertThat(ofArea(doctor.check(flow(List.of(input("cron", "0 9 * * *"), coordinator()))), "trigger"))
                .isEmpty();
    }

    @Test
    void aPausedFlowIsWorthMentioningButIsNotBroken() {
        FlowGraph paused = new FlowGraph("f1", "Flow", "local",
                List.of(input("cron", "0 9 * * *"), coordinator()),
                List.of(new FlowEdge("e1", "in-1", "a-1")), false, null, null, null, null);

        List<DoctorFinding> trigger = ofArea(doctor.check(paused), "trigger");

        assertThat(trigger).hasSize(1);
        assertThat(trigger.get(0).level()).isEqualTo("warn");
    }

    // ---------------------------------------------------------------- folder watch

    private static FlowNode watchInput(String path) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", "watch");
        data.put("watchPath", path);
        return new FlowNode("in-1", "input", null, data);
    }

    @Test
    void aWatchWithNoFolderNeverFires() {
        List<DoctorFinding> trigger = ofArea(doctor.check(flow(List.of(watchInput(""), coordinator()))),
                "trigger");

        assertThat(trigger).hasSize(1);
        assertThat(trigger.get(0).level()).isEqualTo("error");
        assertThat(trigger.get(0).message()).contains("empty");
    }

    @Test
    void aWatchedFolderOutsideTheContextRootsIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        java.nio.file.Path allowed = java.nio.file.Files.createDirectory(tmp.resolve("allowed"));
        java.nio.file.Path elsewhere = java.nio.file.Files.createDirectory(tmp.resolve("elsewhere"));
        FlowDoctor guarded = doctorWith(new ContextFolderResolver(allowed.toString()));

        List<DoctorFinding> trigger = ofArea(
                guarded.check(flow(List.of(watchInput(elsewhere.toString()), coordinator()))), "trigger");

        assertThat(trigger).hasSize(1);
        assertThat(trigger.get(0).level()).isEqualTo("error");
        assertThat(trigger.get(0).message()).contains("outside the configured context roots");
    }

    @Test
    void aWatchedFolderThatDoesNotExistYetIsAWarningNotAnError(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        // The person is about to create it; the watcher starts on its own when they do.
        FlowDoctor guarded = doctorWith(new ContextFolderResolver(tmp.toString()));
        String notYet = tmp.resolve("incoming").toString();

        List<DoctorFinding> trigger = ofArea(
                guarded.check(flow(List.of(watchInput(notYet), coordinator()))), "trigger");

        assertThat(trigger).hasSize(1);
        assertThat(trigger.get(0).level()).isEqualTo("warn");
        assertThat(trigger.get(0).message()).contains("does not exist yet");
    }

    @Test
    void aWatchedFolderUnderTheRootsThatExistsIsFine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        java.nio.file.Path incoming = java.nio.file.Files.createDirectory(tmp.resolve("incoming"));
        FlowDoctor guarded = doctorWith(new ContextFolderResolver(tmp.toString()));

        assertThat(ofArea(guarded.check(flow(List.of(watchInput(incoming.toString()), coordinator()))),
                "trigger")).isEmpty();
    }

    // ---------------------------------------------------------------- budget

    @Test
    void aFlowAtItsCeilingSaysSoBeforeTheRunIsRefused() {
        when(runStore.spendUsdSince(anyString(), anyLong())).thenReturn(30d);
        FlowGraph budgeted = new FlowGraph("f1", "Flow", "local",
                List.of(input("manual", ""), coordinator()),
                List.of(new FlowEdge("e1", "in-1", "a-1")), null, null, null, null, 25.0);

        List<DoctorFinding> budget = ofArea(doctor.check(budgeted), "budget");

        assertThat(budget).hasSize(1);
        assertThat(budget.get(0).level()).isEqualTo("error");
        assertThat(budget.get(0).message()).contains("$30.00").contains("$25.00");
    }

    @Test
    void aFlowUnderItsCeilingSaysNothing() {
        when(runStore.spendUsdSince(anyString(), anyLong())).thenReturn(3d);
        FlowGraph budgeted = new FlowGraph("f1", "Flow", "local",
                List.of(input("manual", ""), coordinator()),
                List.of(new FlowEdge("e1", "in-1", "a-1")), null, null, null, null, 25.0);

        assertThat(ofArea(doctor.check(budgeted), "budget")).isEmpty();
    }
// ------------------------------------------------- independent workers

    /** A fan-out flow: coordinator on fanout, one worker, and whatever else is passed in. */
    private static FlowGraph fanoutFlow(List<FlowNode> extra, List<FlowEdge> extraEdges) {
        Map<String, Object> coord = new HashMap<>();
        coord.put("name", "Coord");
        coord.put("systemPrompt", "Do the thing.");
        coord.put("execution", "fanout");
        List<FlowNode> nodes = new java.util.ArrayList<>(List.of(
                input("manual", ""), new FlowNode("a-1", "agent", "coordinator", coord)));
        nodes.addAll(extra);
        List<FlowEdge> edges = new java.util.ArrayList<>(List.of(new FlowEdge("e1", "in-1", "a-1")));
        edges.addAll(extraEdges);
        return new FlowGraph("f1", "Flow", "local", nodes, edges, null, null, null, null, null);
    }

    private static FlowNode worker(String id, String name, String facadeProfileId) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("systemPrompt", "Work.");
        if (facadeProfileId != null) data.put("facadeProfileId", facadeProfileId);
        return new FlowNode(id, "agent", "subagent", data);
    }

    private static FlowNode localMcp(String id, String name) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("command", "npx");
        data.put("args", List.of("-y", "mcp-" + name));
        return new FlowNode(id, "mcp", null, data);
    }

    // The expensive one, and the reason this check exists: the run says it halfway through, in a
    // line nobody is watching for, after every worker has already been paid for.
    @Test
    void aWorkerWhoseProfileWithholdsItsLocalServersIsAnError() {
        when(facades.get("fprof_1")).thenReturn(Optional.of(new com.concentus.model.FacadeProfile(
                "fprof_1", "solo lectura", "", List.of(), true, Boolean.FALSE)));
        FlowGraph flow = fanoutFlow(
                List.of(worker("w-1", "Analista", "fprof_1"), localMcp("m-1", "google-ads")),
                List.of(new FlowEdge("e2", "a-1", "w-1"), new FlowEdge("e3", "m-1", "w-1")));

        List<DoctorFinding> found = ofArea(doctor.check(flow), "fanout");

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo("error");
            assertThat(f.message()).contains("Analista").contains("solo lectura")
                    .contains("1 local MCP server");
            assertThat(f.where()).isEqualTo("w-1");
        });
    }

    @Test
    void aWorkerWithNoProfileIsFineBecauseItReachesWhatIsWired() {
        FlowGraph flow = fanoutFlow(
                List.of(worker("w-1", "Analista", null), localMcp("m-1", "google-ads")),
                List.of(new FlowEdge("e2", "a-1", "w-1"), new FlowEdge("e3", "m-1", "w-1")));

        assertThat(ofArea(doctor.check(flow), "fanout")).isEmpty();
    }

    // Same graph, ordinary execution: every wired server reaches every agent, so none of this
    // applies and saying it anyway would be noise on the flows most people run.
    @Test
    void noneOfThisIsSaidAboutAFlowOfSubagents() {
        when(facades.get("fprof_1")).thenReturn(Optional.of(new com.concentus.model.FacadeProfile(
                "fprof_1", "solo lectura", "", List.of(), true, Boolean.FALSE)));
        FlowGraph flow = flow(List.of(input("manual", ""), coordinator(),
                worker("w-1", "Analista", "fprof_1"), localMcp("m-1", "google-ads")));

        assertThat(ofArea(doctor.check(flow), "fanout")).isEmpty();
    }

    @Test
    void aVerifierWiredToServersIsToldItWillNotGetThem() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Verificador");
        FlowGraph flow = fanoutFlow(
                List.of(new FlowNode("v-1", "verifier", null, data), localMcp("m-1", "google-ads")),
                List.of(new FlowEdge("e3", "m-1", "v-1")));

        assertThat(ofArea(doctor.check(flow), "fanout")).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo("warn");
            assertThat(f.message()).contains("does not get");
            assertThat(f.where()).isEqualTo("v-1");
        });
    }

    @Test
    void aRepositoryInAFanOutFlowIsNoLongerAFinding() {
        // Repositories are cloned into the worker they are wired to, and their changes reach the
        // merge as patches — the warning that said otherwise would now be the wrong thing to say.
        Map<String, Object> repo = new HashMap<>();
        repo.put("url", "https://github.com/example/thing");
        repo.put("provider", "github");
        FlowGraph flow = fanoutFlow(
                List.of(worker("w-1", "Analista", null), new FlowNode("r-1", "repo", null, repo)),
                List.of(new FlowEdge("e2", "a-1", "w-1"), new FlowEdge("e3", "r-1", "w-1")));

        assertThat(ofArea(doctor.check(flow), "fanout")).noneSatisfy(f ->
                assertThat(f.message()).contains("not cloned"));
    }

    // ---------------------------------------------------------------- send mail

    private static FlowGraph flowWithMail(Map<String, Object> mailData) {
        return new FlowGraph("f1", "Flow", "local",
                List.of(input("manual", ""), coordinator(), new FlowNode("mail-1", "mail", null, mailData)),
                List.of(new FlowEdge("e1", "in-1", "a-1"), new FlowEdge("e2", "a-1", "mail-1")),
                null, null, null, null, null);
    }

    @Test
    void aMailNodeWithNoRecipientAndNoCredentialIsWarnedAboutBeforeTheRun() {
        Map<String, Object> data = new HashMap<>();
        data.put("label", "Avisar");
        data.put("smtpHost", "smtp.gmail.com");
        data.put("from", "bot@gmail.com");

        List<DoctorFinding> mail = ofArea(doctor.check(flowWithMail(data)), "mail");

        // Warnings, not errors: the run starts and finishes; only the mail at the end is missing.
        assertThat(mail).hasSize(2);
        assertThat(mail).allSatisfy(f -> {
            assertThat(f.level()).isEqualTo("warn");
            assertThat(f.where()).isEqualTo("mail-1");
            assertThat(f.message()).contains("Avisar");
        });
        assertThat(mail).anySatisfy(f -> assertThat(f.message()).contains("no recipient"));
        assertThat(mail).anySatisfy(f -> assertThat(f.message()).contains("no mail account credential"));
    }

    @Test
    void aConfiguredMailNodeReportsNothing() {
        when(credentials.resolve("cred_mail")).thenReturn("pw");
        Map<String, Object> data = new HashMap<>();
        data.put("label", "Avisar");
        data.put("to", "gerard@example.com");
        data.put("smtpHost", "smtp.gmail.com");
        data.put("from", "bot@gmail.com");
        data.put("credentialId", "cred_mail");

        assertThat(doctor.check(flowWithMail(data))).isEmpty();
    }

    // ---------------------------------------------------------------- the library

    private static FlowNode linkedCoordinator(long stampedVersion) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Coord");
        data.put("systemPrompt", "Do the thing.");
        data.put("libraryAgentId", "lib-1");
        data.put("libraryVersion", stampedVersion);
        return new FlowNode("a-1", "agent", "coordinator", data);
    }

    private static LibraryAgent libraryReviewer(long version) {
        return new LibraryAgent("lib-1", "Reviewer", "claude-x", "high", 1000, "Review.", "", version);
    }

    @Test
    void aBlockStampedBehindTheLibraryAgentsVersionIsWarnedAboutOnTheBlock() {
        LibraryAgent current = libraryReviewer(4);
        when(agentLibrary.get("lib-1")).thenReturn(Optional.of(current));
        FlowDoctor doctor = doctorWith(new FlowCompiler(id -> Optional.of(current)));

        List<DoctorFinding> findings = doctor.check(flow(List.of(input("manual", ""), linkedCoordinator(2))));

        // A warning, not an error: the run works, and runs the new version — the point of a link.
        // What the person has not seen is the change, so the finding says where to look.
        assertThat(ofArea(findings, "graph")).isEmpty();
        List<DoctorFinding> library = ofArea(findings, "library");
        assertThat(library).hasSize(1);
        assertThat(library.get(0).level()).isEqualTo("warn");
        assertThat(library.get(0).where()).isEqualTo("a-1");
        assertThat(library.get(0).message()).contains("Reviewer").contains("v2 → v4");
        assertThat(library.get(0).fix()).contains("Take the current version");
    }

    @Test
    void aBlockAtTheLibraryAgentsCurrentVersionIsNothingToReport() {
        LibraryAgent current = libraryReviewer(4);
        when(agentLibrary.get("lib-1")).thenReturn(Optional.of(current));
        FlowDoctor doctor = doctorWith(new FlowCompiler(id -> Optional.of(current)));

        assertThat(doctor.check(flow(List.of(input("manual", ""), linkedCoordinator(4))))).isEmpty();
    }

    @Test
    void aLinkedBlockWhoseLibraryAgentWasDeletedIsAnErrorOnTheBlock() {
        // Neither the doctor's store nor the compiler's resolver knows lib-1 any more.
        when(agentLibrary.get("lib-1")).thenReturn(Optional.empty());

        List<DoctorFinding> findings = doctor.check(flow(List.of(input("manual", ""), linkedCoordinator(2))));

        // One finding, on the block, not a generic "does not compile" on the whole flow: the fix
        // is on the block (unlink) or under Resources → Agents, not somewhere on the canvas.
        assertThat(ofArea(findings, "graph")).isEmpty();
        List<DoctorFinding> library = ofArea(findings, "library");
        assertThat(library).hasSize(1);
        assertThat(library.get(0).level()).isEqualTo("error");
        assertThat(library.get(0).where()).isEqualTo("a-1");
        assertThat(library.get(0).message()).contains("'Coord'").contains("lib-1");
    }

    // ---------------------------------------------------------------- organization policy

    private static FlowNode remoteMcp(String id, String name) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("url", "https://" + name + ".example/mcp");
        data.put("credentialId", "cred_ok");
        return new FlowNode(id, "mcp", null, data);
    }

    /** The doctor over a compiler that applies the given policy — what a licensed deployment runs. */
    private FlowDoctor doctorUnder(com.concentus.policy.OrgPolicy policy) {
        return doctorWith(new FlowCompiler(id -> Optional.empty(), () -> policy), new ContextFolderResolver(""));
    }

    @Test
    void aWorkerRefusedByTheFacadeRuleIsAPolicyErrorOnTheBlock() {
        when(credentials.resolve("cred_ok")).thenReturn("token");
        FlowDoctor doctor = doctorUnder(new com.concentus.policy.OrgPolicy("default", "", true, "", null, false));
        FlowGraph flow = fanoutFlow(
                List.of(worker("w-1", "Analista", null), remoteMcp("m-1", "linear")),
                List.of(new FlowEdge("e2", "a-1", "w-1"), new FlowEdge("e3", "m-1", "w-1")));

        List<DoctorFinding> findings = doctor.check(flow);

        // On the block, not a generic "does not compile" on the whole flow.
        assertThat(ofArea(findings, "graph")).isEmpty();
        assertThat(ofArea(findings, "policy")).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo("error");
            assertThat(f.where()).isEqualTo("w-1");
            assertThat(f.message()).contains("organization's policy").contains("Analista");
            assertThat(f.fix()).contains("Resources → Policies");
        });
    }

    @Test
    void aWorkerFilledByTheOrganizationsDefaultProfileIsNothingToReport() {
        when(credentials.resolve("cred_ok")).thenReturn("token");
        when(facades.get("fprof_default")).thenReturn(Optional.of(new com.concentus.model.FacadeProfile(
                "fprof_default", "reader", "", List.of(), true, Boolean.FALSE)));
        FlowDoctor doctor = doctorUnder(new com.concentus.policy.OrgPolicy("default", "fprof_default", true, "", null, false));
        FlowGraph flow = fanoutFlow(
                List.of(worker("w-1", "Analista", null), remoteMcp("m-1", "linear")),
                List.of(new FlowEdge("e2", "a-1", "w-1"), new FlowEdge("e3", "m-1", "w-1")));

        assertThat(ofArea(doctor.check(flow), "policy")).isEmpty();
        assertThat(ofArea(doctor.check(flow), "graph")).isEmpty();
    }

    @Test
    void aFlowAskingForMoreThanTheCeilingIsWarnedItWillGetTheCeiling() {
        when(policies.maxPermissionMode()).thenReturn("acceptEdits");
        Map<String, Object> coord = new HashMap<>(coordinator().dataOrEmpty());
        coord.put("permissionMode", "bypassPermissions");
        FlowGraph flow = flow(List.of(input("manual", ""), new FlowNode("a-1", "agent", "coordinator", coord)));

        assertThat(ofArea(doctor.check(flow), "policy")).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo("warn");
            assertThat(f.message()).contains("'bypassPermissions'").contains("ceiling 'acceptEdits'");
        });
    }

    @Test
    void aFlowNamingNoModeIsMeasuredByTheDeploymentsDefaultWhichIsBypass() {
        when(policies.maxPermissionMode()).thenReturn("plan");

        assertThat(ofArea(doctor.check(healthy()), "policy")).singleElement().satisfies(f ->
                assertThat(f.message()).contains("names no permission mode").contains("'bypassPermissions'"));
    }

    @Test
    void aPublishedFlowWaitingForApprovalIsAPolicyErrorBecauseItsEndpointIsShut() {
        when(policies.publishBlocked("f1", "tok-1")).thenReturn(true);
        Map<String, Object> in = new HashMap<>();
        in.put("mode", "manual");
        in.put("published", true);
        in.put("publishToken", "tok-1");
        FlowGraph flow = flow(List.of(new FlowNode("in-1", "input", null, in), coordinator()));

        assertThat(ofArea(doctor.check(flow), "policy")).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo("error");
            assertThat(f.message()).contains("admin's approval").contains("404");
        });
    }

    // The gate, from the doctor's side: a Team deployment's service has no ceiling and blocks no
    // endpoint, so the same flows say nothing about policy.
    @Test
    void withNoPolicyInForceNoneOfThisIsSaid() {
        Map<String, Object> coord = new HashMap<>(coordinator().dataOrEmpty());
        coord.put("permissionMode", "bypassPermissions");
        Map<String, Object> in = new HashMap<>();
        in.put("mode", "manual");
        in.put("published", true);
        in.put("publishToken", "tok-1");
        FlowGraph flow = flow(List.of(new FlowNode("in-1", "input", null, in),
                new FlowNode("a-1", "agent", "coordinator", coord)));

        assertThat(ofArea(doctor.check(flow), "policy")).isEmpty();
    }
}
