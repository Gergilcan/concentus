import { type CSSProperties, useEffect, useMemo, useRef, useState } from 'react'
import { api, openRunSocket, type RunSocketStatus } from '../api/client.ts'
import type { RunEvent } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { cx } from '../utils/cx.ts'
import styles from './runs.module.scss'

function fmt(ts: number): string {
  return new Date(ts).toLocaleTimeString()
}

/** Stable hue per agent name, so an agent keeps the same colour for the whole run. */
function hueOf(name: string): number {
  let h = 0
  for (let i = 0; i < name.length; i += 1) h = (h * 31 + name.charCodeAt(i)) % 360
  return h
}

export function Console({ runId }: { runId: string }) {
  const [events, setEvents] = useState<RunEvent[]>([])
  const [cmd, setCmd] = useState('')
  const [sending, setSending] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [connStatus, setConnStatus] = useState<RunSocketStatus>('connecting')
  const bottomRef = useRef<HTMLDivElement>(null)

  const [agentFilter, setAgentFilter] = useState<string | null>(null)

  useEffect(() => {
    setEvents([])
    setAgentFilter(null)
    setConnStatus('connecting')
    const handle = openRunSocket(runId, (e) => setEvents((prev) => [...prev, e]), setConnStatus)
    return () => handle.close()
  }, [runId])

  // Every agent seen so far. Built from the events themselves so an agent appears
  // as soon as it produces output, without needing the compiled flow here.
  const agents = useMemo(() => {
    const seen = new Set<string>()
    for (const e of events) if (e.agent) seen.add(e.agent)
    return [...seen].sort()
  }, [events])

  const shown = useMemo(
    () => (agentFilter ? events.filter((e) => e.agent === agentFilter) : events),
    [events, agentFilter],
  )

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
      {agents.length > 1 && (
        <div className={styles.agentBar}>
          <button
            className={cx(styles.agentChip, !agentFilter && styles.agentChipOn)}
            onClick={() => setAgentFilter(null)}
          >
            All agents
          </button>
          {agents.map((a) => (
            <button
              key={a}
              className={cx(styles.agentChip, agentFilter === a && styles.agentChipOn)}
              style={{ '--h': hueOf(a) } as CSSProperties}
              onClick={() => setAgentFilter(agentFilter === a ? null : a)}
            >
              {a}
            </button>
          ))}
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
        {agentFilter && shown.length === 0 && (
          <div className={styles.logMuted}>No output from {agentFilter} yet.</div>
        )}
        {shown.map((e, i) => (
          <div key={i} className={cx(styles.line, styles['t_' + e.type])}>
            <span className={styles.lts}>{fmt(e.ts)}</span>
            {e.agent && (
              <span className={styles.who} style={{ '--h': hueOf(e.agent) } as CSSProperties}>
                {e.agent}
              </span>
            )}
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
