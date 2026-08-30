import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { CreatedRunner, Runner, RunnerScope, RunnersListing } from '../api/types.ts'
import { usePermissions } from '../state/permissionRules.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { knownGroups, useGroups } from './groups.ts'
import { Spinner } from './Spinner.tsx'
import { usePanelLoad } from './usePanelLoad.ts'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/** How often the roster asks again, so a runner that just connected turns green without a reload. */
const POLL_MS = 15_000

const EMPTY: RunnersListing = { runners: [], hubUrl: '', mayCreate: { organization: false, groups: [], user: false } }

/** The image the release publishes; the tag follows the hub's version, `latest` is the one to copy. */
const RUNNER_IMAGE = 'ghcr.io/gergilcan/concentus-runner:latest'

/**
 * The scope select's value: the two plain scopes as themselves, a group as `group:<id>` — one
 * string per option, because a select holds one string.
 */
type ScopeChoice = 'organization' | 'user' | `group:${string}`

function scopeOf(choice: ScopeChoice): { scope: RunnerScope; groupId?: string } {
  if (choice === 'organization' || choice === 'user') return { scope: choice }
  return { scope: 'group', groupId: choice.slice('group:'.length) }
}

/** How the CLI on the runner is signed in, in the words the tooltip uses. */
const AUTH_LABEL: Record<NonNullable<Runner['authKind']>, string> = {
  subscription: 'Claude subscription',
  'api-key': 'API key',
  none: 'no Claude login',
}

/**
 * Machines that execute Claude CLI turns for this organization on their own login.
 *
 * <p>The hub keeps the flows, the runs, the approvals and the interface; a runner keeps the
 * Claude login, the folders, the clones and the processes — the credential stays where its
 * owner runs the process. So a runner is registered by whoever operates it, with a scope that
 * says who may run flows on it: everyone, one group, or that person alone.
 *
 * <p>Laid out as the Service accounts roster it sits beside, and shown to every role: a viewer
 * sees where things run, and the backend has already filtered the list to what the caller may
 * see. Only `mayCreate` decides whether "+ New" is offered. The registration token is shown
 * exactly once, with the three ways to start a runner filled in — the manual is here because
 * this is the one moment the person has the token in hand.
 */
export function RunnersPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const { canAdminister } = usePermissions()
  // For the group options' names and for who manages which group; the listing's own rows
  // already carry their group's name.
  const groups = useGroups({ all: true })
  const {
    value: listing,
    setValue: setListing,
    reload: load,
  } = usePanelLoad(() => api.listRunners(), pushError, EMPTY)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [choice, setChoice] = useState<ScopeChoice | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  // The one moment the token exists on a screen. Cleared by "Done", never re-fetchable.
  const [minted, setMinted] = useState<CreatedRunner | null>(null)
  const [copied, setCopied] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)

  // Online is a fact about the last 45 seconds, so the roster asks again while it is open. A
  // poll that fails says nothing: the first load already spoke, and a toast every fifteen
  // seconds about a backend blip would be worse than a roster a minute stale.
  useEffect(() => {
    const id = setInterval(() => {
      api
        .listRunners()
        .then(setListing)
        .catch(() => {})
    }, POLL_MS)
    return () => clearInterval(id)
  }, [setListing])

  const groupNameOf = (id: string | null, fallback: string | null) =>
    knownGroups(groups).find((g) => g.id === id)?.name ?? fallback ?? id ?? ''

  /** The caller may rename, revoke and delete it: an administrator, its owner, or a manager of its group. */
  const mayEdit = (r: Runner) =>
    canAdminister || r.mine || (r.scope === 'group' && groups.mine.some((g) => g.id === r.groupId && g.manager))

  const create = async () => {
    if (!name.trim() || !chosen) return
    setBusy('new')
    try {
      const { scope, groupId } = scopeOf(chosen)
      const created = await api.createRunner(name.trim(), scope, groupId)
      setMinted(created)
      setCopied(null)
      setName('')
      setCreating(false)
      load()
    } catch (e) {
      // A taken name and the GROUPS gate are refused by the backend, and its sentence is the message.
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const copy = async (what: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(what)
      setTimeout(() => setCopied((c) => (c === what ? null : c)), 2000)
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const revoke = async (r: Runner) => {
    if (!confirm(t('Revoke "{{name}}"? This cannot be undone.', { name: r.name }))) return
    setBusy(r.id)
    try {
      await api.revokeRunner(r.id)
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const remove = async (r: Runner) => {
    if (!confirm(t('Delete "{{name}}"? Runs that ran on it keep its name.', { name: r.name }))) return
    setBusy(r.id)
    try {
      await api.deleteRunner(r.id)
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const rename = async (r: Runner) => {
    if (!renaming) return
    const next = renaming.name.trim()
    // Leaving the field with nothing changed is a cancel, not a request.
    if (!next || next === r.name) return setRenaming(null)
    setBusy(r.id)
    try {
      const updated = await api.renameRunner(r.id, next)
      setListing((prev) =>
        prev ? { ...prev, runners: prev.runners.map((x) => (x.id === updated.id ? updated : x)) } : prev,
      )
      setRenaming(null)
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  // What the select may offer, in the order the scopes widen: the organization, each group the
  // caller may register for, themselves. Nothing offered means no button at all.
  const mayCreate = listing?.mayCreate ?? EMPTY.mayCreate
  const choices: Array<{ value: ScopeChoice; label: string }> = [
    ...(mayCreate.organization ? [{ value: 'organization' as const, label: t('Organization') }] : []),
    ...mayCreate.groups.map((id) => ({
      value: `group:${id}` as const,
      label: t('Group: {{name}}', { name: groupNameOf(id, null) }),
    })),
    ...(mayCreate.user ? [{ value: 'user' as const, label: t('Only me') }] : []),
  ]
  const chosen = choice ?? choices[0]?.value ?? null

  if (!listing) return <Spinner />

  const { runners } = listing

  const scopeMeans = (scope: RunnerScope) =>
    scope === 'organization'
      ? t('Anybody in the organization may run flows on it.')
      : scope === 'group'
        ? t('Its members and the administrators may run flows on it.')
        : t('Only you may run flows on it — never a schedule, never another account.')

  /** The chip's tooltip: who may run flows on this one, naming the owner when it is not the reader. */
  const scopeTitle = (r: Runner) => {
    if (r.scope === 'organization') return scopeMeans('organization')
    if (r.scope === 'group')
      return t('Members of {{name}} and the administrators may run flows on it.', {
        name: groupNameOf(r.groupId, r.groupName),
      })
    if (r.mine) return scopeMeans('user')
    return r.ownerEmail
      ? t('Only {{owner}} may run flows on it — it is their machine and their login.', { owner: r.ownerEmail })
      : t('Only its owner may run flows on it — it is their machine and their login.')
  }

  /** What the dot knows: the host, as it introduced itself, and whether it is there now. */
  const statusTitle = (r: Runner) => {
    const facts = [
      r.hostname,
      r.os && r.arch ? `${r.os}/${r.arch}` : (r.os ?? r.arch),
      r.version,
      r.authKind ? t(AUTH_LABEL[r.authKind]) : null,
    ].filter((f): f is string => !!f)
    const state = r.online ? t('online') : t('offline')
    return facts.length > 0 ? `${state} · ${facts.join(' · ')}` : state
  }

  const hubUrl = minted?.hubUrl || listing.hubUrl
  const dockerLine = minted
    ? `docker run -d --name concentus-runner -e CONCENTUS_RUNNER_URL=${hubUrl} -e CONCENTUS_RUNNER_TOKEN=${minted.token} -e CLAUDE_CODE_OAUTH_TOKEN=<from claude setup-token> ${RUNNER_IMAGE}`
    : ''
  const javaLine = minted ? `java -jar concentus-backend.jar runner --url ${hubUrl} --token ${minted.token}` : ''

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Runners')}</h3>
          <p className={panels.hint}>
            <span
              className={panels.statusPill}
              title={t(
                "Machines that execute this organization's Claude CLI flows on their own login. An administrator sees all of them; anybody else the organization's, their groups' and their own.",
              )}
            >
              {runners.length === 1 ? t('{{count}} runner', { count: 1 }) : t('{{count}} runners', { count: runners.length })}
            </span>{' '}
            {t('A machine you operate that runs Claude CLI flows on its own login; the server keeps everything else.')}
          </p>
        </div>
        {choices.length > 0 && (
          <button
            className={styles.newBtn}
            title={creating ? undefined : t('Register a machine. Its token is shown once.')}
            onClick={() => setCreating((open) => !open)}
          >
            {creating ? t('Cancel') : t('+ New')}
          </button>
        )}
      </div>

      {minted && (
        <div className={styles.redirectBox} role="status">
          <span className={styles.redirectLabel}>
            {t('Token for "{{name}}"', { name: minted.runner.name })} — {t('Shown once.')}
          </span>
          <code>{minted.token}</code>
          <button
            className={styles.saveBtn}
            title={t('Copy it. The runner presents it when it connects; only its hash is stored here.')}
            onClick={() => void copy('token', minted.token)}
          >
            {copied === 'token' ? t('Copied') : t('Copy')}
          </button>
          <button
            className={styles.newBtn}
            title={t('Hide it. Only its hash is stored, so it cannot be shown again.')}
            onClick={() => setMinted(null)}
          >
            {t('Done')}
          </button>
          <ul className={styles.startWays}>
            <li
              title={t(
                'The runner image, with Node, git and the Claude CLI inside. CLAUDE_CODE_OAUTH_TOKEN comes from "claude setup-token" on a machine that is signed in.',
              )}
            >
              <span>Docker</span>
              <pre>{dockerLine}</pre>
              <button className={styles.rowBtn} onClick={() => void copy('docker', dockerLine)}>
                {copied === 'docker' ? t('Copied') : t('Copy')}
              </button>
            </li>
            <li
              title={t(
                'The same jar as the server, in runner mode: no database, no web server. It uses the Claude login on that machine.',
              )}
            >
              <span>Java</span>
              <pre>{javaLine}</pre>
              <button className={styles.rowBtn} onClick={() => void copy('java', javaLine)}>
                {copied === 'java' ? t('Copied') : t('Copy')}
              </button>
            </li>
            <li title={t('The desktop app keeps working on its own and also executes for this server.')}>
              <span>{t('Desktop app')}</span>
              <pre>{t('tray → Set up… → Server, paste the URL and this token.')}</pre>
              <button className={styles.rowBtn} onClick={() => void copy('url', hubUrl)}>
                {copied === 'url' ? t('Copied') : t('Copy URL')}
              </button>
            </li>
          </ul>
        </div>
      )}

      {creating && chosen && (
        <div className={styles.addMember}>
          <div className={styles.addMemberFields}>
            <label className={styles.field}>
              <span>{t('Name')}</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void create()
                }}
                placeholder="office-pc"
                maxLength={80}
                autoFocus
              />
            </label>
            <label className={styles.field}>
              <span>{t('Scope')}</span>
              <select
                value={chosen}
                title={t("Who may run flows on it: everyone in the organization, one group, or you alone. It is somebody's machine and somebody's login.")}
                onChange={(e) => setChoice(e.target.value as ScopeChoice)}
              >
                {choices.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className={styles.addMemberFoot}>
            <span className={panels.hint}>{scopeMeans(scopeOf(chosen).scope)}</span>
            <button
              className={styles.saveBtn}
              disabled={busy === 'new' || !name.trim()}
              onClick={() => void create()}
            >
              {busy === 'new' ? t('Creating…') : t('Create')}
            </button>
          </div>
        </div>
      )}

      {runners.length === 0 ? (
        <p
          className={styles.emptyRoster}
          title={t("Register one with + New, start it where the Claude login is, and pick it under a flow's Settings → Runs on.")}
        >
          {t('No runners yet.')}
        </p>
      ) : (
        <ul className={styles.memberList}>
          {runners.map((r) => {
            const revoked = r.revokedAt != null
            const editable = mayEdit(r) && !revoked
            const editing = renaming?.id === r.id
            return (
              <li key={r.id} className={cx(styles.memberRow, revoked && styles.revokedRow)}>
                <span className={styles.memberWho}>
                  <span
                    className={cx(panels.sDot, r.online && panels.sOk)}
                    role="img"
                    aria-label={r.online ? t('online') : t('offline')}
                    title={statusTitle(r)}
                  />
                  {editing ? (
                    <input
                      aria-label={t('New name for {{name}}', { name: r.name })}
                      value={renaming.name}
                      autoFocus
                      onChange={(e) => setRenaming({ id: r.id, name: e.target.value })}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') void rename(r)
                        if (e.key === 'Escape') setRenaming(null)
                      }}
                      onBlur={() => void rename(r)}
                    />
                  ) : (
                    <span
                      className={styles.memberEmail}
                      title={editable ? t('Double-click to rename.') : undefined}
                      onDoubleClick={editable ? () => setRenaming({ id: r.id, name: r.name }) : undefined}
                    >
                      {r.name}
                    </span>
                  )}
                  <span className={styles.roleChip} title={scopeTitle(r)}>
                    {r.scope === 'organization'
                      ? t('Organization')
                      : r.scope === 'group'
                        ? groupNameOf(r.groupId, r.groupName)
                        : t('Only me')}
                  </span>
                  {r.online && !revoked && (
                    <span
                      className={styles.roleChip}
                      title={t('Claude CLI turns running on it now, of the slots it allows itself.')}
                    >
                      {r.capacity != null
                        ? t('{{busy}} / {{capacity}} running', { busy: r.busy, capacity: r.capacity })
                        : t('{{busy}} running', { busy: r.busy })}
                    </span>
                  )}
                  {revoked && (
                    <span
                      className={styles.roleChip}
                      title={t('Revoked {{when}}. Its token is refused; the row stays as the record.', {
                        when: timeAgo(r.revokedAt as number),
                      })}
                    >
                      {t('revoked')}
                    </span>
                  )}
                </span>
                <span className={styles.memberMeta}>
                  {r.lastSeenAt != null ? t('last seen {{when}}', { when: timeAgo(r.lastSeenAt) }) : t('never connected')}
                  {' · '}
                  {r.createdBy
                    ? t('created {{when}} by {{who}}', { when: timeAgo(r.createdAt), who: r.createdBy })
                    : t('created {{when}}', { when: timeAgo(r.createdAt) })}
                </span>
                <span className={styles.memberRole}>
                  {editable && (
                    <>
                      <button
                        className={styles.rowBtn}
                        aria-label={t('Rename')}
                        title={t('Rename')}
                        disabled={busy === r.id}
                        onClick={() => setRenaming({ id: r.id, name: r.name })}
                      >
                        ✎
                      </button>
                      <button
                        className={cx(styles.rowBtn, styles.rowBtnDanger)}
                        title={t('Refuse its token from now on. What it is running finishes; nothing new is sent to it. Cannot be undone.')}
                        disabled={busy === r.id}
                        onClick={() => void revoke(r)}
                      >
                        {t('Revoke')}
                      </button>
                    </>
                  )}
                  {mayEdit(r) && (
                    <button
                      className={cx(styles.rowBtn, styles.rowBtnDanger)}
                      title={t('Removes the row. Runs that ran on it keep its name.')}
                      disabled={busy === r.id}
                      onClick={() => void remove(r)}
                    >
                      {t('Delete')}
                    </button>
                  )}
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
