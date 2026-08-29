# Optional outputs, `on rejected`, and per-block error routing — design

Date: 2026-08-29. Status: approved by Gerard (chat).

## What this is

Every executing block today draws a second output, `on error`, whether or not anybody wires it.
The verifier — the block whose whole job is to reject — has no output for the thing it does.
And an error wire, wherever it is drawn, fires when the run fails anywhere: the drawing says
"when *this* block fails" and the engine reads "when anything fails".

Three changes, one feature:

1. **Optional outputs are hidden by default.** A block shows `on error` only when the author
   turned it on (a small chip on the card) or when a wire already leaves it.
2. **The verifier gets `on rejected`.** Wired to a flow, that flow receives one verification
   report — every worker, rejected and accepted, with the verdict, the reason, the output and the
   full console log — so a rejection can be acted on (mailed, filed, retried) instead of only
   read on a box afterwards.
3. **`on error` is per block.** The branch wired to block X's error output fires when X failed,
   and is handed X's error and X's console log. A failure nobody attributed to a block fires the
   coordinator's error branch.

## Decisions taken (with Gerard, 29 Aug)

| Question | Decision |
| --- | --- |
| Where `on rejected` lives | On the Verifier, not on each worker. The verifier is the block that rejects; a worker's box already records the verdict as a second fact separate from "failed". |
| Granularity | One hand-off with one report covering every worker, rejected AND accepted, so nothing is lost. A condition gate after the handle filters on the report text. |
| Payload | Full console log of each worker and of the verifier, plus outputs and reasons. No truncation beyond what the run already keeps. |
| Toggle | A small chip on the card. On agents/APIs/sub-flows/merge it reads `+ on error`; on the verifier it reads `+ on rejected` (and `+ on error`). No inspector control. |
| Name | `on rejected` — the word the verifier, the box and the console already use. `on failed` would collide with the process-failure status the model keeps separate on purpose. |
| Per-block error routing | Yes. Behaviour change for existing flows, accepted: a wire off block X now fires only when X failed. Error payload gains X's console log. |

## 1. Data model

### Edges

`FlowEdge.sourceHandle` gains one more name: `REJECTED = "rejected"`. `null` stays the main
output; `"error"` and `"else"` are unchanged. A stored flow does not change meaning.

### Node data (free-form map, backend ignores it)

- `errorOutput?: boolean` on agent, api, flow, merge, verifier — the author turned the error
  output on. Absent or false: hidden unless wired.
- `rejectedOutput?: boolean` on verifier — same, for `on rejected`.

Condition's `else` is not optional: a gate with one visible branch is half a gate, and the
README already reads it as "one test from both sides". Untouched.

No migration. Existing error wires keep their handle visible through the "already wired" rule.

## 2. Canvas (`apps/frontend`)

### `NodeShell`

`altHandle` becomes `altHandles: AltHandle[]` where

```ts
type AltHandle = {
  id: 'error' | 'rejected' | 'else'
  label: string
  tone: 'error' | 'rejected' | 'else'
  /** When set, the handle is optional and this is the node-data flag that turns it on. */
  optional?: { flag: 'errorOutput' | 'rejectedOutput'; enabled: boolean }
}
```

Handles stack from the bottom edge of the card upwards in array order (the first entry sits
lowest, at `calc(100% - 14px)`, the next 16px above it), each with its label beside it. The
card reserves bottom padding for as many rows as are visible.

Visibility rule for an optional handle, evaluated in the node component:

```
visible = enabled || edges.some(e => e.source === id && e.sourceHandle === handle.id)
```

The edges come from the flow store (`useFlowStore(s => s.edges)`), which is what the canvas
saves, so a wire never loses its dot.

### The chip

- Hidden optional handle + node hovered or selected → a chip `+ on error` / `+ on rejected` at
  the bottom-right of the card, in the handle's tone, `nodrag nopan`. Click → `updateNodeData(id,
  { [flag]: true })`. Two hidden handles → two chips side by side.
- Visible optional handle, **unwired** → its label is the chip in reverse: click → flag false,
  handle disappears. Tooltip: "Hide this output".
- Visible optional handle, **wired** → label is inert, tooltip "Delete the wire to hide this
  output".

Chips are absolutely positioned so they cause no layout shift; they show at opacity 0 until
hover/selected so the card stays as quiet as it is today.

### Verifier card

`altHandles: [rejected, error]` — `on rejected` lowest (it is the verifier's real second
output), `on error` above it. Tone `rejected` is drawn in the warning colour (`t.$warn` or the
nearest existing token), distinct from `error`'s danger red and `else`'s muted grey.

### Edges

`FlowCanvas` classes an edge leaving `rejected` as `edge-rejected`, styled like `edge-error` in
the warning colour. `wiring.ts` needs no change: connectability is by node kind, and a rejected
wire may reach exactly what an error wire may (a flow, or a gate on the way to one).

### i18n

New keys: `"on rejected"` → es `"si rechaza"`, ca `"si rebutja"`; `"+ on error"` / `"+ on
rejected"` chips reuse the same words with the plus; tooltips above.

## 3. Engine (`apps/backend`)

### `FlowGates`

`reachedByErrorPath` is replaced by

```java
/** Which output of which block a hand-off hangs from, looking through the gates in between. */
record Origin(String sourceId, String handle) {}   // handle null = main output
static Origin originOf(FlowGraph flow, String nodeId)
```

Walk backwards from the hand-off exactly as `reachedByErrorPath` does today; the first edge
whose source is not a gate answers. Gate edges' own `else` handle is ignored on the walk (it is
the gate's answer, not the block's output). No incoming edge → `Origin(null, null)`.

### `SubflowService.handOffAfter(run, graph)`

For each drawn after-flow, `originOf` says which of three families it belongs to:

| Family | Fires when | Handed |
| --- | --- | --- |
| main (`null`) | run status is `COMPLETED` | `run.finalOutput()` — unchanged |
| `error` off block X | X's `NodeExec.status == "failed"`; or X is the coordinator and the run failed with no block marked failed | `"<X> failed: <error>"` + `## Log` of X (see §4) |
| `rejected` off the verifier | at least one `NodeExec.verdict == "rejected"` in the run | the verification report (§4) |

Each candidate then goes through `FlowGates.decide` as today, so conditions and for-eachs on
the way still apply.

Status rule, generalised from today's: the run failed **and** at least one error or rejected
branch fired → status `COMPLETED`, with the existing "handled by the branch wired to the error
output" line (worded for whichever fired). Failed and nothing fired → stays `ERROR`, message now
names the block: "‘X’ failed and nothing is wired to its error output".

An error branch fires regardless of run status — a worker that crashed in a fan-out whose other
workers carried the run to `COMPLETED` still fires its own branch. That is the point of
per-block routing.

`handOffsFired` keeps guarding double firing. Without a graph (`handOffAfter(run)`) behaviour is
unchanged: every hand-off is main.

### Verifier bookkeeping

`AgentRun` keeps the last accepted verdict's summary (`lastVerdictSummary`) so the report can
open with the verifier's own one-liner; `FanoutExecutor.applyVerdict` stores it.

## 4. Payloads

### Console log of a block

`AgentRun.logOf(nodeId)`: the buffered events whose `agentId` equals the node id, rendered one
per line as `HH:MM:SS  [type]  text`. Honest limit, stated in the spec and nowhere hidden: the
run keeps its last 4 000 events in memory, so a very long run's earliest lines are gone before
the branch fires. That is the same log the box's Logs tab shows.

### Error payload

```
<X label> failed: <X's error, or the run's error when the box has none>

## Log — <X label>
<log lines>
```

The first line is byte-for-byte what the branch receives today, so a condition drawn on it
keeps matching.

### Verification report

```
# Verification report — <flow name>
<verifier summary line>
Rejected <n> of <m> worker(s).

## ✖ <worker> — REJECTED
Reason: <verdictReason>   (includes the escalation note when a retry happened)
### Output
<worker output, or "(no output)">
### Log
<log lines>

## ✔ <worker> — accepted
### Output
...
### Log
...

## Verifier log — <verifier name>
<log lines>
```

Rejected workers first, then accepted, each in canvas order. Workers whose process failed (no
verdict) are listed under a third heading `## ✖ <worker> — failed` with their error and log, so
the report is complete.

## 5. Docs

README "Every executing block has two outputs" bullet rewritten: outputs are optional and
hidden until turned on or wired; `on error` is per block and carries the block's log; the
verifier's `on rejected` and its report. Palette tooltip for the verifier mentions the output.

## 6. Tests

Backend (`FlowGatesTest`, `SubflowServiceTest`, `FanoutExecutorTest` where the summary is kept):

- `originOf` through a condition and a for-each; ignores the gate's `else`; `rejected` handle.
- Rejected branch fires once with the report when one worker was rejected, not when none was,
  not on a run that never had a verifier; the report lists rejected first, accepted second,
  with reasons, outputs and log lines.
- Error branch off worker A fires when A failed and B did not, and not the other way round;
  fires even when the run completed; unattributed failure fires the coordinator's branch.
- Error payload starts with the same first line as before and contains the block's log.
- Failed run + branch fired → `COMPLETED`; failed run + nothing wired → `ERROR` with the block's
  name in the message.

Frontend (vitest): `NodeShell` — optional handle hidden by default, shown when enabled, shown
when wired, chip appears and toggles the flag, wired label is inert; `VerifierNode` — renders
`rejected` and `error` handle ids; `FlowCanvas` edge class for `rejected`.

## Out of scope

- `on rejected` on individual workers.
- Firing the rejected branch mid-run (before the merge). Hand-offs fire when the run is over;
  one mechanism.
- Any inspector control for outputs.
