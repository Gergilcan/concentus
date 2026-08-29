import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import { useFlowStore } from '../../state/store.ts'
import type { MailRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

/**
 * A mail sent when the run finishes.
 *
 * No source handle: a mailbox hands nothing back, and a dot on the right edge would invite a wire
 * that means nothing. The badge reads the wire the box hangs off — through any gates in between,
 * exactly as the backend routes it — so the card says WHEN it sends without a field asking the
 * author to repeat the drawing in words.
 */
export function MailNode({ id, data, selected }: NodeProps<MailRFNode>) {
  const { t } = useTranslation()
  const origin = useFlowStore((s) => {
    const gates = new Set(
      s.nodes.filter((n) => n.data.kind === 'condition' || n.data.kind === 'foreach').map((n) => n.id),
    )
    let current: string | undefined = id
    const seen = new Set<string>()
    while (current && !seen.has(current)) {
      seen.add(current)
      const edge = s.edges.find((e) => e.target === current)
      if (!edge) return 'loose'
      // A gate's own else edge is the gate's answer, not the block's output: step over it and
      // keep walking back to the block.
      if (!gates.has(edge.source)) return edge.sourceHandle || 'main'
      current = edge.source
    }
    return 'loose'
  })

  return (
    <NodeShell
      id={id}
      variant="mail"
      selected={selected}
      icon="✉"
      title={data.label || t('Send mail')}
      badge={
        origin === 'loose' ? '—' : origin === 'error' ? t('ON ERROR') : origin === 'rejected' ? t('ON REJECTED') : t('AFTER')
      }
      showSourceHandle={false}
    >
      <div className={styles.snippet}>{[data.to || t('no recipient yet'), data.subject].filter(Boolean).join(' · ')}</div>
      <div className={styles.snippetMuted}>
        {origin === 'loose'
          ? t('not wired to a block — it will not send')
          : origin === 'error'
            ? t("sends that block's failure and log")
            : origin === 'rejected'
              ? t('sends the verification report')
              : t("sends the run's final answer")}
      </div>
    </NodeShell>
  )
}
