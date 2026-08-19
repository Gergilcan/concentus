import { useFlowStore } from '../state/store.ts'
import { cx } from '../utils/cx.ts'
import styles from './panels.module.scss'

/** Carries the node kind to the canvas. A private type, so a drag from elsewhere is ignored. */
export const NODE_DRAG_TYPE = 'application/concentus-node'

export function Palette() {
  const addNode = useFlowStore((s) => s.addNode)
  // Dragging places the node where it is dropped; clicking still adds one, because a drag is not
  // available to keyboard users and losing that would be a regression for them.
  const drag = (kind: string) => ({
    draggable: true,
    onDragStart: (e: React.DragEvent) => {
      e.dataTransfer.setData(NODE_DRAG_TYPE, kind)
      e.dataTransfer.effectAllowed = 'copy'
    },
  })
  return (
    <aside className={styles.palette}>
      <h3 className={styles.h3}>Add node</h3>
      <button className={cx(styles.addBtn, styles.addInput)} title="How a run starts: manual, a fixed prompt, cron, webhook, or incoming mail." {...drag('input')} onClick={() => addNode('input')}>
        <span>▶</span> Input / trigger
      </button>
      <button className={cx(styles.addBtn, styles.addAgent)} title="Mark exactly one agent as coordinator and link it to the sub-agents it may delegate to." {...drag('agent')} onClick={() => addNode('agent')}>
        <span>◆</span> Agent
      </button>
      <button className={cx(styles.addBtn, styles.addVerifier)} title="For flows running as independent workers: an adversarial judge that runs after every worker and BEFORE the merge. Its objective is inverted — find the reason each output should be REJECTED — and its verdict has teeth: a rejected output never reaches the merge. Reads the workers' files, cannot edit or run commands. At most one per flow; ignored on subagents execution." {...drag('verifier')} onClick={() => addNode('verifier')}>
        <span>⚖</span> Verifier
      </button>
      <button className={cx(styles.addBtn, styles.addMerge)} title="For flows running as independent workers: one more claude process that runs AFTER every worker, reconciles their reports into the final answer, and runs the verification commands (tests, diffs) the workers cannot. At most one per flow; ignored on subagents execution." {...drag('merge')} onClick={() => addNode('merge')}>
        <span>⛙</span> Merge
      </button>
      <button className={cx(styles.addBtn, styles.addMcp)} title="Connect to the agent that should use its tools. Direction of the wire does not matter." {...drag('mcp')} onClick={() => addNode('mcp')}>
        <span>⚙</span> MCP server
      </button>
      <button className={cx(styles.addBtn, styles.addRepo)} title="Clones the repository into the run and enables PRs/MRs. Connect it to an agent." {...drag('repo')} onClick={() => addNode('repo')}>
        <span>🐙</span> Repository
      </button>
      <button className={cx(styles.addBtn, styles.addSql)} title="Runs a SQL query at run start and injects the rows into the connected agent." {...drag('sql')} onClick={() => addNode('sql')}>
        <span>🗄</span> SQL source (RAG)
      </button>
      <button className={cx(styles.addBtn, styles.addApi)} title="Turn a REST API into typed tools. Either load an OpenAPI spec and tick the operations the agent may call, or type a single endpoint — a webhook, an internal service, one endpoint of an API whose document you do not have." {...drag('api')} onClick={() => addNode('api')}>
        <span>🌐</span> API / endpoint
      </button>
      <button className={cx(styles.addBtn, styles.addFlow)} title="Runs another saved flow. Wired to an agent it is a tool the agent may call and wait on; left unconnected it fires when this flow finishes." {...drag('flow')} onClick={() => addNode('flow')}>
        <span>🔗</span> Run another flow
      </button>
      <button className={cx(styles.addBtn, styles.addKnowledge)} title="Injects the passages most relevant to the run's prompt from a document base (Resources → Knowledge)." {...drag('knowledge')} onClick={() => addNode('knowledge')}>
        <span>📚</span> Knowledge base
      </button>
      <button className={cx(styles.addBtn, styles.addCondition)} title="Draw it between an agent and what it hands off to: the branch runs only when the agent's answer passes the test. The same rule written into a prompt is a request; measured here it is a rule, and the run log says which way it went." {...drag('condition')} onClick={() => addNode('condition')}>
        <span>⑂</span> Condition
      </button>
      <button className={cx(styles.addBtn, styles.addForEach)} title="Runs the branch behind it once per item of the agent's answer — one item per line, or a JSON array. Put a condition after it to filter the items." {...drag('foreach')} onClick={() => addNode('foreach')}>
        <span>⟳</span> For each
      </button>

      <p
        className={styles.hint}
        title="Move: drag. Delete a wire: hover it and click ×, or select it and press Delete. Copy/paste: Ctrl/Cmd+C / V — works across flows. Ctrl/Cmd+D duplicates in place. Shift-drag selects several; wires between copied blocks are kept."
      >
        Hover any button or field for help · shortcuts ⓘ
      </p>

    </aside>
  )
}
