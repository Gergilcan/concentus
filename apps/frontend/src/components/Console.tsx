import { useEffect, useRef, useState } from 'react'
import { api, openRunSocket, type RunSocketStatus } from '../api/client.ts'
import type { RunEvent } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import styles from './runs.module.scss'

function fmt(ts: number): string {
  return new Date(ts).toLocaleTimeString()
}

export function Console({ runId }: { runId: string }) {
  const [events, setEvents] = useState<RunEvent[]>([])
  const [cmd, setCmd] = useState('')
  const [sending, setSending] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [connStatus, setConnStatus] = useState<RunSocketStatus>('connecting')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setEvents([])
    setConnStatus('connecting')
    const handle = openRunSocket(runId, (e) => setEvents((prev) => [...prev, e]), setConnStatus)
    return () => handle.close()
  }, [runId])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [events])

  const send = async () => {
    const text = cmd.trim()
    if (!text) return
    setSending(true)
    setErr(null)
    try {
      await api.sendCommand(runId, text)
      setCmd('')
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    } finally {
      setSending(false)
    }
  }

  const stop = async () => {
    try {
      await api.stopRun(runId)
    } catch {
      /* ignore */
    }
  }

  const retry = async () => {
    setErr(null)
    try {
      await api.retryRun(runId)
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    }
  }

  const totals = useFlowStore((s) => s.runTotals)
  const hasTotals = totals.input > 0 || totals.output > 0

  return (
    <div className={styles.console}>
      {hasTotals && (
        <div className={styles.tokenBar}>
          Σ execution tokens · in ≈{totals.input.toLocaleString()} · out {totals.output.toLocaleString()}
        </div>
      )}
      <div className={styles.log}>
        {events.length === 0 && (
          <div className={styles.logMuted}>
            {connStatus === 'reconnecting'
              ? 'Reconnecting…'
              : connStatus === 'disconnected'
                ? 'Disconnected from run output.'
                : 'Waiting for output…'}
          </div>
        )}
        {events.map((e, i) => (
          <div key={i} className={`${styles.line} ${styles['t_' + e.type]}`}>
            <span className={styles.lts}>{fmt(e.ts)}</span>
            <span className={styles.ltext}>{e.text}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {connStatus === 'reconnecting' && events.length > 0 && (
        <div className={styles.err}>Connection lost — reconnecting…</div>
      )}
      {connStatus === 'disconnected' && events.length > 0 && (
        <div className={styles.err}>Disconnected from run output.</div>
      )}

      {err && <div className={styles.err}>{err}</div>}

      <div className={styles.cmdRow}>
        <input
          value={cmd}
          onChange={(e) => setCmd(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void send()
          }}
          placeholder="Send a command to the running agents…"
        />
        <button onClick={() => void send()} disabled={sending}>
          Send
        </button>
        <button className={styles.stopBtn} onClick={() => void stop()}>
          Stop
        </button>
        <button
          className={styles.retryBtn}
          title="Re-run this execution's flow with the same initial input"
          onClick={() => void retry()}
        >
          ⟳ Retry
        </button>
      </div>
    </div>
  )
}
