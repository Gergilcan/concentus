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
  altHandle,
  showStatus = false,
  children,
}: {
  id?: string
  variant: 'agent' | 'mcp' | 'sql' | 'repo' | 'knowledge' | 'api' | 'flow' | 'input' | 'merge' | 'verifier' | 'condition' | 'foreach' | 'worker'
  selected?: boolean
  coordinator?: boolean
  icon: ReactNode
  title: ReactNode
  badge: ReactNode
  showTargetHandle?: boolean
  showSourceHandle?: boolean
  /**
   * The block's second output, and what it means.
   *
   * <p>Every block that can go wrong has one, and it is not decoration: without it, a failing
   * block ends the run and a condition that does not hold simply stops, with nowhere on the
   * canvas to say what should happen instead. The label is drawn beside the handle, because a
   * second dot on the right edge with no word next to it is a thing people wire by accident.
   *
   * <p>{@code id} is what the wire remembers, and it is what the backend routes on.
   */
  altHandle?: { id: string; label: string; tone: 'error' | 'else' }
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
      {altHandle && (
        <>
          {/* Below the main one and named. The two outputs of a block are not interchangeable, so
              they must not look interchangeable. */}
          <Handle
            id={altHandle.id}
            type="source"
            position={Position.Right}
            className={altHandle.tone === 'error' ? styles.handleError : styles.handleElse}
            style={{ top: 'calc(100% - 14px)' }}
          />
          <span
            className={cx(styles.altLabel, altHandle.tone === 'error' ? styles.altError : styles.altElse)}
            aria-hidden="true"
          >
            {altHandle.label}
          </span>
        </>
      )}
    </div>
  )
}
