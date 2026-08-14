import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import { shellBridge, type RuntimeInstallPlan } from '../api/shell.ts'
import type { RuntimeCheck } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Spinner } from './Spinner.tsx'
import styles from './runtimeNotice.module.scss'

/**
 * Whether the machine can actually launch a stdio MCP server — and, when it cannot, the one
 * button that fixes it.
 *
 * A server configured as `uvx some-server` does not fail when it is added. It fails on the first
 * run, deep inside the CLI, as a process that could not start — by which point the person has
 * moved on and the error names none of this. So the check happens where the command is typed.
 *
 * Detection is the backend's (it also serves the pre-run checks); installing belongs to the
 * desktop shell, which owns the fixed table of official commands. In a browser there is no shell,
 * so the notice links the tool's own instructions instead of pretending it can install anything.
 */
export function RuntimeNotice({
  command,
  onResolved,
}: {
  /** The MCP command as configured. Empty (a remote server) checks nothing and renders nothing. */
  command: string
  /** Called with whether the machine is ready, so a wizard can gate its Next button. */
  onResolved?: (satisfied: boolean) => void
}) {
  const [check, setCheck] = useState<RuntimeCheck | null>(null)
  const [plan, setPlan] = useState<RuntimeInstallPlan | null>(null)
  const [installing, setInstalling] = useState(false)
  const [output, setOutput] = useState('')
  const [error, setError] = useState<string | null>(null)
  const bridge = shellBridge()?.runtimes ?? null
  // The callback usually comes from a parent's render, so depending on it directly would re-run
  // the probe on every keystroke in the parent. The ref keeps the latest without being a dep.
  const resolved = useRef(onResolved)
  resolved.current = onResolved

  const trimmed = command.trim()

  const refresh = useCallback(
    async (force: boolean) => {
      if (!trimmed) {
        setCheck(null)
        resolved.current?.(true)
        return
      }
      try {
        const result = await api.checkRuntime(trimmed, force)
        setCheck(result)
        resolved.current?.(result.satisfied)
      } catch (e) {
        // A failed probe must not read as "your runtime is missing" — that would send someone
        // installing something they already have. Say nothing and let the run be the judge.
        setCheck(null)
        setError(errMessage(e))
        resolved.current?.(true)
      }
    },
    [trimmed],
  )

  useEffect(() => {
    // Debounced: this is wired to a text field, and every keystroke would otherwise ask the
    // backend to go looking for binaries.
    const t = setTimeout(() => void refresh(false), 400)
    return () => clearTimeout(t)
  }, [refresh])

  const missing = check?.runtime && !check.satisfied ? check.runtime : null

  // What the shell would run, fetched only when something is actually missing.
  useEffect(() => {
    if (!missing || !bridge) {
      setPlan(null)
      return
    }
    let live = true
    void bridge.plan(missing.id).then((p) => {
      if (live) setPlan(p)
    })
    return () => {
      live = false
    }
  }, [missing, bridge])

  const install = async () => {
    if (!missing || !bridge) return
    setInstalling(true)
    setOutput('')
    setError(null)
    const stop = bridge.onOutput((line) => setOutput((prev) => (prev + line).slice(-4000)))
    try {
      const result = await bridge.install(missing.id)
      if (!result.ok) setError(result.detail || 'The installer did not finish.')
      // Re-probed either way: a "failed" installer that actually put the binary in place should
      // show up as installed, and a successful one that did not must not claim otherwise.
      await refresh(true)
    } finally {
      stop()
      setInstalling(false)
    }
  }

  if (!trimmed || !check) return null

  if (check.satisfied) {
    return (
      <p className={styles.ok}>
        {check.runtime
          ? `${check.runtime.label} ${check.runtime.version} found — this server can be launched.`
          : 'This command is not one of the runtimes this app manages; nothing to check.'}
      </p>
    )
  }

  return (
    <div className={styles.missing} role="status">
      <p className={styles.head}>
        <b>{missing?.label} is not installed on this machine.</b> The run launches this server with{' '}
        <code>{check.command.split(/\s+/)[0]}</code>, so it would fail to start.
      </p>
      {bridge ? (
        <>
          {plan?.command ? (
            <>
              <p className={styles.hint}>This runs, exactly as written:</p>
              <pre className={styles.command}>{plan.command}</pre>
              <div className={styles.actions}>
                <button className={styles.install} disabled={installing} onClick={() => void install()}>
                  {installing ? <Spinner label={`Installing ${missing?.label}`} /> : `Install ${missing?.label}`}
                </button>
                <button className={styles.ghost} disabled={installing} onClick={() => void refresh(true)}>
                  Re-check
                </button>
                <a className={styles.docs} href={missing?.docsUrl} target="_blank" rel="noreferrer">
                  Instructions
                </a>
              </div>
            </>
          ) : (
            <p className={styles.hint}>
              {plan?.reason ?? 'There is no unattended installer for this on your platform.'}{' '}
              <a className={styles.docs} href={missing?.docsUrl} target="_blank" rel="noreferrer">
                Official instructions
              </a>
            </p>
          )}
        </>
      ) : (
        <p className={styles.hint}>
          Install it on this machine and press Re-check.{' '}
          <a className={styles.docs} href={missing?.docsUrl} target="_blank" rel="noreferrer">
            Official instructions
          </a>
          <button className={styles.ghost} onClick={() => void refresh(true)}>
            Re-check
          </button>
        </p>
      )}
      {output && <pre className={styles.output}>{output}</pre>}
      {error && <p className={styles.err}>{error}</p>}
    </div>
  )
}
