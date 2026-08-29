import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { api } from '../api/client.ts'
import type { PatchStats, RunDiff } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { type DiffFile, hasChanges, parsePatch, patchFileName, sumStats } from './diff.ts'
import styles from './diff.module.scss'

/**
 * Past this many lines a file is a generated artefact, not something read on a side panel; the
 * rest stays in the downloadable patch.
 */
const MAX_LINES_PER_FILE = 1500
/** Up to this many files open expanded; a bigger change starts folded so its list is scannable. */
const OPEN_ALL_UP_TO = 6

const STATUS_LABEL: Record<DiffFile['status'], string | null> = {
  modified: null,
  added: 'new file',
  deleted: 'deleted',
  renamed: 'renamed',
}

function filesLabel(t: TFunction, n: number): string {
  return n === 1 ? t('{{n}} file changed', { n }) : t('{{n}} files changed', { n })
}

function StatsLine({ stats }: { stats: PatchStats }) {
  const { t } = useTranslation()
  return (
    <>
      {filesLabel(t, stats.files)} · <span className={styles.plus}>+{stats.additions}</span>{' '}
      <span className={styles.minus}>−{stats.deletions}</span>
    </>
  )
}

/** One file of the patch, folded under its own head line. */
function FileSection({ file, open }: { file: DiffFile; open: boolean }) {
  const { t } = useTranslation()
  const shown = file.lines.slice(0, MAX_LINES_PER_FILE)
  const hidden = file.lines.length - shown.length
  const status = STATUS_LABEL[file.status]
  return (
    <details className={styles.file} open={open}>
      <summary className={styles.fileHead}>
        {status && <span className={cx(styles.status, styles['s_' + file.status])}>{t(status)}</span>}
        <code className={styles.path} title={file.path}>
          {file.from ? `${file.from} → ${file.path}` : file.path}
        </code>
        <span className={styles.fileStats}>
          <span className={styles.plus}>+{file.additions}</span> <span className={styles.minus}>−{file.deletions}</span>
        </span>
      </summary>
      {file.binary ? (
        <div className={styles.binary}>{t('Binary file')}</div>
      ) : (
        <pre className={styles.body}>
          {shown.map((l, i) => (
            <span key={i} className={styles['l_' + l.kind]}>
              {l.text}
            </span>
          ))}
          {hidden > 0 && (
            <span className={styles.more}>
              {t('… {{n}} more lines — download the patch to see them all', { n: hidden })}
            </span>
          )}
        </pre>
      )}
    </details>
  )
}

/**
 * One checkout's diff: who changed it, which checkout, the numbers, the files.
 *
 * The download goes through fetch and a blob rather than a plain link: the app is not sandboxed,
 * and a link would leave the session cookie's behaviour to the browser's download machinery. The
 * note is shown as the backend wrote it — like a run event, it is a fact about this run, not UI.
 */
export function DiffView({ runId, diff }: { runId: string; diff: RunDiff }) {
  const { t } = useTranslation()
  const files = useMemo(() => parsePatch(diff.patch), [diff.patch])
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const download = async () => {
    setBusy(true)
    setErr(null)
    try {
      const blob = await api.fetchRunPatch(runId, diff.nodeId, diff.folder)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = patchFileName(diff)
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e) {
      setErr(t('Could not download the patch: {{error}}', { error: errMessage(e) }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={styles.diff} aria-label={`${diff.label} · ./${diff.folder}`}>
      <header className={styles.head}>
        <span className={styles.who}>{diff.label}</span>
        <code className={styles.folder} title={diff.repoUrl ?? undefined}>
          ./{diff.folder}
        </code>
        <span className={styles.stats}>
          <StatsLine stats={diff.stats} />
        </span>
        {diff.patch && (
          <button type="button" className={styles.button} onClick={download} disabled={busy}>
            {t('Download .patch')}
          </button>
        )}
      </header>
      {diff.note && <div className={styles.note}>{diff.note}</div>}
      {err && <div className={styles.error}>{err}</div>}
      {!diff.patch && !diff.note && (
        <div className={styles.empty}>{t('No changes in ./{{folder}}.', { folder: diff.folder })}</div>
      )}
      {files.map((f) => (
        <FileSection key={`${f.from ?? ''}→${f.path}`} file={f} open={files.length <= OPEN_ALL_UP_TO} />
      ))}
    </section>
  )
}

/** Several checkouts' diffs, one after the other — a block's tab, a worker's section. */
export function DiffList({ runId, diffs }: { runId: string; diffs: RunDiff[] }) {
  return (
    <div>
      {diffs.map((d) => (
        <DiffView key={`${d.nodeId}/${d.folder}`} runId={runId} diff={d} />
      ))}
    </div>
  )
}

/**
 * Every diff of a run under one line of totals — the console's Changes view. Refresh re-reads
 * the checkouts as they stand on disk now, which matters while a run is live: the poll behind
 * this view is deliberately slow, since each read is git walking every clone.
 */
export function RunChanges({
  runId,
  diffs,
  onRefresh,
  refreshing,
}: {
  runId: string
  diffs: RunDiff[]
  onRefresh?: () => void
  refreshing?: boolean
}) {
  const { t } = useTranslation()
  const totals = useMemo(() => sumStats(diffs), [diffs])
  const any = diffs.some(hasChanges)
  return (
    <div className={styles.changes}>
      <div className={styles.summary}>
        <span>
          {diffs.length === 0
            ? t('No repository changes yet. When an agent edits a repository, its diff appears here.')
            : any
              ? <StatsLine stats={totals} />
              : t('Nothing changed in the repositories this run touched.')}
        </span>
        {onRefresh && (
          <button
            type="button"
            className={styles.button}
            onClick={onRefresh}
            disabled={refreshing}
            title={t('Read the checkouts again, as they stand on disk now.')}
          >
            {t('Refresh')}
          </button>
        )}
      </div>
      <DiffList runId={runId} diffs={diffs} />
    </div>
  )
}
