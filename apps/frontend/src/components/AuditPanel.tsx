import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { AuditEvent, AuditFilters, AuditStatus } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/** Rows per request. A page, not the whole trail: the trail is the one table that only grows. */
const PAGE = 100

/**
 * `{"version":3,"trigger":"cron"}` as `version: 3 · trigger: cron` — the detail is for a person
 * scanning a row, and the raw JSON is one click away in the export.
 */
function detailLine(detail: string | null): string {
  if (!detail) return ''
  try {
    const parsed = JSON.parse(detail) as Record<string, unknown>
    return Object.entries(parsed)
      .filter(([, v]) => v !== null && v !== undefined && v !== '')
      .map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(' → ') : String(v)}`)
      .join(' · ')
  } catch {
    return detail
  }
}

/**
 * Who did what, and when.
 *
 * <p>Runs already said who started them and versions who saved them; that answers "who changed
 * this flow" one record at a time. This panel answers the other question — what did this person
 * do last Tuesday, who touched the credentials this quarter — across every kind of record at
 * once. Readable by an administrator on every tier: reading what your own people did is half the
 * reason to have members. Taking it out as a file is the Enterprise half, and the button says so
 * with the backend's own sentence rather than by failing when pressed.
 *
 * <p>The retention in force is stated here, next to the trail it applies to, because the trail is
 * where its absence would be noticed: a row that is not there is either something nobody did or
 * something the policy removed, and the reader must be able to tell which.
 */
export function AuditPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const [status, setStatus] = useState<AuditStatus | null>(null)
  const [events, setEvents] = useState<AuditEvent[] | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [nextBefore, setNextBefore] = useState<number | null>(null)
  // What the fields hold, and what the list was last loaded with. Two states so typing an actor
  // does not fire a request per keystroke; Apply (or Enter) copies one into the other.
  const [draft, setDraft] = useState<AuditFilters>({ actor: '', kind: '', from: '', to: '' })
  const [filters, setFilters] = useState<AuditFilters>({ actor: '', kind: '', from: '', to: '' })
  const [busy, setBusy] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)

  useEffect(() => {
    api
      .auditStatus()
      .then(setStatus)
      .catch((e) => pushError(errMessage(e)))
  }, [pushError])

  const load = useCallback(
    (active: AuditFilters, before?: number) => {
      setBusy(before === undefined ? 'load' : 'more')
      api
        .listAudit(active, before, PAGE)
        .then((page) => {
          setEvents((prev) => (before === undefined || !prev ? page.events : [...prev, ...page.events]))
          setHasMore(page.hasMore)
          setNextBefore(page.nextBefore)
        })
        .catch((e) => {
          if (before === undefined) setEvents([])
          pushError(errMessage(e))
        })
        .finally(() => setBusy(null))
    },
    [pushError],
  )

  useEffect(() => load(filters), [filters, load])

  const apply = () => setFilters({ ...draft })

  const exportAs = async (format: 'csv' | 'json') => {
    setBusy(format)
    setNote(null)
    try {
      const blob = await api.exportAudit(format, filters)
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = `concentus-audit-${new Date().toISOString().slice(0, 10)}.${format}`
      a.click()
      URL.revokeObjectURL(a.href)
      setNote(t('Exported.'))
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const purgeNow = async () => {
    setBusy('purge')
    setNote(null)
    try {
      const report = await api.runRetentionNow()
      const total = report.runs + report.versions + report.auditEvents
      setNote(
        total === 0
          ? t('Nothing to purge.')
          : t('Purged {{runs}} runs, {{versions}} flow versions and {{events}} audit events.', {
              runs: report.runs,
              versions: report.versions,
              events: report.auditEvents,
            }),
      )
      load(filters)
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  if (!events || !status) return <Spinner />

  const exportBlocked = status.exportRefusal != null

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Audit trail')}</h3>
          <p className={panels.hint}>
            {t(
              'Who did what, and when: every run, save, decision, invitation, credential and setting change, on record.',
            )}
          </p>
        </div>
        <div className={styles.auditExport}>
          <button
            className={styles.newBtn}
            disabled={exportBlocked || busy === 'csv'}
            title={status.exportRefusal ?? undefined}
            onClick={() => void exportAs('csv')}
          >
            {t('Export CSV')}
          </button>
          <button
            className={styles.newBtn}
            disabled={exportBlocked || busy === 'json'}
            title={status.exportRefusal ?? undefined}
            onClick={() => void exportAs('json')}
          >
            {t('Export JSON')}
          </button>
        </div>
      </div>

      {/* The gate, in the backend's words: the button being grey is not an explanation. */}
      {exportBlocked && <p className={styles.auditGate}>{status.exportRefusal}</p>}

      {!status.available && (
        <p className={styles.emptyRoster}>
          {t(
            'Recording is unavailable: the database could not be reached, so nothing is being written to the trail.',
          )}
        </p>
      )}

      <div className={styles.auditRetention}>
        <span>
          <b>{t('Retention')}</b> — {status.retentionReason}
        </span>
        {status.retentionDays != null && (
          <button className={styles.linkBtn} disabled={busy === 'purge'} onClick={() => void purgeNow()}>
            {busy === 'purge' ? t('Purging…') : t('Apply retention now')}
          </button>
        )}
      </div>

      <div className={styles.auditFilters}>
        <label className={styles.field}>
          <span>{t('Actor')}</span>
          <input
            value={draft.actor ?? ''}
            placeholder={t('anyone — or system:')}
            onChange={(e) => setDraft({ ...draft, actor: e.target.value })}
            onKeyDown={(e) => {
              if (e.key === 'Enter') apply()
            }}
          />
        </label>
        <label className={styles.field}>
          <span>{t('Kind')}</span>
          <select value={draft.kind ?? ''} onChange={(e) => setDraft({ ...draft, kind: e.target.value })}>
            <option value="">{t('All kinds')}</option>
            {status.kinds.map((k) => (
              <option key={k} value={k}>
                {k}
              </option>
            ))}
          </select>
        </label>
        <label className={styles.field}>
          <span>{t('From')}</span>
          <input type="date" value={draft.from ?? ''} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </label>
        <label className={styles.field}>
          <span>{t('To')}</span>
          <input type="date" value={draft.to ?? ''} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </label>
        <button className={styles.saveBtn} disabled={busy === 'load'} onClick={apply}>
          {t('Apply')}
        </button>
      </div>

      {note && <p className={panels.hint}>{note}</p>}

      {events.length === 0 ? (
        <p className={styles.emptyRoster}>{t('Nothing recorded yet — or nothing matches these filters.')}</p>
      ) : (
        <div className={styles.auditTableWrap}>
          <table className={styles.auditTable}>
            <thead>
              <tr>
                <th>{t('When')}</th>
                <th>{t('Who')}</th>
                <th>{t('What')}</th>
                <th>{t('Subject')}</th>
                <th>{t('Detail')}</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.id}>
                  <td title={new Date(e.at).toISOString()}>{timeAgo(e.at)}</td>
                  <td>
                    <span className={styles.memberEmail}>{e.actorEmail ?? '—'}</span>
                    {e.actorRole && <span className={styles.auditRole}>{e.actorRole}</span>}
                  </td>
                  <td>
                    <code className={styles.auditKind}>{e.kind}</code>
                  </td>
                  <td title={e.subjectId ?? undefined}>{e.subjectLabel ?? e.subjectId ?? '—'}</td>
                  <td className={styles.auditDetail}>{detailLine(e.detail)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {hasMore && nextBefore != null && (
        <div className={styles.auditFoot}>
          <button className={styles.newBtn} disabled={busy === 'more'} onClick={() => load(filters, nextBefore)}>
            {busy === 'more' ? t('Loading…') : t('Load more')}
          </button>
        </div>
      )}
    </div>
  )
}
