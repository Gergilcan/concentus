import { NodeResizer, type NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import { useFlowStore } from '../../state/store.ts'
import { cx } from '../../utils/cx.ts'
import type { GroupRFNode } from '../nodeTypes.ts'
import styles from './nodes.module.scss'

/**
 * A labelled frame. The size is the node's own (`width`/`height`, which the resizer changes
 * through ordinary dimension changes), so the frame fills whatever it was stretched to; the
 * blocks inside it are its React Flow children and travel with it.
 */
export function GroupNode({ data, selected }: NodeProps<GroupRFNode>) {
  const { t } = useTranslation()
  const checkpoint = useFlowStore((s) => s.checkpoint)
  return (
    <div className={cx(styles.group, styles['tint_' + data.color], selected && styles.selected)}>
      {/* Corners only while the frame is picked: eight handles on every frame all the time is
          a canvas covered in dots. One resize is one undo step, like one drag. */}
      <NodeResizer
        isVisible={!!selected}
        minWidth={160}
        minHeight={100}
        lineClassName={styles.resizeLine}
        handleClassName={styles.resizeHandle}
        onResizeStart={() => checkpoint()}
      />
      <span className={styles.groupLabel}>{data.label || t('Group')}</span>
    </div>
  )
}
