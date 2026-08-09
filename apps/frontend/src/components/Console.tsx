import { type CSSProperties, useEffect, useMemo, useRef, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
import { api, openRunSocket, type RunSocketStatus } from '../api/client.ts'
import type { RunStatus } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { clockTime, money } from '../utils/format.ts'
import { agentKey } from '../utils/agentKey.ts'
import { cx } from '../utils/cx.ts'
import styles from './runs.module.scss'

/** Stable hue per agent name, so an agent keeps the same colour for the whole run. */
function hueOf(name: string): number {
  let h = 0
  for (let i = 0; i < name.length; i += 1) h = (h * 31 + name.charCodeAt(i)) % 360
  return h
}

export function Console({ runId, status }: { runId: string; status?: RunStatus }) {
  // Stopping only means something while something is running. STARTING and RUNNING are the states
  // with work to interrupt; IDLE is a turn-based run waiting for its next command, with no process
  // to kill, and TERMINATED and ERROR are over. Offering the button there invites a click that
  // does nothing and leaves the user unsure whether it worked.
  const canStop = status === 'STARTING' || status === 'RUNNING'
  // Events live in the store so a node's inspector can render its own agent's slice
  // of the same stream — one socket, many views.
  const events = useFlowStore((s) => s.runEvents)
  const addRunEvent = useFlowStore((s) => s.addRunEvent)
  const clearRunEvents = useFlowStore((s) => s.clearRunEvents)
  const [cmd, setCmd] = useState('')
  const [sending, setSending] = useState(false)
  const [deciding, setDeciding] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [connStatus, setConnStatus] = useState<RunSocketStatus>('connecting')
  const bottomRef = useRef<HTMLDivElement>(null)

  const [agentFilter, setAgentFilter] = useState<string | null>(null)

  useEffect(() => {
    clearRunEvents()
    setAgentFilter(null)
    setConnStatus('connecting')
    const handle = openRunSocket(runId, addRunEvent, setConnStatus)
    return () => handle.close()
  }, [runId, addRunEvent, clearRunEvents])

  // Every agent seen so far, keyed by node id so two agents sharing a display name stay
  // separate. Built from the events themselves, so an agent appears as soon as it speaks.
  const agents = useMemo(() => {
    const byId = new Map<string, string>()
    for (const e of events) {
      const id = agentKey(e)
      if (id) byId.set(id, e.agent ?? id)
    }
    return [...byId.entries()]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name))
  }, [events])

  const shown = useMemo(
    () => (agentFilter ? events.filter((e) => agentKey(e) === agentFilter) : events),
    [events, agentFilter],
  )
  const filteredName = agents.find((a) => a.id === agentFilter)?.name ?? agentFilter

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
      setErr(errMessage(e))
    } finally {
      setSending(false)
    }
  }

  const decide = async (choice: 'approve' | 'reject') => {
    setDeciding(true)
    setErr(null)
    try {
      await (choice === 'approve' ? api.approveRun(runId) : api.rejectRun(runId))
    } catch (e) {
      setErr(errMessage(e))
    } finally {
      setDeciding(false)
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
      setErr(errMessage(e))
    }
  }

  const totals = useFlowStore((s) => s.runTotals)
  const hasTotals = totals.input > 0 || totals.output > 0

  return (
    <div className={styles.console}>
      {hasTotals && (
        <div className={styles.tokenBar}>
          Σ execution tokens · in {totals.input.toLocaleString()} · out {totals.output.toLocaleString()}
          {totals.costUsd > 0 && (
            <span title="Sum of each block priced at its own model's rate, with cached tokens weighted. Runs on a Claude subscription have no per-token bill — treat this as equivalent usage.">
              {' '}· ≈{money(totals.costUsd)}
            </span>
          )}
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
              key={a.id}
              className={cx(styles.agentChip, agentFilter === a.id && styles.agentChipOn)}
              style={{ '--h': hueOf(a.name) } as CSSProperties}
              onClick={() => setAgentFilter(agentFilter === a.id ? null : a.id)}
            >
              {a.name}
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
          <div className={styles.logMuted}>No output from {filteredName} yet.</div>
        )}
        {shown.map((e, i) => (
          <div key={i} className={cx(styles.line, styles['t_' + e.type])}>
            <span className={styles.lts}>{clockTime(e.ts)}</span>
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

      {status === 'AWAITING_APPROVAL' && (
        <div className={styles.approvalRow}>
          <span>
            <b>Waiting for your approval.</b> The plan is above; nothing has been changed yet.
          </span>
          <button className={styles.sendBtn} onClick={() => void decide('approve')} disabled={deciding}>
            Approve
          </button>
          <button className={styles.stopBtn} onClick={() => void decide('reject')} disabled={deciding}>
            Reject
          </button>
        </div>
      )}

      <div className={styles.cmdRow}>
        <input
          value={cmd}
          onChange={(e) => setCmd(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void send()
          }}
          placeholder="Send a command to the running agents…"
        />
        <button className={styles.sendBtn} onClick={() => void send()} disabled={sending}>
          Send
        </button>
        <button
          className={styles.stopBtn}
          onClick={() => void stop()}
          disabled={!canStop}
          title={canStop ? 'Stop the agents' : 'Nothing is running to stop'}
        >
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
