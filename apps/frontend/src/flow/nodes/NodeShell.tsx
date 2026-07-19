import { Handle, Position } from '@xyflow/react'
import type { ReactNode } from 'react'
import { cx } from '../../utils/cx.ts'
import { NodeStatusBadge } from './NodeStatusBadge.tsx'
import styles from './nodes.module.scss'

/** Shared Handle + header (icon/title/badge) + status-badge scaffold for all node cards. */
export function NodeShell({
  id,
  variant,
  selected,
  coordinator,
  icon,
  title,
  badge,
  showTargetHandle = true,
  showSourceHandle = true,
  showStatus = false,
  children,
}: {
  id?: string
  variant: 'agent' | 'mcp' | 'sql' | 'repo' | 'input'
  selected?: boolean
  coordinator?: boolean
  icon: ReactNode
  title: ReactNode
  badge: ReactNode
  showTargetHandle?: boolean
  showSourceHandle?: boolean
  showStatus?: boolean
  children?: ReactNode
}) {
  return (
    <div className={cx(styles.node, styles[variant], coordinator && styles.coordinator, selected && styles.selected)}>
      {showTargetHandle && <Handle type="target" position={Position.Left} />}
      <div className={styles.header}>
        <span className={styles.icon}>{icon}</span>
        <span className={styles.title}>{title}</span>
        <span className={cx(styles.badge, coordinator && styles.badgeCoord)}>{badge}</span>
      </div>
      {children}
      {showStatus && id && <NodeStatusBadge id={id} />}
      {showSourceHandle && <Handle type="source" position={Position.Right} />}
    </div>
  )
}
