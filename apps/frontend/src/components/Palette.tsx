import { useFlowStore } from '../state/store.ts'
import { cx } from '../utils/cx.ts'
import { RagPanel } from './RagPanel.tsx'
import styles from './panels.module.scss'

export function Palette() {
  const addNode = useFlowStore((s) => s.addNode)
  return (
    <aside className={styles.palette}>
      <h3 className={styles.h3}>Add node</h3>
      <button className={cx(styles.addBtn, styles.addInput)} title="How a run starts: manual, a fixed prompt, cron, webhook, or incoming mail." onClick={() => addNode('input')}>
        <span>▶</span> Input / trigger
      </button>
      <button className={cx(styles.addBtn, styles.addAgent)} title="Mark exactly one agent as coordinator and link it to the sub-agents it may delegate to." onClick={() => addNode('agent')}>
        <span>◆</span> Agent
      </button>
      <button className={cx(styles.addBtn, styles.addMcp)} title="Connect to the agent that should use its tools. Direction of the wire does not matter." onClick={() => addNode('mcp')}>
        <span>⚙</span> MCP server
      </button>
      <button className={cx(styles.addBtn, styles.addRepo)} title="Clones the repository into the run and enables PRs/MRs. Connect it to an agent." onClick={() => addNode('repo')}>
        <span>🐙</span> Repository
      </button>
      <button className={cx(styles.addBtn, styles.addSql)} title="Runs a SQL query at run start and injects the rows into the connected agent." onClick={() => addNode('sql')}>
        <span>🗄</span> SQL source (RAG)
      </button>
      <button className={cx(styles.addBtn, styles.addKnowledge)} title="Injects the passages most relevant to the run's prompt from a document base (Resources → Knowledge)." onClick={() => addNode('knowledge')}>
        <span>📚</span> Knowledge base
      </button>

      <p
        className={styles.hint}
        title="Move: drag. Delete a wire: hover it and click ×, or select it and press Delete. Copy/paste: Ctrl/Cmd+C / V — works across flows. Ctrl/Cmd+D duplicates in place. Shift-drag selects several; wires between copied blocks are kept."
      >
        Hover any button or field for help · shortcuts ⓘ
      </p>

      <RagPanel />
    </aside>
  )
}
