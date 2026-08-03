import { useCallback, useEffect, useRef, useState } from 'react'
import type { Credential, InputNodeData, MailDeviceCode, MailOAuthDefaults, MailStatus } from '../api/types.ts'
import { api } from '../api/client.ts'
import { useFlowStore } from '../state/store.ts'
import { CheckboxField, Field, SelectField, TextArea } from './fields.tsx'
import { errMessage } from '../utils/errMessage.ts'
import styles from './panels.module.scss'

interface Props {
  data: InputNodeData
  set: (patch: Record<string, unknown>) => void
}

export function InputInspector({ data, set }: Props) {
  const flowId = useFlowStore((s) => s.flowId)
  const [copied, setCopied] = useState(false)
  const [credentials, setCredentials] = useState<Credential[]>([])

  // Loaded regardless of mode: the hook must not be conditional, and the list is small.
  // A failure leaves the picker empty rather than breaking the inspector.
  const reloadCredentials = useCallback(() => {
    void api
      .listCredentials()
      .then(setCredentials)
      .catch(() => setCredentials([]))
  }, [])

  useEffect(reloadCredentials, [reloadCredentials])

  // No token in the URL: Linear authenticates by signing the body, not by echoing a secret back.
  const webhookUrl = flowId ? `${location.origin}/api/webhooks/${flowId}` : null

  const copy = async () => {
    if (!webhookUrl) return
    try {
      await navigator.clipboard.writeText(webhookUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard blocked — the field is selectable anyway */
    }
  }

  return (
    <>
      <SelectField label="Execution type" value={data.mode} onChange={(v) => set({ mode: v })}>
        <option value="manual">Manual — you send the first message</option>
        <option value="prompt">Prompt — auto-start with a fixed prompt</option>
        <option value="cron">Automatic — run on a cron schedule</option>
        <option value="webhook">Webhook — start on an external event</option>
        <option value="mail">Mail — start when a matching email arrives (IMAP)</option>
      </SelectField>

      {data.mode !== 'manual' && (
        <TextArea
          label={data.mode === 'webhook' ? 'Instruction (prepended to the event)' : 'Execution prompt'}
          rows={4}
          placeholder={
            data.mode === 'webhook'
              ? 'A Linear issue/comment event arrived. Triage it and take the right action.'
              : 'Build the login page: backend endpoint + React form, wired to the DB.'
          }
          value={data.prompt}
          onChange={(v) => set({ prompt: v })}
        />
      )}

      {data.mode === 'cron' && (
        <Field label="Cron expression" value={data.cron} placeholder="0 9 * * *" onChange={(v) => set({ cron: v })} />
      )}

      {data.mode === 'webhook' && (
        <>
          <Field
            label="Validation parameter"
            value={data.authParam}
            placeholder="Linear-Signature"
            onChange={(v) => set({ authParam: v })}
          />
          <p className={styles.hint}>
            Header (or query parameter) the provider sends the proof in. E.g.{' '}
            <code>Linear-Signature</code>, <code>X-Hub-Signature-256</code> for GitHub, or{' '}
            <code>token</code> for a plain shared token.
          </p>

          <Field
            label="Secret"
            value={data.secret}
            placeholder="Copy from the provider's webhook page"
            onChange={(v) => set({ secret: v })}
          />
          {!data.secret && (
            <p className={styles.hint}>
              Required — without it every delivery is rejected with <b>401</b>.
            </p>
          )}

          <Field
            label="Webhook URL"
            value={webhookUrl ?? 'Save the flow first to generate the URL.'}
            readOnly
            onFocus={webhookUrl ? (e) => e.currentTarget.select() : undefined}
          />
          {webhookUrl && (
            <div className={styles.mcpBtns}>
              <button className={styles.previewBtn} onClick={() => void copy()}>
                {copied ? 'Copied ✓' : 'Copy URL'}
              </button>
            </div>
          )}

          <p className={styles.hint}>
            The value is accepted if it's an HMAC-SHA256 of the request body signed with the secret, or
            the secret itself — so signed and plain-token providers both work with no extra setup.
          </p>
          <p className={styles.hint}>
            <b>Linear:</b> Settings → API → Webhooks → New webhook. Paste this URL and enable the events
            you want (e.g. <b>Issues</b>, <b>Comments</b>). Linear then shows a <b>signing secret</b> on
            the webhook's page — copy it into the Secret field. The URL must be reachable from the
            internet (deploy it, or tunnel with ngrok for local testing).
          </p>
        </>
      )}

      {data.mode === 'mail' && (
        <>
          <p className={styles.hint}>
            Polls an <b>IMAP</b> folder and starts a run for each new message that matches. IMAP,
            not SMTP: folders, flags and read state live in the mail store, so “flagged, in
            Presupuestos” is only expressible here — and it's also what lets the message be moved
            once it's handled.
          </p>

          <Field
            label="IMAP host"
            value={data.mailHost ?? ''}
            placeholder="outlook.office365.com"
            onChange={(v) => set({ mailHost: v })}
          />
          <Field
            label="Port"
            type="number"
            value={data.mailPort ?? 993}
            onChange={(v) => set({ mailPort: Number(v) || 993 })}
          />
          <CheckboxField
            label="Use TLS (IMAPS)"
            checked={data.mailSsl ?? true}
            onChange={(v) => set({ mailSsl: v, mailPort: v ? 993 : 143 })}
          />
          <Field
            label="Username"
            value={data.mailUsername ?? ''}
            placeholder="presupuestos@empresa.com"
            onChange={(v) => set({ mailUsername: v })}
          />
          <SelectField
            label="Authentication"
            value={data.mailAuthMode ?? 'password'}
            onChange={(v) => set({ mailAuthMode: v })}
          >
            <option value="password">Password / app password</option>
            <option value="microsoft-oauth">Microsoft 365 sign-in (OAuth2)</option>
          </SelectField>

          {data.mailAuthMode === 'microsoft-oauth' ? (
            <MicrosoftSignIn data={data} set={set} onSignedIn={reloadCredentials} />
          ) : (
            <>
              <SelectField
                label="Password"
                value={data.mailCredentialId ?? ''}
                onChange={(v) => set({ mailCredentialId: v })}
              >
                <option value="">— select a stored credential —</option>
                {credentials.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label} ({c.hint ?? '••••'})
                  </option>
                ))}
              </SelectField>
              <p className={styles.hint}>
                Add one under <b>Resources → Credentials</b>. It is encrypted before storage and
                never shown again — this node holds only its id, so the flow can be exported,
                duplicated or rolled back to an earlier version without carrying a secret.
              </p>
              <p className={styles.hint}>
                Microsoft 365 will reject a password here: Basic authentication for IMAP is retired,
                so even a correct one comes back as <code>AUTHENTICATE failed</code>. Switch to the
                sign-in above for an <code>@outlook.com</code> or Microsoft 365 mailbox.
              </p>
            </>
          )}
          {data.mailCredentialId && !credentials.some((c) => c.id === data.mailCredentialId) && (
            <p className={styles.hint}>
              <b>This credential no longer exists.</b> Select another, or the flow will not poll.
            </p>
          )}

          <Field
            label="Folder to watch"
            value={data.mailFolder ?? 'INBOX'}
            placeholder="Presupuestos"
            onChange={(v) => set({ mailFolder: v })}
          />

          <p className={styles.hint}>
            <b>Conditions</b> — leave blank to match everything in the folder.
          </p>
          <Field
            label="From contains"
            value={data.mailFrom ?? ''}
            placeholder="@cliente.com"
            onChange={(v) => set({ mailFrom: v })}
          />
          <Field
            label="Subject contains"
            value={data.mailSubjectContains ?? ''}
            placeholder="presupuesto"
            onChange={(v) => set({ mailSubjectContains: v })}
          />
          <Field
            label="Body contains"
            value={data.mailBodyContains ?? ''}
            onChange={(v) => set({ mailBodyContains: v })}
          />
          <CheckboxField
            label="Unread only"
            checked={data.mailUnseenOnly ?? true}
            onChange={(v) => set({ mailUnseenOnly: v })}
          />
          <CheckboxField
            label="Flagged only"
            checked={data.mailFlaggedOnly ?? false}
            onChange={(v) => set({ mailFlaggedOnly: v })}
          />
          <CheckboxField
            label="With attachments only"
            checked={data.mailWithAttachmentsOnly ?? false}
            onChange={(v) => set({ mailWithAttachmentsOnly: v })}
          />

          <Field
            label="Poll every (seconds)"
            type="number"
            value={data.mailPollSeconds ?? 60}
            onChange={(v) => set({ mailPollSeconds: Number(v) || 60 })}
          />
          <Field
            label="Max runs per poll"
            type="number"
            value={data.mailMaxPerPoll ?? 5}
            onChange={(v) => set({ mailMaxPerPoll: Number(v) || 5 })}
          />
          <p className={styles.hint}>
            A cap, so a folder with a thousand unread messages doesn't launch a thousand agent runs
            on the first tick. The rest are picked up on later polls.
          </p>

          <p className={styles.hint}>
            <b>After the run starts</b>
          </p>
          <Field
            label="Move to folder"
            value={data.mailMoveToFolder ?? ''}
            placeholder="Presupuestos/Procesados"
            onChange={(v) => set({ mailMoveToFolder: v })}
          />
          <CheckboxField
            label="Mark as read"
            checked={data.mailMarkSeen ?? true}
            onChange={(v) => set({ mailMarkSeen: v })}
          />
          <CheckboxField
            label="Flag it"
            checked={data.mailFlagAfter ?? false}
            onChange={(v) => set({ mailFlagAfter: v })}
          />
          <p className={styles.hint}>
            A message is never processed twice even if none of these are set: each one is recorded
            by its <code>Message-ID</code> before its run starts.
          </p>
          <p className={styles.hint}>
            The agent receives the sender, subject and date as <i>verified</i> metadata, plus the
            body and attachment text fenced as untrusted — so text in the email can't impersonate
            the system.
          </p>

          <MailTriggerStatus flowId={flowId} />
        </>
      )}

      {data.mode !== 'webhook' && data.mode !== 'mail' && (
        <p className={styles.hint}>
          {data.mode === 'manual' && 'The run starts idle — type the first instruction in the console.'}
          {data.mode === 'prompt' && 'Pressing Run auto-sends this prompt as the first turn.'}
          {data.mode === 'cron' && (
            <>
              Runs automatically on this schedule with the prompt above (saved flows only). 5-field
              (<code>min hour day month weekday</code>) or 6-field cron. E.g. <code>0 9 * * *</code>{' '}
              daily 09:00.
            </>
          )}
        </p>
      )}
      <p className={styles.hint}>Connect this node's output to your coordinator agent.</p>
    </>
  )
}

const STATE_LABEL: Record<string, string> = {
  unknown: '— Not saved',
  off: '— Not a mail trigger',
  incomplete: '⚠ Incomplete',
  paused: '❚❚ Paused',
  waiting: '… Waiting for the first poll',
  ok: '✓ Polling',
  error: '✕ Failing',
}

/**
 * Whether the trigger is actually working.
 *
 * <p>A poller succeeds by doing nothing most of the time, so a correctly configured trigger on a
 * quiet folder and a completely broken one both produce silence. Without something that says which
 * one you have, the only evidence is a log line on a server nobody is tailing.
 */
function MailTriggerStatus({ flowId }: { flowId: string | null }) {
  const [status, setStatus] = useState<MailStatus | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!flowId) return
    let alive = true
    const load = () =>
      api
        .mailStatus(flowId)
        .then((s) => alive && setStatus(s))
        .catch(() => alive && setStatus(null))
    void load()
    // Slower than the poll itself: this is a status line someone glances at, not a live feed.
    const t = setInterval(() => void load(), 10_000)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [flowId])

  const checkNow = async () => {
    if (!flowId) return
    setBusy(true)
    try {
      setStatus(await api.pollMailNow(flowId))
    } catch (e) {
      setStatus({ state: 'error', detail: errMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  if (!flowId) {
    return (
      <p className={styles.hint}>
        <b>Save the flow</b> to start polling — a trigger only runs for a saved flow.
      </p>
    )
  }

  return (
    <>
      <p className={styles.hint}>
        <b>{STATE_LABEL[status?.state ?? 'waiting'] ?? status?.state}</b>
        {status?.detail ? ` — ${status.detail}` : ''}
        {status?.at ? ` (${new Date(status.at).toLocaleTimeString()})` : ''}
      </p>
      {status?.runsStarted !== undefined && status.runsStarted > 0 && (
        <p className={styles.hint}>
          {status.runsStarted} run(s) started from this mailbox since the backend last restarted.
        </p>
      )}
      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => void checkNow()} disabled={busy}>
          {busy ? 'Checking…' : 'Check now'}
        </button>
      </div>
      <p className={styles.hint}>
        Checks immediately instead of waiting for the next poll — a saved change takes effect on the
        next one either way.
      </p>
    </>
  )
}

/**
 * Signs a Microsoft 365 mailbox in without a browser on the server.
 *
 * The device code flow is asynchronous by nature: we ask Entra for a short code, a person types it
 * at microsoft.com/devicelogin on whatever device they like, and we poll until they've finished.
 * That's what makes it work from a container — no redirect URI, no client secret, nothing to open
 * locally. What comes back and gets stored is a refresh token, encrypted like any other credential.
 */
function MicrosoftSignIn({
  data,
  set,
  onSignedIn,
}: Props & { onSignedIn: () => void }) {
  const [code, setCode] = useState<MailDeviceCode | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Held in a ref so unmounting the inspector stops the poll — otherwise switching nodes mid
  // sign-in would leave a timer running against a component that no longer exists.
  const polling = useRef<number | null>(null)

  useEffect(
    () => () => {
      if (polling.current) window.clearTimeout(polling.current)
    },
    [],
  )

  // The deployment's app registration. One registration serves every mailbox, so the normal case
  // is that these fields stay empty and nobody is ever asked for them.
  const [defaults, setDefaults] = useState<MailOAuthDefaults | null>(null)
  const [showAdvanced, setShowAdvanced] = useState(false)

  useEffect(() => {
    let alive = true
    void api
      .mailSignInDefaults()
      .then((d) => alive && setDefaults(d))
      .catch(() => alive && setDefaults(null))
    return () => {
      alive = false
    }
  }, [])

  const tenantId = (data.mailTenantId ?? '').trim()
  const clientId = (data.mailClientId ?? '').trim()
  // Blank fields are fine when the deployment has a registration — the backend fills them in.
  const usingDefaults = tenantId === '' && clientId === ''
  const ready = usingDefaults ? (defaults?.configured ?? false) : tenantId !== '' && clientId !== ''

  const start = async () => {
    setError(null)
    setStatus(null)
    setBusy(true)
    try {
      const started = await api.startMailSignIn(tenantId, clientId)
      setCode(started)
      setStatus('Waiting for you to enter the code…')
      poll(started, Date.now() + started.expiresIn * 1000)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setBusy(false)
    }
  }

  const poll = (started: MailDeviceCode, deadline: number) => {
    polling.current = window.setTimeout(() => {
      void (async () => {
        if (Date.now() > deadline) {
          setError('The code expired before it was entered. Start again.')
          setBusy(false)
          return
        }
        try {
          const result = await api.completeMailSignIn({
            tenantId,
            clientId,
            deviceCode: started.deviceCode,
            label: `Microsoft 365 — ${data.mailUsername || 'mailbox'}`,
            // Reusing the node's existing credential keeps one row per mailbox instead of a new
            // one on every re-sign-in.
            credentialId: data.mailCredentialId,
          })
          if (result.pending) {
            poll(started, deadline)
            return
          }
          setBusy(false)
          setCode(null)
          if (result.ok && result.credentialId) {
            set({ mailCredentialId: result.credentialId })
            onSignedIn()
            setStatus(`Signed in. Stored as “${result.label}”.`)
          } else {
            setError(result.error ?? 'Sign-in did not complete.')
          }
        } catch (e) {
          setError(e instanceof Error ? e.message : String(e))
          setBusy(false)
          setCode(null)
        }
      })()
      // Entra's own interval: polling faster earns a `slow_down` and makes the sign-in take
      // longer, not shorter.
    }, started.interval * 1000)
  }

  return (
    <>
      {defaults?.configured && usingDefaults && !showAdvanced ? (
        <p className={styles.hint}>
          Using this deployment's app registration (<code>…{defaults.clientId.slice(-6)}</code>).{' '}
          <button className={styles.linkBtn} onClick={() => setShowAdvanced(true)}>
            use a different one
          </button>
        </p>
      ) : (
        <>
          {!defaults?.configured && (
            <p className={styles.hint}>
              No app registration is configured for this deployment. Set{' '}
              <code>MAIL_MICROSOFT_TENANT_ID</code> and <code>MAIL_MICROSOFT_CLIENT_ID</code> once —
              one registration serves every mailbox — or fill both in here for this node only.
            </p>
          )}
          <Field
            label="Directory (tenant) ID"
            value={tenantId}
            placeholder={defaults?.tenantId || '00000000-0000-0000-0000-000000000000'}
            onChange={(v) => set({ mailTenantId: v })}
          />
          <Field
            label="Application (client) ID"
            value={clientId}
            placeholder={defaults?.clientId || '00000000-0000-0000-0000-000000000000'}
            onChange={(v) => set({ mailClientId: v })}
          />
          <p className={styles.hint}>
            From the app registration in <b>Entra ID → App registrations</b>. It needs the delegated
            permissions <code>IMAP.AccessAsUser.All</code> and <code>offline_access</code>, and{' '}
            <b>Allow public client flows</b> set to Yes. No redirect URI and no client secret — that
            is what lets this work from a container.
          </p>
        </>
      )}

      <button
        type="button"
        className={styles.previewBtn}
        disabled={!ready || busy}
        onClick={() => void start()}
      >
        {busy ? 'Waiting for sign-in…' : 'Connect Microsoft account'}
      </button>

      {code && (
        <p className={styles.hint}>
          Open{' '}
          <a href={code.verificationUri} target="_blank" rel="noreferrer">
            {code.verificationUri}
          </a>{' '}
          and enter <b>{code.userCode}</b>. Sign in as <b>{data.mailUsername || 'the mailbox'}</b> —
          the mailbox being polled, not your own account.
        </p>
      )}
      {status && <p className={styles.hint}>{status}</p>}
      {error && (
        <p className={styles.hint}>
          <b>{error}</b>
        </p>
      )}
      {data.mailCredentialId && !code && !error && (
        <p className={styles.hint}>
          A sign-in is stored for this node. It renews itself; re-connect only if polling starts
          reporting that it is no longer valid.
        </p>
      )}
    </>
  )
}
