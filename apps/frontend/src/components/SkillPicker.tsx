import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { SkillInfo } from '../api/types.ts'
import { Modal } from './Modal.tsx'
import modal from './flows.module.scss'
import styles from './panels.module.scss'

interface Props {
  skills: SkillInfo[]
  selectedIds: string[]
  onChange: (ids: string[]) => void
}

/**
 * Assigning skills to an agent, as a dialog rather than a list in the sidebar.
 *
 * The inline version was a paged column of checkboxes: it fit three skills, and one browse through
 * the catalog later it was eight per page and a pager in a column narrow enough that reading a
 * description meant hovering. Picking skills is a task, so it gets the room a task needs — the same
 * shape the MCP tool picker already uses, for the same reason.
 *
 * What stays in the sidebar is the answer: how many are assigned and which, so reopening a node
 * says what it has without opening anything.
 */
export function SkillPicker({ skills, selectedIds, onChange }: Props) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)

  // Names, not ids: the ids are paths nobody recognises. Unknown ids (a skill uninstalled since)
  // are dropped from the summary but kept on the node — removing them here would silently edit a
  // flow on nothing more than opening it on the wrong machine.
  const names = selectedIds
    .map((id) => skills.find((s) => s.id === id)?.name)
    .filter((n): n is string => Boolean(n))

  return (
    <div
      className={styles.field}
      title={t(
        "Installed under Resources → Skills. Assigned skills are put into the run's workspace and this agent is told they are its own — Claude Code does the discovering.",
      )}
    >
      <span>{t('Skills')} ⓘ</span>

      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => setOpen(true)}>
          {t('Choose skills…')}
        </button>
        {selectedIds.length > 0 && (
          <button className={styles.linkBtn} onClick={() => onChange([])}>
            {t('Clear')} ({selectedIds.length})
          </button>
        )}
      </div>

      <p className={styles.hint}>
        {selectedIds.length === 0 ? (
          <>
            <b>{t('None assigned')}</b> — {t('the agent runs with whatever Claude Code already has.')}
          </>
        ) : (
          <>
            <b>{t('{{count}} assigned:', { count: selectedIds.length })}</b> {names.slice(0, 4).join(', ')}
            {names.length > 4 && ` … ${t('and {{count}} more', { count: names.length - 4 })}`}
          </>
        )}
      </p>

      {open && (
        <SkillDialog
          skills={skills}
          selectedIds={selectedIds}
          onChange={onChange}
          onClose={() => setOpen(false)}
        />
      )}
    </div>
  )
}

function SkillDialog({ skills, selectedIds, onChange, onClose }: Props & { onClose: () => void }) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [onlyChosen, setOnlyChosen] = useState(false)
  // Edited locally and committed on Assign, so leaving with Escape or the backdrop leaves the node
  // as it was — same contract as the tool picker.
  const [draft, setDraft] = useState<string[]>(selectedIds)

  const shown = useMemo(() => {
    let list = [...skills].sort((a, b) => a.name.localeCompare(b.name))
    if (onlyChosen) list = list.filter((s) => draft.includes(s.id))
    const q = search.trim().toLowerCase()
    if (!q) return list
    return list.filter(
      (s) => s.name.toLowerCase().includes(q) || (s.description ?? '').toLowerCase().includes(q),
    )
    // Alphabetical and stable: sorting the assigned ones first would make a row jump away from the
    // cursor the moment it was ticked. "Show only the selected" answers that question instead.
  }, [skills, search, onlyChosen, draft])

  const toggle = (id: string) =>
    setDraft(draft.includes(id) ? draft.filter((x) => x !== id) : [...draft, id])

  const shownIds = shown.map((s) => s.id)
  const allShownChosen = shownIds.length > 0 && shownIds.every((id) => draft.includes(id))

  return (
    <Modal
      wide
      title={t('Skills for this agent — {{selected}} of {{total}}', {
        selected: draft.length,
        total: skills.length,
      })}
      onClose={onClose}
    >
      <label className={modal.field}>
        <span>{t('Search')}</span>
        <input
          autoFocus
          value={search}
          placeholder={t('pdf, brand, review…')}
          onChange={(e) => setSearch(e.target.value)}
        />
      </label>

      <label className={modal.toggleRow}>
        <input
          type="checkbox"
          checked={onlyChosen}
          onChange={(e) => setOnlyChosen(e.target.checked)}
        />
        <span>{t('Show only the {{count}} assigned', { count: draft.length })}</span>
      </label>

      <div className={styles.mcpBtns}>
        <button
          className={styles.linkBtn}
          disabled={shownIds.length === 0}
          onClick={() =>
            setDraft(
              allShownChosen
                ? draft.filter((id) => !shownIds.includes(id))
                : [...new Set([...draft, ...shownIds])],
            )
          }
        >
          {allShownChosen
            ? t('Deselect these {{count}}', { count: shownIds.length })
            : t('Select these {{count}}', { count: shownIds.length })}
        </button>
        {draft.length > 0 && (
          <button className={styles.linkBtn} onClick={() => setDraft([])}>
            {t('Clear all')}
          </button>
        )}
      </div>

      <div className={`${styles.repoList} ${modal.toolList}`}>
        {shown.map((sk) => (
          <button key={sk.id} className={styles.repoItem} onClick={() => toggle(sk.id)}>
            <span>
              {draft.includes(sk.id) ? '☑ ' : '☐ '}
              {sk.name}
            </span>
            <span className={styles.repoMeta} title={sk.description}>
              {sk.description}
            </span>
          </button>
        ))}
        {shown.length === 0 && (
          <p className={modal.modalHint}>
            {search
              ? t('Nothing matches “{{query}}”.', { query: search })
              : t('Nothing matches the current filter.')}
          </p>
        )}
      </div>

      <div className={modal.modalActions}>
        <button className={styles.linkBtn} onClick={onClose}>
          {t('Cancel')}
        </button>
        <button
          className={styles.previewBtn}
          onClick={() => {
            onChange(draft)
            onClose()
          }}
        >
          {draft.length === 0 ? t('Assign none') : t('Assign these {{count}}', { count: draft.length })}
        </button>
      </div>
    </Modal>
  )
}
