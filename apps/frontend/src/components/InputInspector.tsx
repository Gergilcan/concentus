import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { clockTime } from '../utils/format.ts'
import type {
  Credential,
  InputNodeData,
  MailDeviceCode,
  MailOAuthDefaults,
  MailStatus,
  PublishApprovalView,
} from '../api/types.ts'
import { api, publicChatUrl, publicRunUrl, webhookUrl } from '../api/client.ts'
import { usePermissions } from '../state/permissions.tsx'
import { useFlowStore } from '../state/store.ts'
import { aboveCeiling } from '../utils/permissionCeiling.ts'
import { CronBuilder } from './CronBuilder.tsx'
import { CheckboxField, Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import { errMessage } from '../utils/errMessage.ts'
import styles from './panels.module.scss'

interface Props {
  data: InputNodeData
  set: (patch: Record<string, unknown>) => void
}

/**
 * Where the common providers put their proof, and where they show the secret.
 *
 * Presets, not code paths: the backend verifies HMAC-or-static-token whatever the parameter is
 * called, so choosing a provider only fills the parameter name in. It is derived from that name
 * rather than stored on the node — the wire sees nothing but `authParam`, and a stored "provider"
 * could quietly disagree with it after someone edits the parameter by hand.
 */
const WEBHOOK_PROVIDERS = [
  {
    id: 'github',
    label: 'GitHub',
    authParam: 'X-Hub-Signature-256',
    hint: 'Repository → Settings → Webhooks → Add webhook. The "Secret" field there is what you paste below; GitHub signs every delivery with it.',
  },
  {
    id: 'gitlab',
    label: 'GitLab',
    authParam: 'X-Gitlab-Token',
    hint: 'Project → Settings → Webhooks. The "Secret token" field there is sent back verbatim on every delivery.',
  },
  {
    id: 'linear',
    label: 'Linear',
    authParam: 'Linear-Signature',
    hint: "Settings → API → Webhooks → New webhook. Linear shows a signing secret on the webhook's page once it is created.",
  },
] as const

type WebhookProvider = (typeof WEBHOOK_PROVIDERS)[number]['id'] | 'custom'

function providerFor(authParam: string | undefined): WebhookProvider {
  const name = (authParam ?? '').trim().toLowerCase()
  return WEBHOOK_PROVIDERS.find((p) => p.authParam.toLowerCase() === name)?.id ?? 'custom'
}

/**
 * A fresh endpoint token. `randomUUID` needs a secure context, which localhost is; the fallback
 * covers a WebView that lacks it, with the same 122 bits from the same generator.
 */
function newToken(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

export function InputInspector({ data, set }: Props) {
  const { t } = useTranslation()
  const flowId = useFlowStore((s) => s.flowId)
  const [copied, setCopied] = useState(false)
  // "Custom" chosen while the parameter still spells a preset: remembered against that value, so
  // the select does not snap back to the preset until the parameter is actually changed.
  const [customFor, setCustomFor] = useState<string | null>(null)
  const provider: WebhookProvider =
    customFor !== null && customFor === data.authParam ? 'custom' : providerFor(data.authParam)
  const providerHint = WEBHOOK_PROVIDERS.find((p) => p.id === provider)?.hint

  // Publishing. The token is minted here, in the browser: the backend only ever compares it, so
  // there is no round trip to make and nothing to leak in transit before the flow is saved.
  const [copiedToken, setCopiedToken] = useState(false)
  const runUrl = flowId ? publicRunUrl(flowId) : null
  const chatUrl = flowId && data.publishToken ? publicChatUrl(flowId, data.publishToken) : null
  const curl = runUrl
    ? `curl -X POST "${runUrl}" -H "Authorization: Bearer ${data.publishToken ?? ''}" -H "Content-Type: application/json" -d '{"input":"Hello"}'`
    : null
  const togglePublish = (on: boolean) => {
    // Turning it back on keeps the token a client may already hold; Regenerate is the way to
    // revoke, and it says so.
    if (on) set({ published: true, publishToken: data.publishToken || newToken() })
    else set({ published: false })
  }
  const copyToken = async () => {
    if (!data.publishToken) return
    try {
      await navigator.clipboard.writeText(data.publishToken)
      setCopiedToken(true)
      setTimeout(() => setCopiedToken(false), 1500)
    } catch {
      /* clipboard blocked — the field is selectable anyway */
    }
  }
  const [credentials, setCredentials] = useState<Credential[]>([])

  // The organization's permission ceiling, asked for only when this node still carries the
  // legacy mode it would be measured against — most nodes carry none, and a request per
  // inspector open for a hint that never shows would be waste.
  const [ceiling, setCeiling] = useState('')
  useEffect(() => {
    if (!data.permissionMode) return
    void api
      .getOrgPolicy()
      .then((v) => setCeiling(v.enforced ? (v.policy.maxPermissionMode ?? '') : ''))
      .catch(() => setCeiling(''))
  }, [data.permissionMode])

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
  const hookUrl = flowId ? webhookUrl(flowId) : null

  const copy = async () => {
    if (!hookUrl) return
    try {
      await navigator.clipboard.writeText(hookUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard blocked — the field is selectable anyway */
    }
  }

  return (
    <>
      <SelectField label={t('Execution type')} value={data.mode} onChange={(v) => set({ mode: v })}>
        <option value="manual">{t('Manual — you send the first message')}</option>
        <option value="prompt">{t('Prompt — auto-start with a fixed prompt')}</option>
        <option value="cron">{t('Automatic — run on a cron schedule')}</option>
        <option value="webhook">{t('Webhook — start on an external event')}</option>
        <option value="mail">{t('Mail — start when a matching email arrives (IMAP)')}</option>
        <option value="watch">{t('Folder watch — start when files appear or change in a folder')}</option>
        <option value="subflow">{t('Another flow — this flow runs when another one calls it')}</option>
      </SelectField>

      {data.mode !== 'manual' && (
        <TextArea
          label={
            data.mode === 'webhook'
              ? t('Instruction (prepended to the event)')
              : data.mode === 'watch'
                ? t('Instruction (prepended to the list of changed files)')
                : t('Execution prompt')
          }
          rows={4}
          placeholder={
            data.mode === 'webhook'
              ? t('A Linear issue/comment event arrived. Triage it and take the right action.')
              : data.mode === 'watch'
                ? t('New PDFs arrived. Extract each invoice and record it in Holded.')
                : t('Build the login page: backend endpoint + React form, wired to the DB.')
          }
          value={data.prompt}
          onChange={(v) => set({ prompt: v })}
        />
      )}

      {/* Permissions used to live here. They moved to the coordinator agent, which is the node
          that actually corresponds to the process the setting configures. Flows saved with a value
          here still honour it — see TriggerSpec — so nothing silently changed under anyone; it is
          simply no longer editable in the place that suggested it was a property of the trigger. */}
      {(data.permissionMode ?? '') !== '' && (
        <p className={styles.hint}>
          {t('This flow sets permissions')} (<code>{data.permissionMode}</code>){' '}
          {t('on its trigger, which is where they used to live. They still apply. To change them, open the')}{' '}
          <b>{t('coordinator')}</b> {t('agent — setting them there replaces this.')}
          {aboveCeiling(data.permissionMode, ceiling) && (
            <>
              {' '}
              {t('It is above the organization’s ceiling')} (<code>{ceiling}</code>){' '}
              {t('— runs get the ceiling instead.')}
            </>
          )}
        </p>
      )}
      {data.mode !== 'manual' && data.mode !== 'prompt' && (
        <label
          className={styles.checkField}
          title={t('While on, runs started by this trigger PLAN and stop — nothing is executed, nothing changes. The run log shows what each event would have done. Watch it for a few days, then untick to go live. Manual runs are unaffected.')}
        >
          <input
            type="checkbox"
            checked={!!data.shadow}
            onChange={(e) => set({ shadow: e.target.checked })}
          />
          {t('Shadow mode — plan only, act never ⓘ')}
        </label>
      )}


      {data.mode === 'cron' && (
        <CronBuilder value={data.cron ?? ''} onChange={(v) => set({ cron: v })} />
      )}

      {data.mode === 'watch' && (
        <>
          <Field
            label={t('Folder to watch')}
            value={data.watchPath ?? ''}
            placeholder="C:\drop\incoming · /srv/drop/incoming"
            onChange={(v) => set({ watchPath: v })}
          />
          <p className={styles.hint}>
            {t('A folder on the machine running Concentus. It must sit under one of the context roots')}{' '}
            (<code>LOCAL_CONTEXT_ROOTS</code>){' '}
            {t('— the same allowlist that decides what agents may read. The doctor says so when it does not.')}
          </p>
          <Field
            label={t('Files that count')}
            value={data.watchGlob ?? ''}
            placeholder="*.pdf"
            onChange={(v) => set({ watchGlob: v })}
          />
          <p className={styles.hint}>
            {t('A pattern such as')} <code>*.pdf</code> {t('or')} <code>invoices/*.csv</code>.{' '}
            {t('Leave blank for every file.')}
          </p>
          <Field
            label={t('Quiet time before a run (seconds)')}
            type="number"
            value={data.watchDebounceSeconds ?? 5}
            onChange={(v) => set({ watchDebounceSeconds: Number(v) || 5 })}
          />
          <p className={styles.hint}>
            {t('Changes are held until the folder has been quiet this long, so a batch of files dropped together becomes one run with the whole list — not one run per file, and not a run on a half-copied file.')}
          </p>
          <p className={styles.hint}>
            {t('The agent receives the folder and the time as')} <i>{t('verified')}</i>{' '}
            {t('metadata, and the changed paths fenced as untrusted — a file name is text whoever wrote the file chose.')}
          </p>
          {!flowId && (
            <p className={styles.hint}>
              <b>{t('Save the flow')}</b> {t('to start watching — a trigger only runs for a saved flow.')}
            </p>
          )}
        </>
      )}

      {data.mode === 'webhook' && (
        <>
          <SelectField
            label={t('Provider')}
            value={provider}
            onChange={(v) => {
              const preset = WEBHOOK_PROVIDERS.find((p) => p.id === v)
              if (preset) {
                setCustomFor(null)
                set({ authParam: preset.authParam })
              } else {
                setCustomFor(data.authParam)
              }
            }}
          >
            {WEBHOOK_PROVIDERS.map((p) => (
              <option key={p.id} value={p.id}>
                {p.label}
              </option>
            ))}
            <option value="custom">{t('Custom')}</option>
          </SelectField>
          {providerHint && <p className={styles.hint}>{t(providerHint)}</p>}
          <Field
            label={t('Validation parameter')}
            value={data.authParam}
            placeholder={t('Linear-Signature')}
            onChange={(v) => set({ authParam: v })}
          />
          <p className={styles.hint}>
            {t('Header (or query parameter) the provider sends the proof in. E.g.')}{' '}
            <code>Linear-Signature</code>, <code>X-Hub-Signature-256</code> {t('for GitHub, or')}{' '}
            <code>token</code> {t('for a plain shared token.')}
          </p>

          <Field
            label={t('Secret')}
            value={data.secret}
            placeholder={t("Copy from the provider's webhook page")}
            onChange={(v) => set({ secret: v })}
          />
          {!data.secret && (
            <p className={styles.hint}>
              {t('Required — without it every delivery is rejected with')} <b>401</b>.
            </p>
          )}

          <Field
            label={t('Webhook URL')}
            value={hookUrl ?? t('Save the flow first to generate the URL.')}
            readOnly
            onFocus={hookUrl ? (e) => e.currentTarget.select() : undefined}
          />
          {hookUrl && (
            <div className={styles.mcpBtns}>
              <button className={styles.previewBtn} onClick={() => void copy()}>
                {copied ? t('Copied ✓') : t('Copy URL')}
              </button>
            </div>
          )}

          <p className={styles.hint}>
            {t("The value is accepted if it's an HMAC-SHA256 of the request body signed with the secret, or the secret itself — so signed and plain-token providers both work with no extra setup.")}
          </p>
          <p className={styles.hint}>
            <b>Linear:</b> {t('Settings → API → Webhooks → New webhook. Paste this URL and enable the events you want (e.g.')}{' '}
            <b>{t('Issues')}</b>, <b>{t('Comments')}</b>). {t('Linear then shows a')} <b>{t('signing secret')}</b>{' '}
            {t("on the webhook's page — copy it into the Secret field. The URL must be reachable from the internet (deploy it, or tunnel with ngrok for local testing).")}
          </p>
        </>
      )}

      {data.mode === 'mail' && (
        <>
          <p className={styles.hint}>
            {t('Polls an')} <b>IMAP</b>{' '}
            {t("folder and starts a run for each new message that matches. IMAP, not SMTP: folders, flags and read state live in the mail store, so “flagged, in Presupuestos” is only expressible here — and it's also what lets the message be moved once it's handled.")}
          </p>

          <Field
            label={t('IMAP host')}
            value={data.mailHost ?? ''}
            placeholder={t('outlook.office365.com')}
            onChange={(v) => set({ mailHost: v })}
          />
          <Field
            label={t('Port')}
            type="number"
            value={data.mailPort ?? 993}
            onChange={(v) => set({ mailPort: Number(v) || 993 })}
          />
          <CheckboxField
            label={t('Use TLS (IMAPS)')}
            checked={data.mailSsl ?? true}
            onChange={(v) => set({ mailSsl: v, mailPort: v ? 993 : 143 })}
          />
          <Field
            label={t('Username')}
            value={data.mailUsername ?? ''}
            placeholder={t('presupuestos@empresa.com')}
            onChange={(v) => set({ mailUsername: v })}
          />
          <SelectField
            label={t('Authentication')}
            value={data.mailAuthMode ?? 'password'}
            onChange={(v) => set({ mailAuthMode: v })}
          >
            <option value="password">{t('Password / app password')}</option>
            <option value="microsoft-oauth">{t('Microsoft 365 sign-in (OAuth2)')}</option>
          </SelectField>

          {data.mailAuthMode === 'microsoft-oauth' ? (
            <MicrosoftSignIn data={data} set={set} onSignedIn={reloadCredentials} />
          ) : (
            <>
              <SelectField
                label={t('Password')}
                value={data.mailCredentialId ?? ''}
                onChange={(v) => set({ mailCredentialId: v })}
              >
                <option value="">{t('— select a stored credential —')}</option>
                {credentials.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label} ({c.hint ?? '••••'})
                  </option>
                ))}
              </SelectField>
              <p className={styles.hint}>
                {t('Add one under')} <b>{t('Resources → Credentials')}</b>.{' '}
                {t('It is encrypted before storage and never shown again — this node holds only its id, so the flow can be exported, duplicated or rolled back to an earlier version without carrying a secret.')}
              </p>
              <p className={styles.hint}>
                {t('Microsoft 365 will reject a password here: Basic authentication for IMAP is retired, so even a correct one comes back as')}{' '}
                <code>AUTHENTICATE failed</code>. {t('Switch to the sign-in above for an')}{' '}
                <code>@outlook.com</code> {t('or Microsoft 365 mailbox.')}
              </p>
            </>
          )}
          {data.mailCredentialId && !credentials.some((c) => c.id === data.mailCredentialId) && (
            <p className={styles.hint}>
              <b>{t('This credential no longer exists.')}</b> {t('Select another, or the flow will not poll.')}
            </p>
          )}

          <Field
            label={t('Folder to watch')}
            value={data.mailFolder ?? 'INBOX'}
            placeholder={t('Presupuestos')}
            onChange={(v) => set({ mailFolder: v })}
          />

          <FineTuning>
          <p className={styles.hint}>
            <b>{t('Conditions')}</b> {t('— leave blank to match everything in the folder.')}
          </p>
          <Field
            label={t('From contains')}
            value={data.mailFrom ?? ''}
            placeholder={t('@cliente.com')}
            onChange={(v) => set({ mailFrom: v })}
          />
          <Field
            label={t('Subject contains')}
            value={data.mailSubjectContains ?? ''}
            placeholder={t('presupuesto')}
            onChange={(v) => set({ mailSubjectContains: v })}
          />
          <Field
            label={t('Body contains')}
            value={data.mailBodyContains ?? ''}
            onChange={(v) => set({ mailBodyContains: v })}
          />
          <CheckboxField
            label={t('Unread only')}
            checked={data.mailUnseenOnly ?? true}
            onChange={(v) => set({ mailUnseenOnly: v })}
          />
          <CheckboxField
            label={t('Flagged only')}
            checked={data.mailFlaggedOnly ?? false}
            onChange={(v) => set({ mailFlaggedOnly: v })}
          />
          <CheckboxField
            label={t('With attachments only')}
            checked={data.mailWithAttachmentsOnly ?? false}
            onChange={(v) => set({ mailWithAttachmentsOnly: v })}
          />

          <Field
            label={t('Poll every (seconds)')}
            type="number"
            value={data.mailPollSeconds ?? 60}
            onChange={(v) => set({ mailPollSeconds: Number(v) || 60 })}
          />
          <Field
            label={t('Max runs per poll')}
            type="number"
            value={data.mailMaxPerPoll ?? 5}
            onChange={(v) => set({ mailMaxPerPoll: Number(v) || 5 })}
          />
          <p className={styles.hint}>
            {t("A cap, so a folder with a thousand unread messages doesn't launch a thousand agent runs on the first tick. The rest are picked up on later polls.")}
          </p>

          <p className={styles.hint}>
            <b>{t('After the run starts')}</b>
          </p>
          <Field
            label={t('Move to folder')}
            value={data.mailMoveToFolder ?? ''}
            placeholder={t('Presupuestos/Procesados')}
            onChange={(v) => set({ mailMoveToFolder: v })}
          />
          <CheckboxField
            label={t('Mark as read')}
            checked={data.mailMarkSeen ?? true}
            onChange={(v) => set({ mailMarkSeen: v })}
          />
          <CheckboxField
            label={t('Flag it')}
            checked={data.mailFlagAfter ?? false}
            onChange={(v) => set({ mailFlagAfter: v })}
          />
          <p className={styles.hint}>
            {t('A message is never processed twice even if none of these are set: each one is recorded by its')}{' '}
            <code>Message-ID</code> {t('before its run starts.')}
          </p>
          </FineTuning>
          <p className={styles.hint}>
            {t('The agent receives the sender, subject and date as')} <i>{t('verified')}</i>{' '}
            {t("metadata, plus the body and attachment text fenced as untrusted — so text in the email can't impersonate the system.")}
          </p>

          <MailTriggerStatus flowId={flowId} />
        </>
      )}

      {data.mode !== 'webhook' && data.mode !== 'mail' && data.mode !== 'watch' && (
        <p className={styles.hint}>
          {data.mode === 'manual' && t('The run starts idle — type the first instruction in the console.')}
          {data.mode === 'prompt' && t('Pressing Run auto-sends this prompt as the first turn.')}
          {data.mode === 'cron' && (
            <>{t('Runs automatically on this schedule with the prompt above (saved flows only).')}</>
          )}
          {data.mode === 'subflow' && (
            <>
              {t('Started by another flow — through a Run-another-flow node there, which hands over the text this run begins with. Nothing schedules it, and pressing Run still works for testing it by hand.')}
            </>
          )}
        </p>
      )}
      {/* Any mode: publishing adds a door, it does not move the existing one. */}
      <label
        className={styles.checkField}
        title={t('While on, a POST with this token starts a run of this flow and answers with its final output. The flow otherwise starts exactly as before.')}
      >
        <input
          type="checkbox"
          checked={!!data.published}
          onChange={(e) => togglePublish(e.target.checked)}
        />
        {t('Publish as an endpoint ⓘ')}
      </label>
      {data.published && (
        <>
          <Field
            label={t('Endpoint token')}
            value={data.publishToken ?? ''}
            readOnly
            onFocus={(e) => e.currentTarget.select()}
          />
          <div className={styles.mcpBtns}>
            <button className={styles.previewBtn} onClick={() => void copyToken()}>
              {copiedToken ? t('Copied ✓') : t('Copy token')}
            </button>
            <button className={styles.previewBtn} onClick={() => set({ publishToken: newToken() })}>
              {t('Regenerate')}
            </button>
          </div>
          <p className={styles.hint}>
            {t('Anyone holding this token can start runs of this flow. Regenerating it revokes the old one as soon as the flow is saved.')}
          </p>
          <PublishApprovalStatus flowId={flowId} token={data.publishToken ?? ''} />
          <Field
            label={t('Endpoint URL')}
            value={runUrl ?? t('Save the flow first to generate the URL.')}
            readOnly
            onFocus={runUrl ? (e) => e.currentTarget.select() : undefined}
          />
          {curl && (
            <Field
              label={t('curl example')}
              value={curl}
              readOnly
              onFocus={(e) => e.currentTarget.select()}
            />
          )}
          <p className={styles.hint}>
            {t('The input becomes the first message; the call waits for the final output and answers')}{' '}
            <code>{'{ runId, status, output }'}</code>. {t('Runs started this way show the trigger')} <code>api</code>.
          </p>
          {chatUrl && (
            <p className={styles.hint}>
              {t('A minimal chat page for trying it:')}{' '}
              <a href={chatUrl} target="_blank" rel="noreferrer">
                {t('open')}
              </a>
              . {t('A demo surface, not a product — the token travels in its address, so do not share the link.')}
            </p>
          )}
        </>
      )}
      <p className={styles.hint}>{t("Connect this node's output to your coordinator agent.")}</p>
    </>
  )
}

/**
 * Whether the organization has let this endpoint open (organization policy: published endpoints
 * need an administrator's approval).
 *
 * <p>Nothing at all where the rule is off — most deployments — so the publish block reads as it
 * always did. Where it is on, the approval is of a token: a member sees whether the token in
 * this node is the approved one, an admin gets the button. An approval is always of the SAVED
 * token, so a regenerated-but-unsaved token is told to save first rather than offered a button
 * that would approve the wrong thing.
 */
function PublishApprovalStatus({ flowId, token }: { flowId: string | null; token: string }) {
  const { t } = useTranslation()
  const { canAdminister } = usePermissions()
  const [view, setView] = useState<PublishApprovalView | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!flowId) return
    void api
      .publishApproval(flowId)
      .then(setView)
      .catch(() => setView(null))
  }, [flowId])

  useEffect(load, [load])

  if (!flowId || !view || !view.required) return null

  const approved = !!view.approvedToken && view.approvedToken === token
  const unsaved = view.savedToken !== token

  const decide = async (approve: boolean) => {
    setBusy(true)
    setError(null)
    try {
      setView(approve ? await api.approvePublish(flowId) : await api.revokePublish(flowId))
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <p className={styles.hint}>
        {approved ? (
          <>
            <b>{t('Approved')}</b>
            {view.approvedBy ? ` ${t('by')} ${view.approvedBy}` : ''}
            {view.approvedAt ? ` (${clockTime(view.approvedAt)})` : ''}.{' '}
            {t('Regenerating the token asks for a new approval.')}
          </>
        ) : unsaved ? (
          <>
            <b>{t('Save the flow')}</b>{' '}
            {t("— an administrator approves the saved token, and this one is not saved yet. Until then the endpoint answers 404.")}
          </>
        ) : (
          <>
            <b>{t("Waiting for an administrator's approval")}</b>{' '}
            {t('— the organization requires one before a published endpoint answers. Until then it answers 404, exactly as if the flow were not published.')}
          </>
        )}
      </p>
      {canAdminister && !unsaved && (
        <div className={styles.mcpBtns}>
          <button className={styles.previewBtn} disabled={busy} onClick={() => void decide(!approved)}>
            {approved ? t('Revoke approval') : t('Approve this endpoint')}
          </button>
        </div>
      )}
      {error && <p className={styles.hint}>{error}</p>}
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
  const { t } = useTranslation()
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
        <b>{t('Save the flow')}</b> {t('to start polling — a trigger only runs for a saved flow.')}
      </p>
    )
  }

  const stateLabel = STATE_LABEL[status?.state ?? 'waiting']

  return (
    <>
      <p className={styles.hint}>
        <b>{stateLabel ? t(stateLabel) : status?.state}</b>
        {status?.detail ? ` — ${status.detail}` : ''}
        {status?.at ? ` (${clockTime(status.at)})` : ''}
      </p>
      {status?.runsStarted !== undefined && status.runsStarted > 0 && (
        <p className={styles.hint}>
          {t('{{n}} run(s) started from this mailbox since the backend last restarted.', { n: status.runsStarted })}
        </p>
      )}
      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => void checkNow()} disabled={busy}>
          {busy ? t('Checking…') : t('Check now')}
        </button>
      </div>
      <p className={styles.hint}>
        {t('Checks immediately instead of waiting for the next poll — a saved change takes effect on the next one either way.')}
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
  const { t } = useTranslation()
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
      setStatus(t('Waiting for you to enter the code…'))
      poll(started, Date.now() + started.expiresIn * 1000)
    } catch (e) {
      setError(errMessage(e))
      setBusy(false)
    }
  }

  const poll = (started: MailDeviceCode, deadline: number) => {
    polling.current = window.setTimeout(() => {
      void (async () => {
        if (Date.now() > deadline) {
          setError(t('The code expired before it was entered. Start again.'))
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
            setStatus(t('Signed in. Stored as “{{label}}”.', { label: result.label }))
          } else {
            setError(result.error ?? t('Sign-in did not complete.'))
          }
        } catch (e) {
          setError(errMessage(e))
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
          {t("Using this deployment's app registration")} (<code>…{defaults.clientId.slice(-6)}</code>).{' '}
          <button className={styles.linkBtn} onClick={() => setShowAdvanced(true)}>
            {t('use a different one')}
          </button>
        </p>
      ) : (
        <>
          {!defaults?.configured && (
            <p className={styles.hint}>
              {t('No app registration is configured for this deployment. Set')}{' '}
              <code>MAIL_MICROSOFT_TENANT_ID</code> {t('and')} <code>MAIL_MICROSOFT_CLIENT_ID</code>{' '}
              {t('once — one registration serves every mailbox — or fill both in here for this node only.')}
            </p>
          )}
          <Field
            label={t('Directory (tenant) ID')}
            value={tenantId}
            placeholder={defaults?.tenantId || '00000000-0000-0000-0000-000000000000'}
            onChange={(v) => set({ mailTenantId: v })}
          />
          <Field
            label={t('Application (client) ID')}
            value={clientId}
            placeholder={defaults?.clientId || '00000000-0000-0000-0000-000000000000'}
            onChange={(v) => set({ mailClientId: v })}
          />
          <p className={styles.hint}>
            {t('From the app registration in')} <b>{t('Entra ID → App registrations')}</b>.{' '}
            {t('It needs the delegated permissions')} <code>IMAP.AccessAsUser.All</code> {t('and')}{' '}
            <code>offline_access</code>, {t('and')}{' '}
            <b>{t('Allow public client flows')}</b>{' '}
            {t('set to Yes. No redirect URI and no client secret — that is what lets this work from a container.')}
          </p>
        </>
      )}

      <button
        type="button"
        className={styles.previewBtn}
        disabled={!ready || busy}
        onClick={() => void start()}
      >
        {busy ? t('Waiting for sign-in…') : t('Connect Microsoft account')}
      </button>

      {code && (
        <p className={styles.hint}>
          {t('Open')}{' '}
          <a href={code.verificationUri} target="_blank" rel="noreferrer">
            {code.verificationUri}
          </a>{' '}
          {t('and enter')} <b>{code.userCode}</b>. {t('Sign in as')}{' '}
          <b>{data.mailUsername || t('the mailbox')}</b>{' '}
          {t('— the mailbox being polled, not your own account.')}
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
          {t('A sign-in is stored for this node. It renews itself; re-connect only if polling starts reporting that it is no longer valid.')}
        </p>
      )}
    </>
  )
}
