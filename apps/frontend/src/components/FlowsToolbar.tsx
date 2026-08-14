import { useRef } from 'react'
import { type Sort } from './flowFormat.ts'
import styles from './flows.module.scss'

/** Search, sort, import, and "new flow" controls in the flows dashboard header. */
export function FlowsToolbar({
  query,
  onQueryChange,
  sort,
  onSortChange,
  onImport,
  onNew,
  onDescribe,
  onRecipes,
}: {
  query: string
  onQueryChange: (q: string) => void
  sort: Sort
  onSortChange: (s: Sort) => void
  onImport: (file: File) => void
  onNew: () => void
  /** Opens the "describe what you want automated" dialog. */
  onDescribe: () => void
  /** Opens the outcome recipes — a configured flow in two or three questions. */
  onRecipes: () => void
}) {
  const fileRef = useRef<HTMLInputElement>(null)

  return (
    <div className={styles.headActions}>
      <input
        className={styles.search}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        placeholder="Search flows…"
        aria-label="Search flows"
      />
      <select
        className={styles.sort}
        value={sort}
        onChange={(e) => onSortChange(e.target.value as Sort)}
        aria-label="Sort flows"
      >
        <option value="recent">Recently run</option>
        <option value="name">Name</option>
        <option value="runs">Most runs</option>
      </select>
      <button className={styles.ghost} onClick={() => fileRef.current?.click()}>
        Import
      </button>
      <input
        ref={fileRef}
        type="file"
        accept="application/json,.json"
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0]
          if (f) onImport(f)
          e.target.value = ''
        }}
      />
      <button
        className={styles.ghost}
        onClick={onRecipes}
        title="Pick an outcome — triage my inbox, brief me every morning — answer the two or three things only you can know, and get a configured flow."
      >
        🍳 Recipes
      </button>
      <button
        className={styles.ghost}
        onClick={onDescribe}
        title="Describe what you want automated in a sentence and get a first draft on the canvas. Nothing is saved until you press Save."
      >
        ✨ Describe a flow
      </button>
      <button className={styles.primary} onClick={onNew}>
        + New flow
      </button>
    </div>
  )
}
