import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { api } from '../api/client.ts'
import type { FlowVersionInfo } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import styles from './flowVersions.module.scss'

/**
 * What changed against the previous revision, in the few characters a list row can spare.
 * Empty when nothing measurable changed — a save that only edited a prompt is a real save with
 * no shape change, and inventing "±0" for it would read as noise.
 */
function diffHint(v: FlowVersionInfo, t: TFunction): string {
  const parts: string[] = []
  if (v.nodesDelta) parts.push(t('{{delta}} nodes', { delta: `${v.nodesDelta > 0 ? '+' : ''}${v.nodesDelta}` }))
  if (v.edgesDelta) parts.push(t('{{delta}} links', { delta: `${v.edgesDelta > 0 ? '+' : ''}${v.edgesDelta}` }))
  if (v.renamed) parts.push(t('renamed'))
  return parts.join(' · ')
}

/**
 * A flow's revision history, with the two actions that make history useful: restore a revision as
 * the current flow, or preview it on the canvas without saving anything.
 *
 * <p>Shared by the dashboard's History modal and Studio's Versions tab so both show the same
 * authorship, the same diff hints and the same rules — the two had already been written once and
 * would have drifted the moment either grew a column.
 */
export function FlowVersions({
  flowId,
  onRestored,
  pushError,
}: {
  /** Null for an unsaved flow: nothing has been saved, so there is nothing to list. */
  flowId: string | null
  /** Called after a successful restore, so a modal can close itself. */
  onRestored?: () => void
  pushError: (m: string) => void
}) {
  const { t } = useTranslation()
  const [versions, setVersions] = useState<FlowVersionInfo[] | null>(null)
  const [busy, setBusy] = useState(false)
  const previewVersion = useFlowStore((s) => s.previewVersion)
  const openFlowId = useFlowStore((s) => s.flowId)

  const load = useCallback(() => {
    if (!flowId) {
      setVersions([])
      return
    }
    api
      .listFlowVersions(flowId)
      .then(setVersions)
      .catch(() => setVersions([]))
  }, [flowId])

  useEffect(load, [load])

  // Only the canvas showing THIS flow may be reloaded by a restore or a preview. From the
  // dashboard the history of any flow can be opened while Studio holds a different one.
  const canvasShowsThisFlow = !!flowId && openFlowId === flowId
  const latest = versions?.[0]?.version ?? null

  const restore = async (version: number) => {
    if (!flowId) return
    if (!confirm(t('Restore version {{version}}? The current version is kept in history.', { version }))) return
    setBusy(true)
    try {
      const saved = await api.restoreFlowVersion(flowId, version)
      if (canvasShowsThisFlow) useFlowStore.getState().loadBackendFlow(saved)
      load()
      onRestored?.()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const preview = async (version: number) => {
    if (!flowId) return
    setBusy(true)
    try {
      const flow = await api.getFlowVersion(flowId, version)
      useFlowStore.getState().loadBackendFlow(flow)
      // After the load, which clears it: the canvas is now showing the past, and says so.
      useFlowStore.getState().setPreviewVersion(version)
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const backToLatest = async () => {
    if (!flowId) return
    setBusy(true)
    try {
      useFlowStore.getState().loadBackendFlow(await api.getFlow(flowId))
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (versions === null) {
    return (
      <div className={styles.empty}>
        <Spinner />
      </div>
    )
  }

  return (
    <div className={styles.wrap}>
      {previewVersion !== null && canvasShowsThisFlow && (
        <div className={styles.previewBar}>
          <span>
            {t('Previewing v{{version}} — nothing is saved until you restore it or save the canvas.', { version: previewVersion })}
          </span>
          <button className={styles.ghost} disabled={busy} onClick={() => void backToLatest()}>
            {t('Back to latest')}
          </button>
        </div>
      )}
      {versions.length === 0 ? (
        <div className={styles.empty}>
          {t('No history yet. Every save from now on adds a restorable version.')}
        </div>
      ) : (
        <ul className={styles.list} aria-label={t('Version history')}>
          {versions.map((v) => {
            const hint = diffHint(v, t)
            return (
              <li key={v.version} className={styles.row}>
                <span className={styles.num}>v{v.version}</span>
                <span className={styles.name} title={v.name}>
                  {v.name}
                </span>
                <span className={styles.author} title={v.author ? t('Saved by {{author}}', { author: v.author }) : t('Saved before authorship was recorded')}>
                  {v.author ?? '—'}
                </span>
                <span className={styles.time}>{timeAgo(v.createdAt)}</span>
                <span className={styles.shape} title={t('{{nodes}} nodes, {{edges}} links', { nodes: v.nodeCount, edges: v.edgeCount })}>
                  {v.nodeCount}n/{v.edgeCount}e{hint && ` · ${hint}`}
                </span>
                {canvasShowsThisFlow && (
                  <button
                    className={styles.ghost}
                    disabled={busy}
                    title={t('Load this revision onto the canvas without saving it')}
                    onClick={() => void preview(v.version)}
                  >
                    {t('Preview')}
                  </button>
                )}
                {v.version === latest ? (
                  <span className={styles.current}>{t('current')}</span>
                ) : (
                  <button className={styles.ghost} disabled={busy} onClick={() => void restore(v.version)}>
                    {t('Restore')}
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
