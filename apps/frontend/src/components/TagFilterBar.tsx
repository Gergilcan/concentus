import { cx } from '../utils/cx.ts'
import styles from './flows.module.scss'

/** "All" + one chip per tag; clicking a chip toggles it as the active filter. */
export function TagFilterBar({
  tags,
  activeTag,
  onSelect,
}: {
  tags: string[]
  activeTag: string | null
  onSelect: (tag: string | null) => void
}) {
  if (tags.length === 0) return null

  return (
    <div className={styles.tagBar}>
      <button className={cx(styles.tagChip, !activeTag && styles.tagActive)} onClick={() => onSelect(null)}>
        All
      </button>
      {tags.map((t) => (
        <button
          key={t}
          className={cx(styles.tagChip, activeTag === t && styles.tagActive)}
          onClick={() => onSelect(activeTag === t ? null : t)}
        >
          {t}
        </button>
      ))}
    </div>
  )
}
