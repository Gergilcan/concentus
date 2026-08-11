import { useMemo, useState } from 'react'
import type { SkillInfo } from '../api/types.ts'
import { Pager } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  skills: SkillInfo[]
  selectedIds: string[]
  onChange: (ids: string[]) => void
}

/**
 * Assigning skills to an agent, built for the day the list stopped being three items.
 *
 * A flat checkbox column scaled exactly as long as nobody used the catalog; one browse through
 * GitHub later it was a wall. So: a search box, pages of eight, and — the part that keeps the
 * common case one glance long — the ASSIGNED skills always sort first, so reopening a node shows
 * what it has before anything else. Eight per page rather than the app's usual twenty because
 * this lives in the inspector sidebar, where twenty checkboxes is most of the column.
 */
const PAGE_SIZE = 8

export function SkillPicker({ skills, selectedIds, onChange }: Props) {
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const matches = needle
      ? skills.filter(
          (s) =>
            s.name.toLowerCase().includes(needle) ||
            (s.description ?? '').toLowerCase().includes(needle),
        )
      : skills
    // Selected first, each half alphabetical — stable whatever order the API answered in.
    const chosen = new Set(selectedIds)
    return [...matches].sort((a, b) => {
      const pick = Number(chosen.has(b.id)) - Number(chosen.has(a.id))
      return pick !== 0 ? pick : a.name.localeCompare(b.name)
    })
  }, [skills, selectedIds, query])

  const pages = Math.max(1, Math.ceil(visible.length / PAGE_SIZE))
  const safePage = Math.min(page, pages - 1)
  const pageSkills = visible.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)

  const toggle = (id: string) => {
    onChange(
      selectedIds.includes(id) ? selectedIds.filter((x) => x !== id) : [...selectedIds, id],
    )
  }

  return (
    <div
      className={styles.field}
      title="Installed under Resources → Skills. Assigned skills are put into the run's workspace and this agent is told they are its own — Claude Code does the discovering."
    >
      <span>
        Skills{selectedIds.length > 0 ? ` — ${selectedIds.length} assigned` : ''} ⓘ
      </span>
      {skills.length > PAGE_SIZE && (
        <input
          className={styles.pickerSearch}
          value={query}
          placeholder="Search skills…"
          aria-label="Search skills"
          onChange={(e) => {
            setQuery(e.target.value)
            setPage(0)
          }}
        />
      )}
      {pageSkills.map((sk) => (
        <label key={sk.id} className={styles.checkField} title={sk.description}>
          <input
            type="checkbox"
            checked={selectedIds.includes(sk.id)}
            onChange={() => toggle(sk.id)}
          />
          {sk.name}
        </label>
      ))}
      {visible.length === 0 && <span className={styles.hint}>Nothing matches that search.</span>}
      <Pager page={safePage} pages={pages} total={visible.length} unit="skill" onPage={setPage} />
    </div>
  )
}
