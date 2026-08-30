import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { BackendFlow, FlowMemoryView, Runner, Variable } from '../api/types.ts'
import { CredentialField } from './CredentialField.tsx'
import { FlowVersions } from './FlowVersions.tsx'
import { Modal } from './Modal.tsx'
import { timeAgo } from './flowFormat.ts'
import styles from './flows.module.scss'

/** Flow-level settings: name, tags, schedule pause, where it runs, failure webhook. */
export function SettingsModal({
  flow,
  onClose,
  onSave,
}: {
  flow: BackendFlow
  onClose: () => void
  onSave: (changes: Partial<BackendFlow>) => Promise<void>
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(flow.name)
  const [tags, setTags] = useState((flow.tags ?? []).join(', '))
  const [enabled, setEnabled] = useState(flow.enabled !== false)
  const [webhook, setWebhook] = useState(flow.notifyWebhook ?? '')
  const [budget, setBudget] = useState(flow.budgetUsd != null ? String(flow.budgetUsd) : '')
  const [goldenAutoRun, setGoldenAutoRun] = useState(flow.goldenAutoRun === true)
  const [slackCredential, setSlackCredential] = useState(flow.approvalSlackCredentialId ?? '')
  const [slackChannel, setSlackChannel] = useState(flow.approvalSlackChannel ?? '')
  const [teamsWebhook, setTeamsWebhook] = useState(flow.approvalTeamsWebhook ?? '')
  // Where the flow's Claude CLI runs: '' is this server, 'any' whichever usable runner is least
  // busy, otherwise one runner's id. The list is the caller's usable runners; null until it
  // answers or when it cannot, in which case only the two fixed choices are offered.
  const [runner, setRunner] = useState(flow.runner ?? '')
  const [runners, setRunners] = useState<Runner[] | null>(null)
  const [busy, setBusy] = useState(false)
  // The flow's own {{NAME}} values. Organization variables are shown alongside as the defaults
  // being overridden; they are loaded here because this is the one place overriding happens.
  const [variables, setVariables] = useState<Record<string, string>>(flow.variables ?? {})
  const [orgVariables, setOrgVariables] = useState<Variable[]>([])
  const [newVarName, setNewVarName] = useState('')

  useEffect(() => {
    api.listVariables().then(setOrgVariables).catch(() => setOrgVariables([]))
  }, [])

  // Deferred to a microtask so a backend (or a test's client) without the route rejects
  // instead of throwing during render.
  useEffect(() => {
    Promise.resolve()
      .then(() => api.listUsableRunners())
      .then(setRunners)
      .catch(() => setRunners(null))
  }, [])

  const save = async () => {
    setBusy(true)
    await onSave({
      name: name.trim() || flow.name,
      tags: tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean),
      enabled,
      notifyWebhook: webhook.trim(),
      budgetUsd: budget.trim() === '' ? null : Math.max(0, Number(budget)) || null,
      approvalSlackCredentialId: slackCredential.trim(),
      approvalSlackChannel: slackChannel.trim(),
      approvalTeamsWebhook: teamsWebhook.trim(),
      variables: savedVariables(),
      goldenAutoRun,
      runner: runner === '' ? null : runner,
    })
    setBusy(false)
  }

  // A flow may name a runner the list no longer offers — revoked, deleted, or no longer this
  // caller's to use. Shown as it is rather than dropped, so a Save of an unrelated field does
  // not silently move the flow back to this server.
  const missingRunner = runner !== '' && runner !== 'any' && !(runners ?? []).some((r) => r.id === runner)

  // One row per name the flow could speak about: every organization variable, plus every name
  // this flow defines itself. A blank flow value means "inherit the organization's".
  const variableNames = [
    ...new Set([...orgVariables.map((v) => v.name), ...Object.keys(variables)]),
  ].sort()

  /**
   * Sets a value, and keeps the row.
   *
   * <p>This used to delete the entry when the value went blank, which is what "it will not let me
   * edit them" looked like from outside: clear a flow-only variable to retype it, and the field
   * you were typing in vanished from the screen mid-edit. Blank still means "inherit the
   * organization's value" — that is decided at save, where an empty flow-only entry is dropped,
   * rather than while somebody is still typing.
   */
  const setVariable = (name: string, value: string) => {
    setVariables((prev) => ({ ...prev, [name]: value }))
  }

  /** What actually travels: blank flow values are the absence of an override, not an override. */
  const savedVariables = () =>
    Object.fromEntries(Object.entries(variables).filter(([, value]) => value.trim() !== ''))

  return (
    <Modal title={t('Flow settings')} onClose={onClose}>
      <label className={styles.field}>
        <span>{t('Name')}</span>
        <input value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      {/* No folder field. Filing a flow is done by dragging its card onto a folder on the
          dashboard, where the folders are visible and the result is immediate; typing a path into
          a settings dialog asked somebody to remember the tree and to spell it. The flow keeps
          whichever folder it is in — this screen simply no longer has an opinion about it. */}
      <label className={styles.field}>
        <span>{t('Tags (comma separated)')}</span>
        <input value={tags} onChange={(e) => setTags(e.target.value)} placeholder={t('ops, nightly')} />
      </label>
      <label className={styles.toggleRow}>
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        <span>
          {t('Enabled — when off, scheduled (cron) runs are paused. Manual runs still work.')}
        </span>
      </label>
      <label
        className={styles.field}
        title={t("Sum of the estimated cost of this flow's runs in the current calendar month. Only ever refuses a run that would be billed — one on ANTHROPIC_API_KEY. Runs on your Claude subscription or on a self-hosted model cost the same whether the flow runs once or fifty times, so the ceiling never blocks them; their estimate is still recorded, as equivalent usage rather than a bill. A run already in flight always finishes.")}
      >
        <span>{t('Monthly budget in USD, for API-key runs (blank = no limit) ⓘ')}</span>
        <input
          type="number"
          min="0"
          step="0.5"
          value={budget}
          onChange={(e) => setBudget(e.target.value)}
          placeholder={t('e.g. 25')}
        />
      </label>
      <label
        className={styles.toggleRow}
        title={t("After a save that changes the graph, replay the golden reference's input against the new flow automatically. Each check is a real run with a real cost, which is why it is off unless you ask for it — the dashboard offers the same check as a chip when the flow drifts.")}
      >
        <input
          type="checkbox"
          checked={goldenAutoRun}
          onChange={(e) => setGoldenAutoRun(e.target.checked)}
        />
        <span>{t('Re-run the golden check after each save that changes the flow ⓘ')}</span>
      </label>
      <label
        className={styles.field}
        title={t("Where this flow's Claude CLI runs: this server, any runner registered for you, or one runner. Runners are registered under Resources → Runners.")}
      >
        <span>{t('Runs on ⓘ')}</span>
        <select value={runner} onChange={(e) => setRunner(e.target.value)}>
          <option value="">{t('This server')}</option>
          <option value="any">{t('Any runner')}</option>
          {(runners ?? []).map((r) => (
            <option key={r.id} value={r.id}>
              {r.online ? t('{{name}} · online', { name: r.name }) : t('{{name}} · offline', { name: r.name })}
            </option>
          ))}
          {missingRunner && <option value={runner}>{t('{{id}} · not available', { id: runner })}</option>}
        </select>
      </label>
      <label className={styles.field}>
        <span>{t('Failure notification webhook')}</span>
        <input
          value={webhook}
          onChange={(e) => setWebhook(e.target.value)}
          placeholder="https://hooks.slack.com/services/…"
        />
      </label>
      <p className={styles.modalHint}>
        {t('POSTed with a Slack-compatible')} <code>text</code>{' '}
        {t('field plus run details whenever an execution of this flow fails.')}
      </p>

      <h4
        className={styles.sectionHead}
        title={t('Where a run goes when it stops for a human — waiting for approval, or asking a question. Fill in either one, or both, or neither: with none of it set the run still waits, and you answer it in the app.')}
      >
        {t('Remote approvals and questions ⓘ')}
      </h4>
      <p className={styles.modalHint}>
        {t('Optional, and independent of each other.')} <b>Slack</b>{' '}
        {t("can also answer: a ✅ reaction approves a plan (❌ rejects) and a reply in the question's thread becomes the run's next command — the app polls, so no public URL is needed.")}{' '}
        <b>Teams</b>{' '}
        {t('only tells you: its webhooks cannot carry a reply back. With neither, a waiting run is answered in the app.')}
      </p>
      <CredentialField
        label={t('Slack bot token (optional)')}
        value={slackCredential}
        onChange={setSlackCredential}
        what={t('the Slack bot (scopes: chat:write, reactions:read, channels:history)')}
      />
      <label
        className={styles.field}
        title={t("Channel id (C0123456789, from the channel's details) or a public channel name. The bot must be invited to it (/invite @bot).")}
      >
        <span>{t('Slack channel — only if you set a Slack token ⓘ')}</span>
        <input
          value={slackChannel}
          onChange={(e) => setSlackChannel(e.target.value)}
          placeholder="C0123456789"
        />
      </label>
      <label
        className={styles.field}
        title={t('A Teams incoming-webhook URL (Workflows). Posts the plan or the question as a card — notification only; answer from the app or Slack.')}
      >
        <span>{t('Teams webhook, on its own or alongside Slack (notify only) ⓘ')}</span>
        <input
          value={teamsWebhook}
          onChange={(e) => setTeamsWebhook(e.target.value)}
          placeholder="https://…webhook.office.com/…"
        />
      </label>

      <h4
        className={styles.sectionHead}
        title={t("Values substituted into this flow's prompts as {{NAME}} when a run starts. Rows come from Resources → Variables; typing here overrides that value for THIS flow only, and blank inherits it. Add flow-only variables below. Saved with the flow, so it remembers what its runs use.")}
      >
        {t('Variables ⓘ')}
      </h4>
      {variableNames.length === 0 && (
        <p className={styles.modalHint}>
          {t('None yet — define shared ones under')} <b>{t('Resources → Variables')}</b>
          {t(', or add one below.')}
        </p>
      )}
      {variableNames.map((varName) => {
        const org = orgVariables.find((v) => v.name === varName)
        return (
          <label key={varName} className={styles.field} title={org?.description || undefined}>
            <span>
              {'{{'}{varName}{'}}'}
              {org && variables[varName] !== undefined ? t(' — overridden') : ''}
            </span>
            <input
              value={variables[varName] ?? ''}
              placeholder={org ? t('{{value}} (organization)', { value: org.value }) : ''}
              onChange={(e) => setVariable(varName, e.target.value)}
            />
          </label>
        )
      })}
      <label className={styles.field}>
        <span>{t('Add a variable for this flow')}</span>
        <input
          value={newVarName}
          placeholder={t('NAME — then fill its value above')}
          onChange={(e) => setNewVarName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return
            e.preventDefault()
            const name = newVarName.trim().replace(/[^A-Za-z0-9_.-]/g, '_')
            if (!name) return
            // Created with an empty value so its row appears above ready to fill. Clearing a
            // flow-only variable's row later removes it again — blank has nothing to say.
            setVariables((prev) => (name in prev ? prev : { ...prev, [name]: '' }))
            setNewVarName('')
          }}
        />
      </label>

      {flow.id && <MemorySection flowId={flow.id} />}
      <div className={styles.modalActions}>
        <button className={styles.ghost} onClick={onClose}>
          {t('Cancel')}
        </button>
        <button className={styles.primary} onClick={() => void save()} disabled={busy}>
          {busy ? t('Saving…') : t('Save')}
        </button>
      </div>
    </Modal>
  )
}

/** How many notes are shown before "…and N older" — same page size as every list here. */
const MEMORY_PAGE = 20

/**
 * The flow's persistent memory: what agents noted for future runs, and the one way to wipe it.
 * Read-only beyond that on purpose — notes are the agents' channel to their future selves, and
 * editing them by hand would put words in a mouth that never said them.
 */
function MemorySection({ flowId }: { flowId: string }) {
  const { t } = useTranslation()
  const [mem, setMem] = useState<FlowMemoryView | null>(null)
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api
      .getFlowMemory(flowId)
      .then(setMem)
      .catch(() => setMem(null))
  }, [flowId])

  if (!mem) return null

  const clear = async () => {
    if (!confirm(t('Forget every note? Future runs of this flow start with an empty memory.'))) return
    setBusy(true)
    try {
      await api.clearFlowMemory(flowId)
      setMem({ ...mem, count: 0, notes: [] })
      setOpen(false)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.memory}>
      <div
        className={styles.memoryHead}
        title={t("Notes agents leave with the memory_append tool during runs of this flow; every future run reads them with memory_read. Stored in the app's database, per flow.")}
      >
        <span>
          {mem.count === 1
            ? t('Agent memory · {{n}} note ⓘ', { n: mem.count })
            : t('Agent memory · {{n}} notes ⓘ', { n: mem.count })}
        </span>
        {mem.count > 0 && (
          <>
            <button className={styles.ghost} onClick={() => setOpen(!open)}>
              {open ? t('Hide') : t('View')}
            </button>
            <button className={styles.ghost} onClick={() => void clear()} disabled={busy}>
              {t('Forget all')}
            </button>
          </>
        )}
      </div>
      {!mem.available && (
        <p className={styles.modalHint}>{t('Storage is unavailable — notes cannot be read right now.')}</p>
      )}
      {open && (
        <ul className={styles.memoryList}>
          {mem.notes.slice(0, MEMORY_PAGE).map((n) => (
            <li key={n.id} className={styles.memoryNote}>
              <span className={styles.memoryTime}>{timeAgo(n.createdAt)}</span>
              <span className={styles.memoryText}>{n.note}</span>
            </li>
          ))}
          {mem.count > MEMORY_PAGE && (
            <li className={styles.memoryMore}>{t('…and {{n}} older', { n: mem.count - MEMORY_PAGE })}</li>
          )}
        </ul>
      )}
    </div>
  )
}

/**
 * Version history with one-click rollback, for a flow opened from the dashboard. The list itself
 * is {@link FlowVersions}, shared with Studio's Versions tab.
 */
export function VersionsModal({
  flow,
  onClose,
  pushError,
}: {
  flow: BackendFlow
  onClose: () => void
  pushError: (m: string) => void
}) {
  const { t } = useTranslation()
  return (
    <Modal title={t('History — {{name}}', { name: flow.name })} onClose={onClose}>
      <FlowVersions flowId={flow.id ?? null} onRestored={onClose} pushError={pushError} />
    </Modal>
  )
}
