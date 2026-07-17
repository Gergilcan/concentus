import { useEffect, useRef, useState } from 'react'
import { api, openRunSocket } from '../api/client.ts'
import type { RunEvent } from '../api/types.ts'
import styles from './runs.module.scss'

function fmt(ts: number): string {
  return new Date(ts).toLocaleTimeString()
}

export function Console({ runId }: { runId: string }) {
  const [events, setEvents] = useState<RunEvent[]>([])
  const [cmd, setCmd] = useState('')
  const [sending, setSending] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setEvents([])
    const ws = openRunSocket(runId, (e) => setEvents((prev) => [...prev, e]))
    return () => ws.close()
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

  return (
    <div className={styles.console}>
      <div className={styles.log}>
        {events.length === 0 && <div className={styles.logMuted}>Waiting for output…</div>}
        {events.map((e, i) => (
          <div key={i} className={`${styles.line} ${styles['t_' + e.type]}`}>
            <span className={styles.lts}>{fmt(e.ts)}</span>
            <span className={styles.ltext}>{e.text}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

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
      </div>
    </div>
  )
}
