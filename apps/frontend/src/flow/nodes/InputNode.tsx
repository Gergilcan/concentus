import type { NodeProps, Node } from '@xyflow/react'
import type { ReactElement } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import type { InputNodeData } from '../../api/types.ts'
import { describeCron } from '../../components/cron.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

type InputRFNode = Node<InputNodeData, 'input'>

const LABEL: Record<InputNodeData['mode'], string> = {
  manual: 'Manual',
  prompt: 'Prompt',
  cron: 'Automatic (cron)',
  webhook: 'Webhook',
  mail: 'Mail (IMAP)',
  subflow: 'Another flow',
}

/** What this input will do when the flow runs — the line under the mode badge. */
function summary(data: InputNodeData, t: TFunction): ReactElement {
  if (data.mode === 'webhook') {
    return <div className={styles.snippetMuted}>{t('starts on an external event')}</div>
  }
  if (data.mode === 'subflow') {
    return <div className={styles.snippetMuted}>{t('another flow starts this one')}</div>
  }
  if (data.mode === 'manual') {
    return <div className={styles.snippetMuted}>{t('you type the first message')}</div>
  }
  if (!data.prompt) return <div className={styles.snippetMuted}>{t('no prompt set')}</div>
  // Full text, clamped by CSS — slicing here cut mid-word with nothing to show for it.
  return (
    <div className={styles.snippet} title={data.prompt}>
      {data.prompt}
    </div>
  )
}

export function InputNode({ data, selected }: NodeProps<InputRFNode>) {
  const { t } = useTranslation()
  return (
    <NodeShell
      variant="input"
      selected={selected}
      showTargetHandle={false}
      icon="▶"
      title={t('Input')}
      badge={t(LABEL[data.mode])}
    >
      {/* The schedule in words when it can be said in words ("Working days at 07:00"), the raw
          expression otherwise — with the expression on hover either way. */}
      {data.mode === 'cron' && (
        <div className={styles.meta} title={data.cron || undefined}>
          {data.cron ? (describeCron(data.cron) ?? data.cron) : t('no schedule')}
        </div>
      )}
      {/* The folder is what someone scanning the canvas needs to see — it is the thing that
          decides which mail this flow acts on. */}
      {data.mode === 'mail' && (
        <div className={styles.meta}>{data.mailFolder || t('no folder set')}</div>
      )}
      {summary(data, t)}
    </NodeShell>
  )
}
