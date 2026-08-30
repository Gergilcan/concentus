import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { FacadeProfile, OrgPolicy, OrgPolicyView } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { PERMISSION_MODE_ORDER } from '../utils/permissionCeiling.ts'
import { Field, SelectField } from './fields.tsx'
import { MODE_LABEL } from './policyFields.ts'
import { Spinner } from './Spinner.tsx'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/** The stored budget as the number box shows it: blank for none, never "0" or "null". */
function budgetText(policy: OrgPolicy): string {
  return policy.monthlyBudgetUsd != null && policy.monthlyBudgetUsd > 0 ? String(policy.monthlyBudgetUsd) : ''
}

/**
 * The organization's rules over every flow in it, one record, edited here by an admin.
 *
 * <p>Enterprise only, and the panel says so in the license's own words rather than hiding: on a
 * Team deployment the fields render read-only with whatever was saved (a downgrade is not a
 * deletion) under the refusal sentence, and nothing in them is enforced. An admin on Enterprise
 * edits; a member on Enterprise reads — the same shape, one sentence different.
 *
 * <p>The context roots are mentioned and not editable on purpose: they are a deployment setting
 * (`local.context-roots`), decided by whoever runs the server, and a second copy here would be a
 * rule that could disagree with the one actually enforced.
 */
export function PoliciesPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const [view, setView] = useState<OrgPolicyView | null>(null)
  const [draft, setDraft] = useState<OrgPolicy | null>(null)
  const [facades, setFacades] = useState<FacadeProfile[]>([])
  const [busy, setBusy] = useState(false)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  useEffect(() => {
    api
      .getOrgPolicy()
      .then((v) => {
        setView(v)
        setDraft(v.policy)
      })
      .catch((e) => pushError(errMessage(e)))
    // The default-profile picker: a failure leaves it with only "none", which is still usable.
    api.listFacadeProfiles().then(setFacades).catch(() => setFacades([]))
    // pushError is stable for the panel's life; re-fetching on its identity would be a loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!view || !draft) return <Spinner />

  const editable = view.canEdit
  const dirty = JSON.stringify(draft) !== JSON.stringify(view.policy)
  const patch = (p: Partial<OrgPolicy>) => setDraft((d) => (d ? { ...d, ...p } : d))

  const save = async () => {
    setBusy(true)
    try {
      const next = await api.saveOrgPolicy(draft)
      setView(next)
      setDraft(next.policy)
      setSavedAt(Date.now())
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={styles.settingGroup}>
      <h4 className={styles.h4}>{t('Organization policies')}</h4>
      <p className={panels.hint}>
        {t(
          "Rules over every flow in this organization. A flow's own settings say what one flow does; these say what no flow may exceed — they only ever narrow.",
        )}
      </p>
      {!view.enforced && view.refusal && (
        <p className={panels.hint} role="note">
          <b>{t('Read-only.')}</b> {view.refusal}
        </p>
      )}
      {view.enforced && !editable && (
        <p className={panels.hint} role="note">
          {t('Only an administrator can change these. Yours is the view a member gets.')}
        </p>
      )}

      <fieldset className={styles.field} disabled={!editable} aria-label={t('Organization policies')}>
        <SelectField
          label={t('Default facade profile for independent workers')}
          value={draft.defaultFacadeProfileId ?? ''}
          onChange={(v) => patch({ defaultFacadeProfileId: v })}
          readOnly={!editable}
        >
          <option value="">{t('— none: a worker with no profile reaches what is wired to it —')}</option>
          {facades.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
          {draft.defaultFacadeProfileId && !facades.some((p) => p.id === draft.defaultFacadeProfileId) && (
            <option value={draft.defaultFacadeProfileId}>
              {t('{{id}} (no longer exists)', { id: draft.defaultFacadeProfileId })}
            </option>
          )}
        </SelectField>
        <p className={panels.hint}>
          {t(
            'A worker whose block names no facade profile runs behind this one; the run log says "applied by organization policy". A profile on the block wins.',
          )}
        </p>

        <label className={panels.checkField}>
          <input
            type="checkbox"
            checked={draft.requireFacade}
            disabled={!editable}
            onChange={(e) => patch({ requireFacade: e.target.checked })}
          />
          {t('Require a facade profile on every independent worker that reaches MCP')}
        </label>
        <p className={panels.hint}>
          {t(
            'With this on and no default above, a flow whose worker has no profile does not compile: the doctor names the block, and a run refuses to start.',
          )}
        </p>

        <SelectField
          label={t('Permission ceiling')}
          value={draft.maxPermissionMode ?? ''}
          onChange={(v) => patch({ maxPermissionMode: v })}
          readOnly={!editable}
        >
          <option value="">{t('— no ceiling —')}</option>
          {PERMISSION_MODE_ORDER.map((m) => (
            <option key={m} value={m}>
              {t(MODE_LABEL[m])}
            </option>
          ))}
        </SelectField>
        <p className={panels.hint}>
          {t(
            "The most any run may do without asking. A flow asking for more — or naming nothing and getting the deployment's default — is clamped to this, and its log says so once. The coordinator's picker disables the modes above it.",
          )}
        </p>

        <Field
          label={t('Organization budget (USD per month, blank = none)')}
          type="number"
          value={budgetText(draft)}
          readOnly={!editable}
          onChange={(v) => patch({ monthlyBudgetUsd: v === '' ? null : Math.max(0, Number(v)) })}
        />
        <p className={panels.hint}>
          {t(
            "Like a flow's monthly ceiling, summed across every flow of the organization: at or past it, new runs on an API key are refused, and a run in flight is stopped when it crosses. Runs on a subscription or a self-hosted model are told, not stopped — there is no bill for the ceiling to protect.",
          )}
        </p>

        <label className={panels.checkField}>
          <input
            type="checkbox"
            checked={draft.publishRequiresApproval}
            disabled={!editable}
            onChange={(e) => patch({ publishRequiresApproval: e.target.checked })}
          />
          {t("Published endpoints need an administrator's approval")}
        </label>
        <p className={panels.hint}>
          {t(
            "A flow published as an endpoint answers 404 — exactly as if it were not published — until an admin approves its current token on the Input node. Regenerating the token asks for a new approval.",
          )}
        </p>
      </fieldset>

      <p className={panels.hint}>
        <b>{t('Allowed context roots')}</b> —{' '}
        {t('set on the server, not here:')} <code>local.context-roots</code> (<code>LOCAL_CONTEXT_ROOTS</code>).{' '}
        {t('The same allowlist decides what agents may read and which folders a watch trigger may use.')}
      </p>

      {editable && (
        <button className={styles.saveBtn} disabled={busy || !dirty} onClick={() => void save()}>
          {busy ? t('Saving…') : t('Save')}
        </button>
      )}
      {savedAt && !dirty && <p className={panels.hint}>{t('Saved. Applies to the next run.')}</p>}
    </section>
  )
}
