import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { BackendFlow, FlowVersionInfo } from '../api/types.ts'
import { Modal } from './Modal.tsx'
import { timeAgo } from './flowFormat.ts'
import styles from './flows.module.scss'

/** Flow-level settings: name, tags, schedule pause, failure webhook. */
export function SettingsModal({
  flow,
  onClose,
  onSave,
}: {
  flow: BackendFlow
  onClose: () => void
  onSave: (changes: Partial<BackendFlow>) => Promise<void>
}) {
  const [name, setName] = useState(flow.name)
  const [tags, setTags] = useState((flow.tags ?? []).join(', '))
  const [enabled, setEnabled] = useState(flow.enabled !== false)
  const [webhook, setWebhook] = useState(flow.notifyWebhook ?? '')
  const [busy, setBusy] = useState(false)

  const save = async () => {
    setBusy(true)
    await onSave({
      name: name.trim() || flow.name,
      tags: tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean),
      enabled,
      notifyWebhook: webhook.trim(),
    })
    setBusy(false)
  }

  return (
    <Modal title="Flow settings" onClose={onClose}>
      <label className={styles.field}>
        <span>Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label className={styles.field}>
        <span>Tags (comma separated)</span>
        <input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="ops, nightly" />
      </label>
      <label className={styles.toggleRow}>
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        <span>
          Enabled — when off, scheduled (cron) runs are paused. Manual runs still work.
        </span>
      </label>
      <label className={styles.field}>
        <span>Failure notification webhook</span>
        <input
          value={webhook}
          onChange={(e) => setWebhook(e.target.value)}
          placeholder="https://hooks.slack.com/services/…"
        />
      </label>
      <p className={styles.modalHint}>
        POSTed with a Slack-compatible <code>text</code> field plus run details whenever an execution
        of this flow fails.
      </p>
      <div className={styles.modalActions}>
        <button className={styles.ghost} onClick={onClose}>
          Cancel
        </button>
        <button className={styles.primary} onClick={() => void save()} disabled={busy}>
          {busy ? 'Saving…' : 'Save'}
        </button>
      </div>
    </Modal>
  )
}

/** Version history with one-click rollback. */
export function VersionsModal({
  flow,
  onClose,
  pushError,
}: {
  flow: BackendFlow
  onClose: () => void
  pushError: (m: string) => void
}) {
  const [versions, setVersions] = useState<FlowVersionInfo[] | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!flow.id) {
      setVersions([])
      return
    }
    api
      .listFlowVersions(flow.id)
      .then(setVersions)
      .catch(() => setVersions([]))
  }, [flow.id])

  const restore = async (version: number) => {
    if (!flow.id) return
    if (!confirm(`Restore version ${version}? The current version is kept in history.`)) return
    setBusy(true)
    try {
      await api.restoreFlowVersion(flow.id, version)
      onClose()
    } catch (e) {
      pushError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={`History — ${flow.name}`} onClose={onClose}>
      {versions === null ? (
        <div className={styles.sideEmpty}>Loading…</div>
      ) : versions.length === 0 ? (
        <div className={styles.sideEmpty}>
          No history yet. Every save from now on adds a restorable version.
        </div>
      ) : (
        <ul className={styles.versionList}>
          {versions.map((v, i) => (
            <li key={v.version} className={styles.versionRow}>
              <span className={styles.versionNum}>v{v.version}</span>
              <span className={styles.versionName}>{v.name}</span>
              <span className={styles.versionTime}>{timeAgo(v.createdAt)}</span>
              {i === 0 ? (
                <span className={styles.versionCurrent}>current</span>
              ) : (
                <button
                  className={styles.ghost}
                  disabled={busy}
                  onClick={() => void restore(v.version)}
                >
                  Restore
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Modal>
  )
}
