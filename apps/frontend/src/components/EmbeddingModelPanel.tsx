import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { EmbedderStatus } from '../api/types.ts'
import styles from './resources.module.scss'

/**
 * The built-in embedding model: one button, no server, no Docker.
 *
 * Semantic search used to require standing up Ollama, which for most people meant it stayed off.
 * The model runs inside the backend; only the file is fetched, and it is fetched from here so the
 * choice — and the ~130 MB — stays the user's.
 */
export function EmbeddingModelPanel() {
  const [status, setStatus] = useState<EmbedderStatus | null>(null)
  const wasDownloading = useRef(false)

  useEffect(() => {
    let live = true
    const tick = async () => {
      try {
        const s = await api.embedderStatus()
        if (!live) return
        setStatus(s)
        wasDownloading.current = s.state === 'DOWNLOADING'
      } catch {
        if (live) setStatus(null)
      }
    }
    void tick()
    // Polled only while the download is in flight; otherwise this is a one-shot read.
    const timer = setInterval(() => {
      if (wasDownloading.current) void tick()
    }, 1000)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [])

  if (!status) return null

  return (
    <div className={styles.embedder}>
      {status.state === 'READY' && (
        <div className={styles.embedderRow}>
          <span className={styles.okDot}>●</span>
          <span>
            Semantic search is on — <b>{status.model}</b>, running inside the app.
          </span>
          <button
            className={styles.textLink}
            title="Frees the disk space. Retrieval falls back to word overlap until you download it again."
            onClick={() => void api.deleteEmbedder().then(() => setStatus({ ...status, state: 'NOT_DOWNLOADED' }))}
          >
            Remove
          </button>
        </div>
      )}

      {status.state === 'DOWNLOADING' && (
        <div className={styles.embedderRow}>
          <progress className={styles.progress} value={status.percent} max={100} />
          <span className={styles.muted}>Downloading the embedding model… {status.percent}%</span>
        </div>
      )}

      {(status.state === 'NOT_DOWNLOADED' || status.state === 'ERROR') && (
        <div className={styles.embedderRow}>
          {status.semantic ? (
            <span className={styles.muted} title={status.detail}>
              Semantic search is on via your model server. ⓘ
            </span>
          ) : (
            <span
              className={styles.muted}
              title="Without an embedding model, retrieval ranks by shared words instead of meaning. The button fixes that — or serve bge-m3 from Ollama, which is picked up automatically."
            >
              Ranking by word overlap. ⓘ
            </span>
          )}
          <button className={styles.newBtn} onClick={() => void api.embedderDownload().then(() => setStatus({ ...status, state: 'DOWNLOADING', percent: 0 }))}>
            Enable semantic search
          </button>
          <span
            className={styles.muted}
            title="Downloads a small multilingual embedding model (~130 MB) that runs inside the app — no Ollama, no Docker. Ranking by meaning instead of shared words. Documents already uploaded keep word-overlap ranking until re-uploaded."
          >
            One-time {status.sizeMb} MB download · runs offline ⓘ
          </span>
          {status.state === 'ERROR' && <span className={styles.errText}>{status.error}</span>}
        </div>
      )}
    </div>
  )
}
