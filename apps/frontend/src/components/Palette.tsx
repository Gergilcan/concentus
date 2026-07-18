import { useFlowStore } from '../state/store.ts'
import { RagPanel } from './RagPanel.tsx'
import styles from './panels.module.scss'

export function Palette() {
  const addNode = useFlowStore((s) => s.addNode)
  return (
    <aside className={styles.palette}>
      <h3 className={styles.h3}>Add node</h3>
      <button className={`${styles.addBtn} ${styles.addInput}`} onClick={() => addNode('input')}>
        <span>▶</span> Input / trigger
      </button>
      <button className={`${styles.addBtn} ${styles.addAgent}`} onClick={() => addNode('agent')}>
        <span>◆</span> Agent
      </button>
      <button className={`${styles.addBtn} ${styles.addMcp}`} onClick={() => addNode('mcp')}>
        <span>⚙</span> MCP server
      </button>
      <button className={`${styles.addBtn} ${styles.addRepo}`} onClick={() => addNode('repo')}>
        <span>🐙</span> Repository
      </button>
      <button className={`${styles.addBtn} ${styles.addSql}`} onClick={() => addNode('sql')}>
        <span>🗄</span> SQL source (RAG)
      </button>

      <p className={styles.hint}>
        Add an <b>Input</b> node to set how a run starts: <b>Manual</b>, a fixed <b>Prompt</b>, or
        <b> Automatic</b> on a cron schedule. Mark exactly one agent as <b>coordinator</b>, and link it to the
        sub-agents it may delegate to. Connect <b>MCP</b>, <b>Repository</b>, and <b>SQL</b> nodes to the
        specific agent that should use them (direction doesn't matter).
      </p>
      <p className={styles.hint}>
        Drag a node to move it. To remove a connection, hover it and click the <b>×</b>, or select it and
        press <b>Delete</b>.
      </p>
      <p className={styles.hint}>
        Copy blocks with <b>Ctrl/⌘+C</b> and paste with <b>Ctrl/⌘+V</b> — you can paste into a different
        flow too. <b>Ctrl/⌘+D</b> duplicates the selection in place. Shift-drag to select several blocks at
        once; connections between copied blocks are kept.
      </p>

      <RagPanel />
    </aside>
  )
}
