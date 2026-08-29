# Optional outputs, `on rejected`, per-block error routing — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide a block's second output until turned on or wired; give the verifier an `on rejected` output that hands a full verification report to a flow; make `on error` fire per block with the block's log.

**Architecture:** Backend reads the handle a hand-off hangs from (`FlowGates.originOf`) and classifies it in `SubflowService.handOffAfter` into main / error-of-block / rejected-of-verifier, with payload builders on `AgentRun`. Frontend `NodeShell` takes a list of alt handles, each optionally gated by a node-data flag and the existence of a wire, with a chip to turn it on.

**Tech Stack:** Java 25 / Spring Boot (backend, JUnit 5 + AssertJ + Mockito), React 19 + @xyflow/react + zustand (frontend, vitest + testing-library), react-i18next.

**Spec:** `docs/superpowers/specs/2026-08-29-on-rejected-output-design.md`

## Global Constraints

- Handle names on the wire: `null` = main, `"error"`, `"else"`, `"rejected"`. Stored flows must not change meaning.
- Node-data flags: `errorOutput`, `rejectedOutput` (booleans, absent = false).
- Error payload's FIRST LINE stays exactly `"<label> failed: <error>"`.
- Gerard's rule: one commit for the whole feature at the end, on branch `feat/on-rejected-output`, no push.
- Records: never add `get*/is*` methods (Jackson turns them into properties — see FlowEdge javadoc).

---

### Task 1: `FlowEdge.REJECTED` + `FlowGates.originOf`

**Files:**
- Modify: `apps/backend/src/main/java/com/concentus/model/FlowEdge.java`
- Modify: `apps/backend/src/main/java/com/concentus/service/FlowGates.java:130-150` (replace `reachedByErrorPath`)
- Modify: `apps/backend/src/main/java/com/concentus/service/RunReplay.java:187` (caller)
- Modify: `apps/backend/src/main/java/com/concentus/service/SubflowService.java:157` (caller — temporary shim until Task 3)
- Test: `apps/backend/src/test/java/com/concentus/service/ErrorPathTest.java`

**Interfaces:**
- Produces: `public static final String FlowEdge.REJECTED = "rejected"`; `public record FlowGates.Origin(String sourceId, String handle)` with `boolean onMain()`, `boolean is(String handle)`; `public static Origin originOf(FlowGraph flow, String nodeId)`.

- [ ] Rewrite `ErrorPathTest` against `originOf`: main output → `Origin("agent", null)`; error edge → `Origin("agent","error")`; null handle → main; a condition between them keeps the block's handle and the block's id (`Origin("agent","error")` even though the direct source is `if`); a gate's own `else` edge does not count as the block's handle (`agent → if`, `if --else--> recover` ⇒ `Origin("agent", null)`); `rejected` edge; a node nothing feeds ⇒ `Origin(null, null)`.
- [ ] Run `mvn -q -pl apps/backend test -Dtest=ErrorPathTest` → compile failure.
- [ ] Implement `originOf`: same backwards walk as `reachedByErrorPath`; at the first edge whose source is not a gate return `new Origin(source.id(), e.sourceHandle() blank→null)`; through gates continue. `RunReplay` uses `originOf(flow, id)` and `origin.is(FlowEdge.ERROR)` (and later `REJECTED`); `SubflowService` uses `originOf(...).is(FlowEdge.ERROR)` for now.
- [ ] Run test → pass.

### Task 2: Per-block payloads on `AgentRun`

**Files:**
- Modify: `apps/backend/src/main/java/com/concentus/service/AgentRun.java` (near `failedNodeLabel`)
- Create: `apps/backend/src/main/java/com/concentus/service/BranchPayloads.java`
- Test: `apps/backend/src/test/java/com/concentus/service/BranchPayloadsTest.java`

**Interfaces:**
- Produces: `AgentRun.logOf(String nodeId): String` (lines `HH:mm:ss  [type]  text`, joined by `\n`, empty string when none); `AgentRun.lastVerdictSummary` (volatile String);
  `BranchPayloads.errorOf(AgentRun run, NodeExec exec)` → `"<label> failed: <error>\n\n## Log — <label>\n<log>"`;
  `BranchPayloads.verificationReport(AgentRun run, CompiledFlow compiled)` → the §4 report (rejected workers first, then accepted, then failed-without-verdict, then the verifier's log).

- [ ] Tests: `logOf` filters by agentId and formats time; `errorOf` first line equals the legacy line and contains the log; the report lists a rejected worker before an accepted one, with reason/output/log sections, the verifier's summary and log, and `Rejected 1 of 2 worker(s).`
- [ ] Run → fail. Implement. Run → pass.

### Task 3: `SubflowService.handOffAfter` — three families

**Files:**
- Modify: `apps/backend/src/main/java/com/concentus/service/SubflowService.java:136-215`
- Modify: `apps/backend/src/main/java/com/concentus/service/FanoutExecutor.java:1173` (`applyVerdict` stores `run.lastVerdictSummary = verdict.summary()`)
- Test: `apps/backend/src/test/java/com/concentus/service/SubflowServiceTest.java`

**Interfaces:**
- Consumes: `FlowGates.originOf`, `BranchPayloads.*`, `NodeExec.status/verdict`, `CompiledFlow.coordinator()/verifier()`.

- [ ] Tests (each builds a graph with `FlowNode`s `a` (coordinator), `w1`,`w2` (subagent), `v` (verifier), and a flow node hanging off a given handle of a given source):
  - error branch off `w1` fires when `w1`'s exec is failed and the run COMPLETED; not when only `w2` failed;
  - error branch off coordinator fires on a failed run with no failed exec (unattributed);
  - error payload contains `## Log — `;
  - rejected branch off `v` fires once with `# Verification report` when an exec has `verdict = "rejected"`; does not fire when all accepted; does not fire for a run without rejections even if the run failed for another reason;
  - failed run + rejected branch fired → `COMPLETED`; failed run + error wired on the wrong block → stays `ERROR`, message names the failing block;
  - existing tests keep passing (`a_failed_run_fires_the_branch_wired_to_its_error_output_and_is_handled` now needs an exec `a` marked failed, or relies on the unattributed rule — it already does, `a` is the coordinator).
- [ ] Run → fail. Implement: for each drawn after-flow compute `Origin`; `fires = origin.onMain() ? completed : origin.is(ERROR) ? blockFailed(origin.sourceId) : origin.is(REJECTED) ? anyRejected : false`; payload per family; after the loop, `if (failed && anyRecoveryFired) status = COMPLETED` with the message; if failed and nothing fired, emit `"'X' failed and nothing is wired to its error output"` (X = failedNodeLabel or "The run").
- [ ] Run whole backend suite → pass.

### Task 4: Frontend — `NodeShell` alt handles, chip, verifier handle, edge class, i18n

**Files:**
- Modify: `apps/frontend/src/flow/nodes/NodeShell.tsx`, `nodes.module.scss`
- Modify: `AgentNode.tsx`, `ApiNode.tsx`, `FlowRunNode.tsx`, `MergeNode.tsx`, `VerifierNode.tsx`, `ConditionNode.tsx`
- Modify: `apps/frontend/src/flow/FlowCanvas.tsx:64`, `canvas-overrides.css:79`
- Modify: `apps/frontend/src/api/types.ts` (`errorOutput?`, `rejectedOutput?`)
- Modify: `apps/frontend/src/i18n/es.json`, `ca.json`
- Test: `NodeShell.test.tsx`, `VerifierNode.test.tsx`

**Interfaces:**
- Produces: `export type AltHandle = { id: 'error'|'rejected'|'else'; label: string; tone: 'error'|'rejected'|'else'; optional?: { flag: 'errorOutput'|'rejectedOutput'; enabled: boolean } }`; `NodeShell` prop `altHandles?: AltHandle[]`; helper `export function optionalOutput(flag, data): AltHandle['optional']`.

- [ ] Tests: optional handle absent by default; present when `enabled`; present when an edge in the store leaves that handle; chip `+ on error` rendered for a hidden optional handle and clicking it calls `updateNodeData(id, {errorOutput: true})`; clicking a visible unwired label hides it; wired label has the "Delete the wire" title; verifier renders handles `rejected` and `error`; condition still renders `else` unconditionally.
- [ ] Run `pnpm --filter frontend test -- NodeShell VerifierNode` → fail. Implement. Pass. `pnpm --filter frontend typecheck && lint`.

### Task 5: Docs

- [ ] README bullet (line 161) rewritten per spec §5; verifier section (line 267) mentions `on rejected`; Palette verifier tooltip adds one sentence.

### Task 6: Verify and commit

- [ ] Backend: `mvn -q test` in `apps/backend` (clean test per hard lessons). Frontend: `pnpm test`, `typecheck`, `lint`.
- [ ] One commit, staged by explicit path, on `feat/on-rejected-output`. No push.
