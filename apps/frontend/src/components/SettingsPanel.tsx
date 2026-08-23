import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.ts'
import type { SettingEntry } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { setTheme, THEMES, useTheme } from '../utils/theme.ts'
import { LicensePanel } from './LicensePanel.tsx'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * The theme, where the rest of the preferences are.
 *
 * <p>It used to be a button in the header, cycling through three values one click at a time — a
 * control in the most valuable corner of the screen for something a person sets once and never
 * touches again. That corner now belongs to the thing that changes without being asked.
 *
 * <p>Above the saved settings and not among them, because it is not one of them: it lives in this
 * browser rather than in the database, applies the instant it is chosen, and has no Save.
 */
function ThemeSetting() {
  const theme = useTheme()
  return (
    <div className={styles.themeRow}>
      <div>
        <label className={styles.themeLabel}>Appearance</label>
        <p className={panels.hint}>
          This browser only, and immediately — it is not saved with the settings below.
        </p>
      </div>
      <div className={styles.themeChoices}>
        {THEMES.map((t) => (
          <button
            key={t.id}
            type="button"
            className={t.id === theme ? styles.themeOn : styles.themeOff}
            aria-pressed={t.id === theme}
            onClick={() => setTheme(t.id)}
          >
            <span aria-hidden="true">{t.icon}</span> {t.label}
          </button>
        ))}
      </div>
    </div>
  )
}

/** What a value's origin means, in the words somebody reading the screen would use. */
const SOURCE_LABEL: Record<string, string> = {
  STORED: 'set here',
  CONFIGURED: 'from this deployment',
  DEFAULT: 'default',
}

/**
 * Everything about this installation that can be changed, in one place.
 *
 * <p>All of it used to be environment variables — which on a server means editing a container's
 * environment and redeploying, and on the desktop means nothing at all, because the shell computes
 * the environment and there is no file a person can edit. So they were, in practice, not settings.
 *
 * <p>What is deliberately absent is the handful that cannot be here: the database this table lives
 * in, the key that decrypts it, the port, the data directory. Something has to be known before
 * there is anywhere to keep a setting, and those are it.
 *
 * <p>Every field says where its current value comes from, because "20" means three different
 * things — somebody chose it, the deployment was started with it, or it is simply what the code
 * does — and only the first is yours to clear.
 */
export function SettingsPanel({ pushError }: { pushError: (m: string) => void }) {
  const [entries, setEntries] = useState<SettingEntry[] | null>(null)
  const [edits, setEdits] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  /**
   * What the last save was, or null when there has not been one.
   *
   * Held rather than derived from the pending edits, which are cleared by the save itself — read
   * afterwards they say "nothing changed", and the note would have promised every change was in
   * effect immediately.
   */
  const [saved, setSaved] = useState<{ restartRequired: boolean } | null>(null)

  useEffect(() => {
    api
      .listSettings()
      .then((r) => setEntries(r.settings))
      .catch((e) => {
        setEntries([])
        pushError(errMessage(e))
      })
  }, [pushError])

  const groups = useMemo(() => {
    const byGroup = new Map<string, SettingEntry[]>()
    for (const entry of entries ?? []) {
      const list = byGroup.get(entry.group) ?? []
      list.push(entry)
      byGroup.set(entry.group, list)
    }
    return [...byGroup.entries()]
  }, [entries])

  const change = (key: string, value: string) => {
    setSaved(null)
    setEdits((prev) => ({ ...prev, [key]: value }))
  }

  const save = async () => {
    setBusy(true)
    const restartRequired = Object.keys(edits).some(
      (key) => entries?.find((e) => e.key === key)?.restartRequired,
    )
    try {
      const r = await api.saveSettings(edits)
      setEntries(r.settings)
      setEdits({})
      setSaved({ restartRequired })
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!entries) return <Spinner />

  const pending = Object.keys(edits).length

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>Settings</h3>
          <p className={panels.hint}>
            Limits, timeouts and allowlists for this organization. A blank field falls back to what
            this deployment was started with, and then to the default.
          </p>
        </div>
        <button className={styles.saveBtn} disabled={busy || pending === 0} onClick={() => void save()}>
          {busy ? 'Saving…' : pending ? `Save ${pending} change${pending > 1 ? 's' : ''}` : 'Save'}
        </button>
      </div>

      <ThemeSetting />

      <LicensePanel />

      {saved && (
        <p className={styles.savedNote}>
          Saved.{' '}
          {saved.restartRequired
            ? 'Restart Concentus for it to take effect — these are read once, when the application starts.'
            : 'In effect now.'}
        </p>
      )}

      {groups.map(([group, items]) => (
        <section key={group} className={styles.settingGroup}>
          <h4 className={styles.h4}>{group}</h4>
          <div className={styles.settingList}>
            {items.map((entry) => (
              <SettingRow
                key={entry.key}
                entry={entry}
                draft={edits[entry.key]}
                onChange={(value) => change(entry.key, value)}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

function SettingRow({
  entry,
  draft,
  onChange,
}: {
  entry: SettingEntry
  draft: string | undefined
  onChange: (value: string) => void
}) {
  const value = draft ?? entry.value
  const id = `setting-${entry.key}`
  const edited = draft !== undefined && draft !== entry.value

  return (
    <div className={edited ? `${styles.settingRow} ${styles.settingEdited}` : styles.settingRow}>
      <div className={styles.settingText}>
        <label htmlFor={id}>{entry.label}</label>
        <p>{entry.help}</p>
        <span className={styles.settingMeta}>
          {SOURCE_LABEL[entry.source] ?? entry.source}
          {entry.restartRequired && ' · needs a restart'}
        </span>
      </div>
      <div className={styles.settingControl}>
        {entry.type === 'BOOLEAN' ? (
          <select id={id} value={value || 'false'} onChange={(e) => onChange(e.target.value)}>
            <option value="true">On</option>
            <option value="false">Off</option>
          </select>
        ) : entry.type === 'CHOICE' ? (
          <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
            {/* Blank is a real choice here: it is how a field goes back to the deployment's own
                value rather than being pinned to one of the options. */}
            <option value="">(unset)</option>
            {entry.options.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        ) : (
          <input
            id={id}
            type={entry.type === 'NUMBER' ? 'number' : entry.type === 'SECRET' ? 'password' : 'text'}
            value={value}
            placeholder={
              entry.type === 'SECRET' && entry.hasValue
                ? '•••••••• (unchanged)'
                : entry.type === 'LIST'
                  ? 'comma, separated'
                  : ''
            }
            onChange={(e) => onChange(e.target.value)}
          />
        )}
      </div>
    </div>
  )
}
