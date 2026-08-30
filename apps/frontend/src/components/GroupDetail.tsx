import { type ReactNode, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { FacadeProfile, Group, GroupMember, GroupPolicy, GroupPolicyView, SettingDef } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { PERMISSION_MODE_ORDER } from '../utils/permissionCeiling.ts'
import { timeAgo } from './flowFormat.ts'
import { MODE_LABEL } from './policyFields.ts'
import { Spinner } from './Spinner.tsx'
import { usePanelLoad } from './usePanelLoad.ts'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

type Tab = 'members' | 'settings' | 'policy'

interface Gate {
  /** The license lets the group be changed. Off, everything renders read-only under the refusal. */
  allowed: boolean
  refusal: string | null
}

/**
 * One group, opened: who is in it, the settings it overrides, the rules it narrows.
 *
 * Three tabs rather than one long form because they are three different questions with three
 * different readers — membership is asked often, settings and policy once — and a manager who
 * came to add a person should not scroll past a budget field to do it.
 */
export function GroupDetail({
  group,
  allowed,
  refusal,
  pushError,
  onChanged,
}: Gate & {
  group: Group
  pushError: (m: string) => void
  /** Something the roster shows — a member count — moved. */
  onChanged: () => void
}) {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('members')
  const tabs: Array<{ id: Tab; label: string; title: string }> = [
    { id: 'members', label: 'Members', title: 'Who is in the group, and who manages it.' },
    { id: 'settings', label: 'Settings', title: "The per-run settings this group overrides. Anything not set here follows the organization." },
    { id: 'policy', label: 'Policy', title: "The organization's rules, narrowed for this group. A rule left to inherit is the organization's." },
  ]
  return (
    <div className={styles.groupDetail}>
      <div className={styles.kbTabs} role="tablist">
        {tabs.map((td) => (
          <button
            key={td.id}
            role="tab"
            aria-selected={tab === td.id}
            className={tab === td.id ? styles.kbTabOn : ''}
            title={t(td.title)}
            onClick={() => setTab(td.id)}
          >
            {t(td.label)}
          </button>
        ))}
      </div>
      {tab === 'members' && <GroupMembers group={group} pushError={pushError} onChanged={onChanged} />}
      {tab === 'settings' && <GroupSettingsTab group={group} allowed={allowed} refusal={refusal} pushError={pushError} />}
      {tab === 'policy' && <GroupPolicyTab group={group} allowed={allowed} refusal={refusal} pushError={pushError} />}
    </div>
  )
}

/** Title case for a role that arrives shouting from the API. */
function roleLabel(role: string): string {
  return role ? role.charAt(0).toUpperCase() + role.slice(1).toLowerCase() : ''
}

const MANAGER_MEANS =
  "May add and remove members and edit the group's settings and policy. Making, renaming or deleting the group stays with the organization's administrators."

/* ---------------- members ---------------- */

function GroupMembers({
  group,
  pushError,
  onChanged,
}: {
  group: Group
  pushError: (m: string) => void
  onChanged: () => void
}) {
  const { t } = useTranslation()
  const {
    value: members,
    setValue: setMembers,
    reload,
  } = usePanelLoad(() => api.listGroupMembers(group.id), pushError, [])
  // The organization's accounts, for the picker: a group is made of people already here.
  const { value: accounts } = usePanelLoad(() => api.listMembers(), pushError, [])
  const [adding, setAdding] = useState(false)
  const [userId, setUserId] = useState('')
  const [asManager, setAsManager] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)

  const candidates = (accounts ?? []).filter((a) => !(members ?? []).some((m) => m.userId === a.id))

  const add = async () => {
    if (!userId) return
    setBusy('new')
    try {
      await api.addGroupMember(group.id, userId, asManager)
      setUserId('')
      setAsManager(false)
      setAdding(false)
      reload()
      onChanged()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  // Membership has one write: adding. Sending the same account again with the other flag is how
  // a member becomes a manager, or stops being one.
  const toggleManager = async (m: GroupMember) => {
    setBusy(m.userId)
    try {
      const updated = await api.addGroupMember(group.id, m.userId, !m.manager)
      setMembers((prev) => (prev ?? []).map((x) => (x.userId === updated.userId ? updated : x)))
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const remove = async (m: GroupMember) => {
    setBusy(m.userId)
    try {
      await api.removeGroupMember(group.id, m.userId)
      reload()
      onChanged()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  if (!members) return <Spinner />

  return (
    <div>
      <div className={styles.rosterHead}>
        <p className={panels.hint}>
          {members.length === 0
            ? t('Nobody in this group yet.')
            : members.length === 1
              ? t('{{count}} account.', { count: 1 })
              : t('{{count}} accounts.', { count: members.length })}{' '}
          {t('Members see what is visible to the group; managers also change who is in it.')}
        </p>
        <button
          className={styles.newBtn}
          disabled={!adding && candidates.length === 0}
          title={
            candidates.length === 0 && !adding
              ? t('Everybody in the organization is already in this group.')
              : t("From the organization's accounts. A person can be in several groups.")
          }
          onClick={() => setAdding((o) => !o)}
        >
          {adding ? t('Cancel') : t('Add member')}
        </button>
      </div>

      {adding && (
        <div className={styles.addMember}>
          <div className={styles.addMemberFields}>
            <label className={styles.field}>
              <span>{t('Account')}</span>
              <select value={userId} onChange={(e) => setUserId(e.target.value)}>
                <option value="">{t('Pick one…')}</option>
                {candidates.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.email}
                  </option>
                ))}
              </select>
            </label>
            <label className={panels.checkField} title={t(MANAGER_MEANS)}>
              <input type="checkbox" checked={asManager} onChange={(e) => setAsManager(e.target.checked)} />
              <span>{t('Manager')}</span>
            </label>
          </div>
          <div className={styles.addMemberFoot}>
            <span className={panels.hint}>{t('An existing account of the organization; it keeps its role.')}</span>
            <button className={styles.saveBtn} disabled={busy === 'new' || !userId} onClick={() => void add()}>
              {busy === 'new' ? t('Adding…') : t('Add member')}
            </button>
          </div>
        </div>
      )}

      {members.length > 0 && (
        <ul className={styles.memberList}>
          {members.map((m) => (
            <li key={m.userId} className={styles.memberRow}>
              <span className={styles.memberWho}>
                <span className={styles.memberEmail}>{m.email}</span>
              </span>
              <span className={styles.memberMeta}>
                {t(roleLabel(m.role))} · {t('joined {{when}}', { when: timeAgo(m.createdAt) })}
              </span>
              <span className={styles.memberRole}>
                <label className={panels.checkField} title={t(MANAGER_MEANS)}>
                  <input
                    type="checkbox"
                    aria-label={t('Manager: {{email}}', { email: m.email })}
                    checked={m.manager}
                    disabled={busy === m.userId}
                    onChange={() => void toggleManager(m)}
                  />
                  <span>{t('manager')}</span>
                </label>
                <button
                  className={cx(styles.rowBtn, styles.rowBtnDanger)}
                  disabled={busy === m.userId}
                  title={t('Takes this account out of the group. Its organization account is untouched.')}
                  onClick={() => void remove(m)}
                >
                  {t('Remove')}
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/* ---------------- settings ---------------- */

function GroupSettingsTab({ group, allowed, refusal, pushError }: Gate & { group: Group; pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const { value: settings, setValue: setSettings } = usePanelLoad(() => api.getGroupSettings(group.id), pushError)
  // The overrides being edited, or null while they are exactly what is stored.
  const [overrides, setOverrides] = useState<Record<string, string> | null>(null)
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)

  if (!settings) return <Spinner />

  const draft = overrides ?? settings.values
  const dirty = JSON.stringify(draft) !== JSON.stringify(settings.values)

  const change = (key: string, value: string) => {
    setSaved(false)
    setOverrides({ ...draft, [key]: value })
  }
  const reset = (key: string) => {
    setSaved(false)
    const next = { ...draft }
    delete next[key]
    setOverrides(next)
  }
  const save = async () => {
    setBusy(true)
    try {
      // Only what this group sets: PUT replaces, so a key left out inherits again.
      const next = await api.saveGroupSettings(group.id, draft)
      setSettings(next)
      setOverrides(null)
      setSaved(true)
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const sections = new Map<string, SettingDef[]>()
  for (const def of settings.keys) {
    const list = sections.get(def.group) ?? []
    list.push(def)
    sections.set(def.group, list)
  }

  return (
    <div>
      <div className={styles.rosterHead}>
        <p className={panels.hint}>
          {t('Read per run: a flow in this group runs with these values. Anything not set here follows the organization.')}
        </p>
        <button
          className={styles.saveBtn}
          disabled={busy || !dirty || !allowed}
          title={!allowed ? (refusal ?? undefined) : undefined}
          onClick={() => void save()}
        >
          {busy ? t('Saving…') : t('Save')}
        </button>
      </div>
      {!allowed && refusal && (
        <p className={panels.hint} role="note">
          <b>{t('Read-only.')}</b> {refusal}
        </p>
      )}
      {saved && !dirty && <p className={styles.savedNote}>{t('Saved. Applies to the next run.')}</p>}
      {settings.keys.length === 0 && <p className={styles.muted}>{t('Nothing here can be set per group.')}</p>}
      {[...sections.entries()].map(([section, defs]) => (
        <section key={section} className={styles.settingGroup}>
          <h4 className={styles.h4}>{section}</h4>
          <div className={styles.settingList}>
            {defs.map((def) => (
              <GroupSettingRow
                key={def.key}
                def={def}
                value={draft[def.key]}
                inherited={settings.inherited[def.key] ?? ''}
                disabled={!allowed}
                onChange={(v) => change(def.key, v)}
                onReset={() => reset(def.key)}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

/**
 * One key: the inherited value, muted and marked, until the group sets its own; Reset puts it
 * back. The same controls the organization's Settings tab uses, so a value reads the same in both.
 */
function GroupSettingRow({
  def,
  value,
  inherited,
  disabled,
  onChange,
  onReset,
}: {
  def: SettingDef
  /** The group's own value, or undefined while it inherits. */
  value: string | undefined
  inherited: string
  disabled: boolean
  onChange: (value: string) => void
  onReset: () => void
}) {
  const { t } = useTranslation()
  const overridden = value !== undefined
  const shown = overridden ? value : inherited
  const id = `group-setting-${def.key}`

  return (
    <div className={cx(styles.settingRow, overridden && styles.settingEdited)}>
      <div className={styles.settingText}>
        <label htmlFor={id}>{def.label}</label>
        <p>{def.help}</p>
        <span className={styles.settingMeta}>
          {overridden ? (
            t('set for this group')
          ) : (
            <span title={t("The organization's value, or the deployment's. Change it to set one for this group alone.")}>
              {t('inherited')}
            </span>
          )}
          {def.restartRequired && ` · ${t('needs a restart')}`}
        </span>
      </div>
      <div className={cx(styles.settingControl, !overridden && styles.inheritedControl)}>
        {def.type === 'BOOLEAN' ? (
          <select id={id} value={shown || 'false'} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
            <option value="true">{t('On')}</option>
            <option value="false">{t('Off')}</option>
          </select>
        ) : def.type === 'CHOICE' ? (
          <select id={id} value={shown} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
            <option value="">{t('(unset)')}</option>
            {def.options.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        ) : (
          <input
            id={id}
            type={def.type === 'NUMBER' ? 'number' : def.type === 'SECRET' ? 'password' : 'text'}
            value={shown}
            disabled={disabled}
            placeholder={def.type === 'LIST' ? t('comma, separated') : ''}
            onChange={(e) => onChange(e.target.value)}
          />
        )}
        {overridden && (
          <button
            type="button"
            className={styles.rowBtn}
            disabled={disabled}
            title={t('Back to the inherited value.')}
            onClick={onReset}
          >
            {t('Reset')}
          </button>
        )}
      </div>
    </div>
  )
}

/* ---------------- policy ---------------- */

/** The group's own five fields, without the effective view the server bundles with them. */
function ownPolicy(view: GroupPolicyView): GroupPolicy {
  return {
    defaultFacadeProfileId: view.defaultFacadeProfileId,
    requireFacade: view.requireFacade,
    maxPermissionMode: view.maxPermissionMode,
    monthlyBudgetUsd: view.monthlyBudgetUsd,
    publishRequiresApproval: view.publishRequiresApproval,
  }
}

function GroupPolicyTab({ group, allowed, refusal, pushError }: Gate & { group: Group; pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const { value: view, setValue: setView } = usePanelLoad(() => api.getGroupPolicy(group.id), pushError)
  const [facades, setFacades] = useState<FacadeProfile[]>([])
  const [draft, setDraft] = useState<GroupPolicy | null>(null)
  const [busy, setBusy] = useState(false)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  useEffect(() => {
    // The default-profile picker: a failure leaves it with only "none", which is still usable.
    api.listFacadeProfiles().then(setFacades).catch(() => setFacades([]))
  }, [])

  if (!view) return <Spinner />

  const policy = draft ?? ownPolicy(view)
  const effective = view.effective
  const dirty = JSON.stringify(policy) !== JSON.stringify(ownPolicy(view))
  const patch = (p: Partial<GroupPolicy>) => setDraft({ ...policy, ...p })
  const editable = allowed

  const save = async () => {
    setBusy(true)
    try {
      const next = await api.saveGroupPolicy(group.id, policy)
      setView(next)
      setDraft(null)
      setSavedAt(Date.now())
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const facadeName = (id: string | null | undefined) =>
    id ? (facades.find((p) => p.id === id)?.name ?? id) : t('none')
  const onOff = (v: boolean | null | undefined) => (v ? t('On') : t('Off'))
  const modeName = (m: string | null | undefined) =>
    m && m in MODE_LABEL ? t(MODE_LABEL[m as keyof typeof MODE_LABEL]) : t('no ceiling')
  const budgetName = (n: number | null | undefined) => (n != null && n > 0 ? `${n} USD` : t('none'))

  return (
    <section className={styles.settingGroup}>
      <p className={panels.hint}>
        {t("The organization's rules, narrowed for this group. A rule left to inherit is whatever the organization says.")}
      </p>
      {!allowed && refusal && (
        <p className={panels.hint} role="note">
          <b>{t('Read-only.')}</b> {refusal}
        </p>
      )}

      <fieldset className={styles.field} disabled={!editable} aria-label={t('Group policy')}>
        <PolicyField
          label={t('Default facade profile for independent workers')}
          inherited={policy.defaultFacadeProfileId === null}
          onInherit={(on) => patch({ defaultFacadeProfileId: on ? null : (effective.defaultFacadeProfileId ?? '') })}
          effective={facadeName(effective.defaultFacadeProfileId)}
          disabled={!editable}
        >
          <select
            aria-label={t('Default facade profile for independent workers')}
            value={policy.defaultFacadeProfileId ?? ''}
            disabled={!editable || policy.defaultFacadeProfileId === null}
            onChange={(e) => patch({ defaultFacadeProfileId: e.target.value })}
          >
            <option value="">{t('— none —')}</option>
            {facades.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </PolicyField>

        <PolicyField
          label={t('Require a facade profile on every independent worker that reaches MCP')}
          inherited={policy.requireFacade === null}
          onInherit={(on) => patch({ requireFacade: on ? null : effective.requireFacade })}
          effective={onOff(effective.requireFacade)}
          disabled={!editable}
        >
          <select
            aria-label={t('Require a facade profile on every independent worker that reaches MCP')}
            value={policy.requireFacade ? 'true' : 'false'}
            disabled={!editable || policy.requireFacade === null}
            onChange={(e) => patch({ requireFacade: e.target.value === 'true' })}
          >
            <option value="true">{t('On')}</option>
            <option value="false">{t('Off')}</option>
          </select>
        </PolicyField>

        <PolicyField
          label={t('Permission ceiling')}
          inherited={policy.maxPermissionMode === null}
          onInherit={(on) => patch({ maxPermissionMode: on ? null : (effective.maxPermissionMode ?? '') })}
          effective={modeName(effective.maxPermissionMode)}
          disabled={!editable}
        >
          <select
            aria-label={t('Permission ceiling')}
            value={policy.maxPermissionMode ?? ''}
            disabled={!editable || policy.maxPermissionMode === null}
            onChange={(e) => patch({ maxPermissionMode: e.target.value })}
          >
            <option value="">{t('— no ceiling —')}</option>
            {PERMISSION_MODE_ORDER.map((m) => (
              <option key={m} value={m}>
                {t(MODE_LABEL[m])}
              </option>
            ))}
          </select>
        </PolicyField>

        <PolicyField
          label={t('Group budget (USD per month)')}
          inherited={policy.monthlyBudgetUsd === null}
          onInherit={(on) => patch({ monthlyBudgetUsd: on ? null : (effective.monthlyBudgetUsd ?? 0) })}
          effective={budgetName(effective.monthlyBudgetUsd)}
          disabled={!editable}
          hint={t("The group's own ceiling, summed over its flows, beside the organization's.")}
        >
          <input
            type="number"
            aria-label={t('Group budget (USD per month)')}
            value={policy.monthlyBudgetUsd ?? ''}
            disabled={!editable || policy.monthlyBudgetUsd === null}
            onChange={(e) => patch({ monthlyBudgetUsd: Math.max(0, Number(e.target.value)) })}
          />
        </PolicyField>

        <PolicyField
          label={t("Published endpoints need an administrator's approval")}
          inherited={policy.publishRequiresApproval === null}
          onInherit={(on) => patch({ publishRequiresApproval: on ? null : effective.publishRequiresApproval })}
          effective={onOff(effective.publishRequiresApproval)}
          disabled={!editable}
        >
          <select
            aria-label={t("Published endpoints need an administrator's approval")}
            value={policy.publishRequiresApproval ? 'true' : 'false'}
            disabled={!editable || policy.publishRequiresApproval === null}
            onChange={(e) => patch({ publishRequiresApproval: e.target.value === 'true' })}
          >
            <option value="true">{t('On')}</option>
            <option value="false">{t('Off')}</option>
          </select>
        </PolicyField>
      </fieldset>

      {editable && (
        <button className={styles.saveBtn} disabled={busy || !dirty} onClick={() => void save()}>
          {busy ? t('Saving…') : t('Save')}
        </button>
      )}
      {savedAt && !dirty && <p className={panels.hint}>{t('Saved. Applies to the next run.')}</p>}
    </section>
  )
}

/**
 * One rule: its control, an "inherit" switch that greys the control out, and what runs actually
 * get — the group's value where set, the organization's otherwise — as one muted line.
 */
function PolicyField({
  label,
  inherited,
  onInherit,
  effective,
  disabled,
  hint,
  children,
}: {
  label: string
  inherited: boolean
  onInherit: (inherit: boolean) => void
  effective: string
  disabled: boolean
  hint?: string
  children: ReactNode
}) {
  const { t } = useTranslation()
  return (
    <div className={styles.policyField}>
      <div className={styles.policyHead}>
        <span className={styles.policyLabel} title={hint}>
          {label}
          {hint && ' ⓘ'}
        </span>
        <label className={panels.checkField} title={t('Leave this rule as the organization has it.')}>
          <input
            type="checkbox"
            aria-label={t('Inherit: {{label}}', { label })}
            checked={inherited}
            disabled={disabled}
            onChange={(e) => onInherit(e.target.checked)}
          />
          <span>{t('inherit')}</span>
        </label>
      </div>
      {children}
      <span
        className={styles.settingMeta}
        title={t("What runs of this group's flows actually get: the group's value where set, the organization's otherwise.")}
      >
        {t('in effect: {{value}}', { value: effective })}
      </span>
    </div>
  )
}
