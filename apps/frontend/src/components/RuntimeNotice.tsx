import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { RuntimeCheck, RuntimeInstallPlan } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Spinner } from './Spinner.tsx'
import styles from './runtimeNotice.module.scss'

/**
 * Whether this machine can actually launch a local MCP server — and, when it cannot, the one
 * button that fixes it.
 *
 * A server configured as `uvx some-server` does not fail when it is added. It fails on the first
 * run, deep inside the CLI, as a process that could not start — by which point the person has
 * moved on and the error names none of this. So the check happens where the server is chosen.
 *
 * Detection and installation both go through the backend, which is the only part that can see the
 * machine. The install command is fetched separately and shown before anything runs: this pipes a
 * published one-liner into a shell, and asking someone to approve that without naming it would not
 * be a real choice.
 */
export function RuntimeNotice({
  command,
  onResolved,
}: {
  /** The MCP command as configured. Empty (a remote server) checks nothing and renders nothing. */
  command: string
  /** Called with whether the machine is ready, so a caller can gate its own step. */
  onResolved?: (satisfied: boolean) => void
}) {
  const { t } = useTranslation()
  const [check, setCheck] = useState<RuntimeCheck | null>(null)
  const [plan, setPlan] = useState<RuntimeInstallPlan | null>(null)
  const [installing, setInstalling] = useState(false)
  const [output, setOutput] = useState('')
  const [error, setError] = useState<string | null>(null)
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
    // Debounced: this can be wired to a text field, and every keystroke would otherwise ask the
    // backend to go looking for binaries.
    const timer = setTimeout(() => void refresh(false), 400)
    return () => clearTimeout(timer)
  }, [refresh])

  const missing = check?.runtime && !check.satisfied ? check.runtime : null

  // What installing it would run, fetched only once something is actually missing.
  useEffect(() => {
    if (!missing) {
      setPlan(null)
      return
    }
    let live = true
    void api
      .runtimeInstallPlan(missing.id)
      .then((p) => live && setPlan(p))
      .catch(() => live && setPlan(null))
    return () => {
      live = false
    }
  }, [missing])

  const install = async () => {
    if (!missing) return
    setInstalling(true)
    setOutput('')
    setError(null)
    try {
      const result = await api.installRuntime(missing.id)
      setOutput(result.output)
      if (!result.ok) setError(t('The installer did not finish. Its output is above.'))
      // Re-probed either way: an installer that reported failure may still have put the binary in
      // place, and one that reported success may not have.
      await refresh(true)
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setInstalling(false)
    }
  }

  if (!trimmed || !check) return null

  if (check.satisfied) {
    return (
      <p className={styles.ok}>
        {check.runtime
          ? t('{{label}} {{version}} found — this server can be launched.', {
              label: check.runtime.label,
              version: check.runtime.version,
            })
          : t('This command is not one of the runtimes this app manages; nothing to check.')}
      </p>
    )
  }

  return (
    <div className={styles.missing} role="status">
      <p className={styles.head}>
        <b>{t('{{label}} is not installed on this machine.', { label: missing?.label })}</b>{' '}
        {t('The run launches this server with')} <code>{check.command.split(/\s+/)[0]}</code>
        {t(', so it would fail to start.')}
      </p>
      {plan?.command ? (
        <>
          <p className={styles.hint}>{t('This runs, exactly as written:')}</p>
          <pre className={styles.command}>{plan.command}</pre>
          <div className={styles.actions}>
            <button className={styles.install} disabled={installing} onClick={() => void install()}>
              {installing ? (
                <Spinner label={t('Installing {{label}}', { label: missing?.label })} />
              ) : (
                t('Install {{label}}', { label: missing?.label })
              )}
            </button>
            <button className={styles.ghost} disabled={installing} onClick={() => void refresh(true)}>
              {t('Re-check')}
            </button>
            <a className={styles.docs} href={missing?.docsUrl} target="_blank" rel="noreferrer">
              {t('Instructions')}
            </a>
          </div>
        </>
      ) : (
        <p className={styles.hint}>
          {plan?.reason ?? t('Install it on this machine and press Re-check.')}{' '}
          <a className={styles.docs} href={missing?.docsUrl} target="_blank" rel="noreferrer">
            {t('Official instructions')}
          </a>
          <button className={styles.ghost} disabled={installing} onClick={() => void refresh(true)}>
            {t('Re-check')}
          </button>
        </p>
      )}
      {output && <pre className={styles.output}>{output}</pre>}
      {error && <p className={styles.err}>{error}</p>}
    </div>
  )
}
