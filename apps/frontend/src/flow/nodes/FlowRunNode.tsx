import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import { useFlowStore } from '../../state/store.ts'
import type { FlowRunRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

/**
 * Another flow, run from this one.
 *
 * The badge is read from the wiring rather than from the node's own data, because the wiring is
 * what decides: drawn into an agent it runs first and its answer becomes that agent's context;
 * drawn out of one it runs when the flow finishes. Saying it on the box closes the gap between
 * "what I drew" and "what that means" without asking anyone to fill in a dropdown.
 *
 * <p>Except on a node saved by an older version, which said so in a field the compiler still
 * honours. Those show what will actually happen — a badge that reported the drawing instead would
 * be the same lie the dropdown used to allow, only harder to notice.
 */
export function FlowRunNode({ id, data, selected }: NodeProps<FlowRunRFNode>) {
  const { t } = useTranslation()
  const drawn = useFlowStore((s) => {
    const consumers = new Set(
      s.nodes.filter((n) => ['agent', 'coordinator', 'merge', 'verifier'].includes(n.data.kind)).map((n) => n.id),
    )
    const before = s.edges.some((e) => e.source === id && consumers.has(e.target))
    const after = s.edges.some((e) => consumers.has(e.source) && e.target === id)
    return before && after ? 'both' : before ? 'before' : after ? 'after' : 'loose'
  })
  // 'tool' was the old word for what the drawing now calls "wired into an agent": the agent may
  // call it. The one thing it never was is a hand-off, so it never reads as one here.
  const wiring = data.mode ? (data.mode === 'after' ? 'after' : 'before') : drawn

  return (
    <NodeShell
      altHandles={[{ id: 'error', label: t('on error'), tone: 'error', optional: { flag: 'errorOutput', enabled: !!data.errorOutput } }]}
      id={id}
      variant="flow"
      selected={selected}
      icon="🔗"
      title={data.label || t('flow')}
      badge={wiring === 'after' ? t('AFTER') : wiring === 'loose' ? '—' : t('BEFORE')}
      showStatus
    >
      <div className={styles.snippet}>
        {!data.flowId
          ? t('no flow selected')
          : wiring === 'loose'
            ? t('not wired to an agent — it will not run')
            : wiring === 'after'
              ? t('runs when this flow finishes')
              : data.waitForResult
                ? t('runs first; its answer goes to the agent')
                : t('starts first; nobody waits for it')}
      </div>
    </NodeShell>
  )
}
