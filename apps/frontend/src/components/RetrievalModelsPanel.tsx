import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { EmbedderStatus, RerankerStatus } from '../api/types.ts'
import styles from './resources.module.scss'

/**
 * The two optional models that make retrieval better, each one button and no server.
 *
 * Keyword search always works and needs nothing. On top of it, an embedding model adds ranking by
 * meaning, and a reranker reorders the results by reading question and passage together. Both run
 * inside the backend; only the files are fetched, and they are fetched from here so the choice —
 * and the disk space — stays the user's.
 *
 * They are shown as one block with two rows rather than two panels, because they are two steps of
 * one pipeline and a person deciding about them is deciding about the same thing: how well this
 * base will answer. They are still reported separately, because they fail independently and the
 * next action differs — without the first there is no semantic search at all, without the second
 * search works and merely ranks less well.
 */
export function RetrievalModelsPanel() {
  const { t } = useTranslation()
  const [embedder, setEmbedder] = useState<EmbedderStatus | null>(null)
  const [reranker, setReranker] = useState<RerankerStatus | null>(null)
  const downloading = useRef(false)

  useEffect(() => {
    let live = true
    const tick = async () => {
      try {
        const [e, r] = await Promise.all([api.embedderStatus(), api.rerankerStatus()])
        if (!live) return
        setEmbedder(e)
        setReranker(r)
        downloading.current = e.state === 'DOWNLOADING' || r.state === 'DOWNLOADING'
      } catch {
        if (live) setEmbedder(null)
      }
    }
    void tick()
    // Polled only while a download is in flight; otherwise this is a one-shot read.
    const timer = setInterval(() => {
      if (downloading.current) void tick()
    }, 1000)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [])

  if (!embedder) return null

  return (
    <div className={styles.embedder}>
      <ModelRow
        state={embedder.state}
        percent={embedder.percent}
        error={embedder.error}
        onName={
          embedder.semantic && embedder.state !== 'READY'
            ? `${t('Semantic search is on via your model server.')} ${embedder.detail}`
            : t('Keyword and semantic search — {{model}}, running inside the app.', { model: embedder.model })
        }
        offName={
          embedder.semantic
            ? t('Semantic search is on via your model server.')
            : t('Keyword search only — nothing ranks by meaning yet.')
        }
        offHelp={t(
          'Keyword search finds exact words and always works. An embedding model adds meaning, so a question phrased differently from the document still finds it. Runs inside the app — no Ollama, no Docker. Documents already uploaded stay keyword-only until re-uploaded.',
        )}
        downloadingLabel={t('Downloading the embedding model')}
        action={t('Enable semantic search')}
        sizeMb={embedder.sizeMb}
        removeTitle={t('Frees the disk space. Search keeps working on keywords until you download it again.')}
        onDownload={() => {
          downloading.current = true
          void api.embedderDownload().then(() => setEmbedder({ ...embedder, state: 'DOWNLOADING', percent: 0 }))
        }}
        onRemove={() => void api.deleteEmbedder().then(() => setEmbedder({ ...embedder, state: 'NOT_DOWNLOADED' }))}
        hidden={embedder.semantic && embedder.state === 'NOT_DOWNLOADED'}
      />

      {reranker && (
        <ModelRow
          state={reranker.state}
          percent={reranker.percent}
          error={reranker.error}
          onName={t('Results reordered by {{model}}, running inside the app.', { model: reranker.model })}
          offName={t('Results are not reordered.')}
          offHelp={t(
            'A reranker reads your question and each candidate passage together and scores the pair, which catches what two separately-computed embeddings cannot — the passage describing the right process for the wrong department. It runs over the handful of candidates a search already found, so it costs milliseconds, not a scan.',
          )}
          downloadingLabel={t('Downloading the reranker')}
          action={t('Enable reranking')}
          sizeMb={reranker.sizeMb}
          removeTitle={t('Frees the disk space. Search keeps working; results simply go back to the fused order.')}
          onDownload={() => {
            downloading.current = true
            void api.rerankerDownload().then(() => setReranker({ ...reranker, state: 'DOWNLOADING', percent: 0 }))
          }}
          onRemove={() => void api.deleteReranker().then(() => setReranker({ ...reranker, state: 'NOT_DOWNLOADED' }))}
        />
      )}
    </div>
  )
}

/** One model: on, downloading, or offered. Same three states for both, so the same row draws them. */
function ModelRow(props: {
  state: 'NOT_DOWNLOADED' | 'DOWNLOADING' | 'READY' | 'ERROR'
  percent: number
  error: string
  onName: string
  offName: string
  offHelp: string
  downloadingLabel: string
  action: string
  sizeMb: number
  removeTitle: string
  onDownload: () => void
  onRemove: () => void
  hidden?: boolean
}) {
  const { t } = useTranslation()
  if (props.hidden) return null

  if (props.state === 'READY') {
    return (
      <div className={styles.embedderRow}>
        <span className={styles.okDot}>●</span>
        <span>{props.onName}</span>
        <button className={styles.textLink} title={props.removeTitle} onClick={props.onRemove}>
          {t('Remove')}
        </button>
      </div>
    )
  }

  if (props.state === 'DOWNLOADING') {
    return (
      <div className={styles.embedderRow}>
        <progress className={styles.progress} value={props.percent} max={100} />
        <span className={styles.muted}>
          {props.downloadingLabel}… {props.percent}%
        </span>
      </div>
    )
  }

  return (
    <div className={styles.embedderRow}>
      <span className={styles.muted} title={props.offHelp}>
        {props.offName} ⓘ
      </span>
      <button className={styles.newBtn} onClick={props.onDownload}>
        {props.action}
      </button>
      <span className={styles.muted} title={props.offHelp}>
        {t('One-time {{size}} MB download · runs offline', { size: props.sizeMb })} ⓘ
      </span>
      {props.state === 'ERROR' && <span className={styles.errText}>{props.error}</span>}
    </div>
  )
}
