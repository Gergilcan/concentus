import { useMemo, useState } from 'react'
import type { PluginInfo } from '../api/types.ts'
import { Modal } from './Modal.tsx'
import modal from './flows.module.scss'
import styles from './panels.module.scss'

interface Props {
  plugins: PluginInfo[]
  selectedIds: string[]
  onChange: (ids: string[]) => void
}

/**
 * The CLI reports whatever the plugin's own manifest says: a semver, a commit sha, or the literal
 * word "unknown". A bare `v` in front of all three produced "va0109974ea32" and "vunknown".
 */
function versionLabel(version?: string): string {
  const v = (version ?? '').trim()
  if (!v || v === 'unknown') return ''
  return /^\d/.test(v) ? `v${v}` : v.slice(0, 8)
}

/**
 * Which Claude Code plugins an agent's runs load — the same dialog its skills use, for the same
 * reason: a column of checkboxes in the inspector stopped being readable the moment a marketplace
 * was added.
 *
 * The selection is exclusive. Nothing ticked means no plugins at all, not "whatever this machine
 * has enabled globally" — that used to be the contract, and it made an empty list a lie: a flow
 * that chose nothing still ran with the CLI's defaults and behaved differently on the next machine.
 */
export function PluginPicker({ plugins, selectedIds, onChange }: Props) {
  const [open, setOpen] = useState(false)

  // Ids kept even when this machine has no such plugin installed — the machine that runs the flow
  // may well have it, and dropping it here would edit the flow just by opening its inspector.
  const known = new Set(plugins.map((p) => p.id))
  const missing = selectedIds.filter((id) => !known.has(id))

  return (
    <div
      className={styles.field}
      title="Claude Code plugins this agent's runs load — exactly these, with every other installed plugin disabled for the run. None selected means none loaded. Claude backend only."
    >
      <span>Plugins ⓘ</span>

      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => setOpen(true)}>
          Choose plugins…
        </button>
        {selectedIds.length > 0 && (
          <button className={styles.linkBtn} onClick={() => onChange([])}>
            Clear ({selectedIds.length})
          </button>
        )}
      </div>

      <p className={styles.hint}>
        {selectedIds.length === 0 ? (
          <>
            <b>None</b> — runs load no plugins at all.
          </>
        ) : (
          <>
            <b>{selectedIds.length} loaded:</b> {selectedIds.slice(0, 3).join(', ')}
            {selectedIds.length > 3 && ` … and ${selectedIds.length - 3} more`}
          </>
        )}
      </p>

      {missing.length > 0 && (
        <p className={styles.hint}>
          ⚠ Not installed here: {missing.join(', ')} — the run will simply not load them.
        </p>
      )}

      {open && (
        <PluginDialog
          plugins={plugins}
          selectedIds={selectedIds}
          onChange={onChange}
          onClose={() => setOpen(false)}
        />
      )}
    </div>
  )
}

function PluginDialog({ plugins, selectedIds, onChange, onClose }: Props & { onClose: () => void }) {
  const [search, setSearch] = useState('')
  const [onlyChosen, setOnlyChosen] = useState(false)
  // Committed on Assign, so leaving with Escape or the backdrop leaves the node as it was.
  const [draft, setDraft] = useState<string[]>(selectedIds)

  const shown = useMemo(() => {
    let list = [...plugins].sort((a, b) => a.id.localeCompare(b.id))
    if (onlyChosen) list = list.filter((p) => draft.includes(p.id))
    const q = search.trim().toLowerCase()
    if (!q) return list
    return list.filter((p) => p.id.toLowerCase().includes(q))
  }, [plugins, search, onlyChosen, draft])

  const toggle = (id: string) =>
    setDraft(draft.includes(id) ? draft.filter((x) => x !== id) : [...draft, id])

  const shownIds = shown.map((p) => p.id)
  const allShownChosen = shownIds.length > 0 && shownIds.every((id) => draft.includes(id))

  return (
    <Modal wide title={`Plugins for this agent — ${draft.length} of ${plugins.length}`} onClose={onClose}>
      <label className={modal.field}>
        <span>Search</span>
        <input
          autoFocus
          value={search}
          placeholder="caveman, superpowers…"
          onChange={(e) => setSearch(e.target.value)}
        />
      </label>

      <label className={modal.toggleRow}>
        <input
          type="checkbox"
          checked={onlyChosen}
          onChange={(e) => setOnlyChosen(e.target.checked)}
        />
        <span>Show only the {draft.length} selected</span>
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
          {allShownChosen ? 'Deselect' : 'Select'} these {shownIds.length}
        </button>
        {draft.length > 0 && (
          <button className={styles.linkBtn} onClick={() => setDraft([])}>
            Clear all
          </button>
        )}
      </div>

      <div className={`${styles.repoList} ${modal.toolList}`}>
        {shown.map((p) => (
          <button key={p.id} className={styles.repoItem} onClick={() => toggle(p.id)}>
            <span>
              {draft.includes(p.id) ? '☑ ' : '☐ '}
              {p.id}
            </span>
            {/* Its state on this machine, which is not what the run uses: a run loads the
                selection and disables the rest, whether or not the CLI has them on today. */}
            <span className={styles.repoMeta}>
              {versionLabel(p.version)}
              {p.enabled ? '' : ' · disabled in the CLI'}
            </span>
          </button>
        ))}
        {shown.length === 0 && (
          <p className={modal.modalHint}>
            Nothing matches {search ? `“${search}”` : 'the current filter'}.
          </p>
        )}
      </div>

      <p className={modal.modalHint}>
        A run loads <b>exactly</b> what is ticked here and disables every other installed plugin.
        Nothing ticked means no plugins at all.
      </p>

      <div className={modal.modalActions}>
        <button className={styles.linkBtn} onClick={onClose}>
          Cancel
        </button>
        <button
          className={styles.previewBtn}
          onClick={() => {
            onChange(draft)
            onClose()
          }}
        >
          {draft.length === 0 ? 'Load none' : `Load these ${draft.length}`}
        </button>
      </div>
    </Modal>
  )
}
