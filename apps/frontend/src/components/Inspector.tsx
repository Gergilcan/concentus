import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { AppNodeData } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { useFlowStore } from '../state/store.ts'
import { AgentInspector } from './AgentInspector.tsx'
import { GroupInspector, NoteInspector } from './AnnotationInspectors.tsx'
import { ApiInspector } from './ApiInspector.tsx'
import { ConditionInspector, ForEachInspector } from './GateInspectors.tsx'
import { hasChanges } from './diff.ts'
import { DiffList } from './DiffView.tsx'
import { FlowVersions } from './FlowVersions.tsx'
import { InputInspector } from './InputInspector.tsx'
import { InputView, OutputView } from './NodeExecView.tsx'
import { NodeLogView } from './NodeLogView.tsx'
import { McpInspector } from './McpInspector.tsx'
import { MergeInspector } from './MergeInspector.tsx'
import { RepoInspector } from './RepoInspector.tsx'
import { RerunBlockDialog } from './RerunBlockDialog.tsx'
import { KnowledgeInspector } from './KnowledgeInspector.tsx'
import { FlowRunInspector } from './FlowRunInspector.tsx'
import { MailInspector } from './MailInspector.tsx'
import { SqlInspector } from './SqlInspector.tsx'
import { VerifierInspector } from './VerifierInspector.tsx'
import styles from './panels.module.scss'

function title(data: AppNodeData): string {
  if (data.kind === 'agent') return 'Agent'
  if (data.kind === 'coordinator') return 'Coordinator'
  if (data.kind === 'input') return 'Input / trigger'
  if (data.kind === 'mcp') return 'MCP server'
  if (data.kind === 'sql') return 'SQL source'
  if (data.kind === 'knowledge') return 'Knowledge base'
  if (data.kind === 'api') return data.mode === 'endpoint' ? 'API endpoint' : 'API (OpenAPI)'
  if (data.kind === 'flow') return 'Run another flow'
  if (data.kind === 'merge') return 'Merge'
  if (data.kind === 'verifier') return 'Verifier'
  if (data.kind === 'condition') return 'Condition'
  if (data.kind === 'foreach') return 'For each'
  if (data.kind === 'mail') return 'Send mail'
  if (data.kind === 'note') return 'Note'
  if (data.kind === 'group') return 'Group'
  return 'Repository'
}

type Tab = 'properties' | 'input' | 'output' | 'logs' | 'changes'

const TAB_LABEL: Record<Tab, string> = {
  properties: 'Properties',
  input: 'Input',
  output: 'Output',
  logs: 'Logs',
  changes: 'Changes',
}

export function Inspector() {
  const { t } = useTranslation()
  const selectedId = useFlowStore((s) => s.selectedId)
  const node = useFlowStore((s) => s.nodes.find((n) => n.id === selectedId) ?? null)
  const update = useFlowStore((s) => s.updateNodeData)
  const remove = useFlowStore((s) => s.deleteNode)
  const duplicate = useFlowStore((s) => s.duplicateNode)
  const activeRunId = useFlowStore((s) => s.activeRunId)
  const flowId = useFlowStore((s) => s.flowId)
  const exec = useFlowStore((s) => (selectedId ? s.runExecByNode[selectedId] : undefined))
  const runDiffs = useFlowStore((s) => s.runDiffs)
  // This block's checkouts with something to show. A checkout read and found unchanged is not
  // a tab: the run-level view says "nothing changed"; the block only speaks when it did something.
  const nodeDiffs = runDiffs.filter((d) => d.nodeId === selectedId && hasChanges(d))
  const [tab, setTab] = useState<Tab>('properties')
  // Whether the "run this block again" dialog is open. Reset by closing it, not by changing
  // selection: it names the block it was opened from and carries that block's recorded input.
  const [rerun, setRerun] = useState(false)
  // The no-selection panel's own tab. Separate state from `tab` so selecting a node and coming
  // back does not drop you into a tab that means something else.
  const [flowTab, setFlowTab] = useState<'flow' | 'versions'>('flow')
  const [versionsError, setVersionsError] = useState<string | null>(null)

  if (!node) {
    // A box that exists only in the run report: a plan-born worker. Nothing to edit — its
    // definition was the coordinator's plan — but its input, output and cost are real.
    if (selectedId?.startsWith('worker:') && exec) {
      return (
        <aside className={styles.inspector}>
          <div className={styles.inspectorHead}>
            <h3 className={styles.h3} title={t("Created by the coordinator's plan for this run. It is not part of the drawn flow, so there is nothing to edit; the next run may plan different workers.")}>
              {t('Worker: {{label}} ⓘ', { label: exec.label })}
            </h3>
          </div>
          <h4 className={styles.h3}>{t('Input')}</h4>
          <InputView exec={exec} />
          <h4 className={styles.h3}>{t('Output')}</h4>
          <OutputView exec={exec} />
          {/* Plan-born workers get every repository on the canvas, so their diff is the one
              most worth reading — and they have no tabs to put it behind. */}
          {activeRunId && nodeDiffs.length > 0 && (
            <>
              <h4 className={styles.h3}>{t('Changes')}</h4>
              <DiffList runId={activeRunId} diffs={nodeDiffs} />
            </>
          )}
        </aside>
      )
    }
    // Nothing selected: the inspector is free, so it hosts what belongs to the flow rather than
    // to a node. Versions lives here instead of behind the dashboard's History modal because
    // restoring and previewing are editing actions — they belong next to the canvas they change.
    return (
      <aside className={styles.inspector}>
        <div className={styles.inspectorHead}>
          <h3 className={styles.h3}>{t('Flow')}</h3>
        </div>
        <div className={styles.execTabs}>
          {(['flow', 'versions'] as const).map((ft) => (
            <button
              key={ft}
              className={cx(styles.execTab, flowTab === ft && styles.execTabActive)}
              onClick={() => setFlowTab(ft)}
            >
              {ft === 'flow' ? t('Properties') : t('Versions')}
            </button>
          ))}
        </div>
        {flowTab === 'flow' ? (
          <div className={styles.empty}>{t('Select a node to edit its settings.')}</div>
        ) : flowId ? (
          <FlowVersions flowId={flowId} pushError={setVersionsError} />
        ) : (
          <div className={styles.empty}>{t('Save this flow to start its version history.')}</div>
        )}
        {versionsError && <div className={styles.empty}>{versionsError}</div>}
      </aside>
    )
  }

  const id = node.id
  const data = node.data
  const set = (patch: Record<string, unknown>) => update(id, patch)
  // Input/Output tabs only make sense for boxes that execute; only agents produce console
  // output, so Logs is theirs alone.
  const hasExecTabs =
    data.kind === 'agent' || data.kind === 'coordinator' || data.kind === 'sql' || data.kind === 'knowledge' || data.kind === 'api' || data.kind === 'mcp' || data.kind === 'flow' || data.kind === 'input' || data.kind === 'merge' || data.kind === 'verifier'
  // The Input node has an Output but no Input of its own: it is where the run's text comes *from*.
  // For a mail trigger that output is the email, which is the first thing anyone wants to read.
  const tabs: Tab[] =
    data.kind === 'agent' || data.kind === 'coordinator' || data.kind === 'merge' || data.kind === 'verifier'
      ? ['properties', 'input', 'output', 'logs']
      : data.kind === 'input'
        ? ['properties', 'output']
        : ['properties', 'input', 'output']
  // Changes only for the kinds that clone a repository, and only once this run's read of that
  // block's checkouts found something: an empty tab would be a promise the run did not keep.
  if ((data.kind === 'agent' || data.kind === 'coordinator' || data.kind === 'merge') && nodeDiffs.length > 0) {
    tabs.push('changes')
  }
  const shownTab: Tab = hasExecTabs && tabs.includes(tab) ? tab : 'properties'

  return (
    <aside className={styles.inspector}>
      <div className={styles.inspectorHead}>
        <h3 className={styles.h3}>{t(title(data))}</h3>
        <button className={styles.dup} onClick={() => duplicate(id)} title={t('Duplicate this node (Ctrl+D)')}>
          {t('Duplicate')}
        </button>
        <button className={styles.del} onClick={() => remove(id)}>
          {t('Delete')}
        </button>
      </div>

      {hasExecTabs && (
        <div className={styles.execTabs}>
          {tabs.map((tb) => (
            <button
              key={tb}
              className={cx(styles.execTab, shownTab === tb && styles.execTabActive)}
              onClick={() => setTab(tb)}
            >
              {t(TAB_LABEL[tb])}
            </button>
          ))}
        </div>
      )}

      {shownTab === 'input' && (
        <>
          {!activeRunId && <div className={styles.empty}>{t('Select a run below to see its data.')}</div>}
          <InputView exec={exec} />
          {/* Only for agent blocks: a capability node has no instruction to run again, and only
              once there is a recorded input — the offer is to reproduce this block's conditions,
              and with nothing recorded there are none to reproduce. */}
          {activeRunId && (data.kind === 'agent' || data.kind === 'coordinator') && exec?.input && (
            <>
              <button className={styles.rerunBlock} onClick={() => setRerun(true)}>
                {t('Run this block again…')}
              </button>
              {rerun && (
                <RerunBlockDialog
                  runId={activeRunId}
                  nodeId={id}
                  label={data.name || id}
                  recordedInput={exec.input}
                  onClose={() => setRerun(false)}
                />
              )}
            </>
          )}
        </>
      )}
      {shownTab === 'logs' && (data.kind === 'agent' || data.kind === 'coordinator' || data.kind === 'merge' || data.kind === 'verifier') && (
        <NodeLogView nodeId={id} label={data.name} />
      )}
      {shownTab === 'output' && (
        <>
          {!activeRunId && <div className={styles.empty}>{t('Select a run below to see its data.')}</div>}
          <OutputView exec={exec} />
        </>
      )}
      {shownTab === 'changes' && activeRunId && <DiffList runId={activeRunId} diffs={nodeDiffs} />}

      {shownTab === 'properties' && (data.kind === 'agent' || data.kind === 'coordinator') && <AgentInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'input' && <InputInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'mcp' && <McpInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'sql' && <SqlInspector data={data} set={set} />}
      {shownTab === 'properties' && data.kind === 'knowledge' && <KnowledgeInspector data={data} set={set} />}
      {shownTab === 'properties' && data.kind === 'api' && <ApiInspector data={data} set={set} />}
      {shownTab === 'properties' && data.kind === 'flow' && <FlowRunInspector data={data} set={set} />}
      {shownTab === 'properties' && data.kind === 'condition' && <ConditionInspector data={data} set={set} />}
      {shownTab === 'properties' && data.kind === 'foreach' && <ForEachInspector data={data} set={set} />}
      {shownTab === 'properties' && data.kind === 'mail' && <MailInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'merge' && <MergeInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'verifier' && <VerifierInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'repo' && <RepoInspector data={data} set={set} />}

      {shownTab === 'properties' && data.kind === 'note' && <NoteInspector data={data} set={set} />}
      {shownTab === 'properties' && data.kind === 'group' && <GroupInspector data={data} set={set} />}
    </aside>
  )
}
