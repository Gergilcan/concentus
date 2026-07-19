import { useState } from 'react'
import type { AppNodeData } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { useFlowStore } from '../state/store.ts'
import { AgentInspector } from './AgentInspector.tsx'
import { InputInspector } from './InputInspector.tsx'
import { InputView, OutputView } from './NodeExecView.tsx'
import { NodeLogView } from './NodeLogView.tsx'
import { McpInspector } from './McpInspector.tsx'
import { RepoInspector } from './RepoInspector.tsx'
import { SqlInspector } from './SqlInspector.tsx'
import styles from './panels.module.scss'

function title(data: AppNodeData): string {
  if (data.kind === 'agent') return 'Agent'
  if (data.kind === 'input') return 'Input / trigger'
  if (data.kind === 'mcp') return 'MCP server'
  if (data.kind === 'sql') return 'SQL source'
  return 'Repository'
}

type Tab = 'properties' | 'input' | 'output' | 'logs'

const TAB_LABEL: Record<Tab, string> = {
  properties: 'Properties',
  input: 'Input',
  output: 'Output',
  logs: 'Logs',
}

export function Inspector() {
  const selectedId = useFlowStore((s) => s.selectedId)
  const node = useFlowStore((s) => s.nodes.find((n) => n.id === selectedId) ?? null)
  const update = useFlowStore((s) => s.updateNodeData)
  const remove = useFlowStore((s) => s.deleteNode)
  const duplicate = useFlowStore((s) => s.duplicateNode)
  const activeRunId = useFlowStore((s) => s.activeRunId)
  const exec = useFlowStore((s) => (selectedId ? s.runExecByNode[selectedId] : undefined))
  const [tab, setTab] = useState<Tab>('properties')

  if (!node) {
    return (
      <aside className={styles.inspector}>
        <div className={styles.empty}>Select a node to edit its settings.</div>
      </aside>
    )
  }

  const id = node.id
  const data = node.data
  const set = (patch: Record<string, unknown>) => update(id, patch)
  // Input/Output tabs only make sense for boxes that execute; only agents produce console
  // output, so Logs is theirs alone.
  const hasExecTabs = data.kind === 'agent' || data.kind === 'sql' || data.kind === 'mcp'
  const tabs: Tab[] = data.kind === 'agent'
    ? ['properties', 'input', 'output', 'logs']
    : ['properties', 'input', 'output']
  const shownTab: Tab = hasExecTabs && tabs.includes(tab) ? tab : 'properties'

  return (
    <aside className={styles.inspector}>
      <div className={styles.inspectorHead}>
        <h3 className={styles.h3}>{title(data)}</h3>
        <button className={styles.dup} onClick={() => duplicate(id)} title="Duplicate this node (Ctrl+D)">
          Duplicate
        </button>
        <button className={styles.del} onClick={() => remove(id)}>
          Delete
        </button>
      </div>

      {hasExecTabs && (
        <div className={styles.execTabs}>
          {tabs.map((t) => (
            <button
              key={t}
              className={cx(styles.execTab, shownTab === t && styles.execTabActive)}
              onClick={() => setTab(t)}
            >
              {TAB_LABEL[t]}
            </button>
          ))}
        </div>
      )}

      {shownTab === 'input' && (
        <>
          {!activeRunId && <div className={styles.empty}>Select a run below to see its data.</div>}
          <InputView exec={exec} />
        </>
      )}
      {shownTab === 'logs' && data.kind === 'agent' && (
        <NodeLogView nodeId={id} label={data.name} />
      )}
      {shownTab === 'output' && (
        <>
          {!activeRunId && <div className={styles.empty}>Select a run below to see its data.</div>}
          <OutputView exec={exec} />
        </>
      )}

      {shownTab === 'properties' && data.kind === 'agent' && <AgentInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'input' && <InputInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'mcp' && <McpInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'sql' && <SqlInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'repo' && <RepoInspector data={data} set={set} />}
    </aside>
  )
}
