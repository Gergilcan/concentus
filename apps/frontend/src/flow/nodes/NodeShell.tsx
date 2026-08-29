import { Handle, Position } from '@xyflow/react'
import { useMemo } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useFlowStore } from '../../state/store.ts'
import { cx } from '../../utils/cx.ts'
import { NodeReplayBadge } from './NodeReplayBadge.tsx'
import { NodeStatusBadge } from './NodeStatusBadge.tsx'
import styles from './nodes.module.scss'

/**
 * One of a block's second outputs, and what it means.
 *
 * <p>{@code id} is what the wire remembers, and it is what the backend routes on. The label is
 * drawn beside the handle, because a second dot on the right edge with no word next to it is a
 * thing people wire by accident.
 *
 * <p>An {@code optional} output is hidden until the author turns it on (a chip on the card sets
 * the named node-data flag) or a wire already leaves it — a wire never loses its dot. A
 * condition's `else` is not optional: a gate with one visible branch is half a gate.
 */
export type AltHandle = {
  id: 'error' | 'rejected' | 'else'
  label: string
  tone: 'error' | 'rejected' | 'else'
  optional?: { flag: 'errorOutput' | 'rejectedOutput'; enabled: boolean }
}

/** Vertical rhythm of stacked second outputs: the first sits lowest, each next one this much higher. */
const ROW = 16

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
  altHandles = [],
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
   * The block's second outputs, lowest first.
   *
   * <p>Every block that can go wrong has one, and it is not decoration: without it, a failing
   * block ends the run and a condition that does not hold simply stops, with nowhere on the
   * canvas to say what should happen instead.
   */
  altHandles?: AltHandle[]
  showStatus?: boolean
  children?: ReactNode
}) {
  const { t } = useTranslation()
  const update = useFlowStore((s) => s.updateNodeData)
  // Which of this block's second outputs already carry a wire. Read from the store the canvas
  // saves, so a hidden-by-default output with a wire on it is shown for as long as the wire is.
  const edges = useFlowStore((s) => s.edges)
  const wired = useMemo(() => {
    const out = new Set<string>()
    if (!id) return out
    for (const e of edges) if (e.source === id && e.sourceHandle) out.add(e.sourceHandle)
    return out
  }, [edges, id])

  const shown = altHandles.filter((h) => !h.optional || h.optional.enabled || wired.has(h.id))
  const hidden = altHandles.filter((h) => !shown.includes(h))
  const rows = Math.max(shown.length, hidden.length > 0 ? 1 : 0)

  return (
    <div
      className={cx(styles.node, styles[variant], coordinator && styles.coordinator, selected && styles.selected)}
      style={rows > 0 ? { paddingBottom: 8 + rows * ROW } : undefined}
    >
      {showTargetHandle && <Handle type="target" position={Position.Left} />}
      <div className={styles.header}>
        <span className={styles.icon}>{icon}</span>
        <span className={styles.title}>{title}</span>
        <span className={cx(styles.badge, coordinator && styles.badgeCoord)}>{badge}</span>
      </div>
      {children}
      {showStatus && id && <NodeStatusBadge id={id} />}
      {id && <NodeReplayBadge id={id} />}
      {showSourceHandle && <Handle type="source" position={Position.Right} />}
      {shown.map((h, i) => {
        const isWired = wired.has(h.id)
        const toggles = !!h.optional && !isWired
        return (
          <span key={h.id}>
            {/* Below the main one and named. The two outputs of a block are not interchangeable,
                so they must not look interchangeable. */}
            <Handle
              id={h.id}
              type="source"
              position={Position.Right}
              className={styles['handle_' + h.tone]}
              style={{ top: `calc(100% - ${14 + i * ROW}px)` }}
            />
            <span
              className={cx(styles.altLabel, styles['alt_' + h.tone], toggles && styles.altToggle, 'nodrag', 'nopan')}
              style={{ bottom: 2 + i * ROW }}
              title={
                !h.optional ? undefined : isWired ? t('Delete the wire to hide this output') : t('Hide this output')
              }
              role={toggles ? 'button' : undefined}
              onClick={
                toggles && id
                  ? (ev) => {
                      ev.stopPropagation()
                      update(id, { [h.optional!.flag]: false })
                    }
                  : undefined
              }
            >
              {h.label}
            </span>
          </span>
        )
      })}
      {hidden.length > 0 && id && (
        <span className={cx(styles.chips, 'nodrag', 'nopan')}>
          {hidden.map((h) => (
            <button
              key={h.id}
              type="button"
              className={cx(styles.chip, styles['alt_' + h.tone])}
              title={t('Show this output')}
              onClick={(ev) => {
                ev.stopPropagation()
                update(id, { [h.optional!.flag]: true })
              }}
            >
              + {h.label}
            </button>
          ))}
        </span>
      )}
    </div>
  )
}
