import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { MarketplaceItem, MarketplaceKind, MarketplaceScope } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { knownGroups, useGroups } from './groups.ts'
import { KIND_LABEL, KINDS } from './marketplace.ts'
import { Modal } from './Modal.tsx'
import fx from './flows.module.scss'
import styles from './marketplace.module.scss'

/** What a "Publish…" action on a resource already knows, so the form opens filled in. */
export interface PublishPrefill {
  kind: MarketplaceKind
  resourceId: string
  name?: string
  summary?: string
  description?: string
}

interface Props {
  onClose: () => void
  /** The item as the server returned it, and the credential slots it stripped on the way. */
  onPublished: (item: MarketplaceItem, stripped: string[]) => void
  pushError: (m: string) => void
  prefill?: PublishPrefill
  /** Editing an existing item: the payload is the JSON box, the kind is fixed, PUT instead of POST. */
  editing?: MarketplaceItem
}

type Source = 'resource' | 'json'

interface ResourceOption {
  id: string
  label: string
  summary?: string
}

/** The organization's own resources of one kind, as the select lists them. An API has no resource to publish from. */
async function loadResources(kind: MarketplaceKind): Promise<ResourceOption[]> {
  switch (kind) {
    case 'mcp':
      return (await api.listMcpDefs()).flatMap((d) => (d.id ? [{ id: d.id, label: d.name }] : []))
    case 'agent':
      return (await api.listAgents()).flatMap((a) => (a.id ? [{ id: a.id, label: a.name, summary: a.description }] : []))
    case 'facade':
      return (await api.listFacadeProfiles()).flatMap((p) => (p.id ? [{ id: p.id, label: p.name, summary: p.description }] : []))
    case 'skill':
      return (await api.listSkills()).map((s) => ({ id: s.id, label: s.name, summary: s.description }))
    case 'plugin':
      return (await api.listPlugins()).plugins.map((p) => ({ id: p.id, label: p.id }))
    case 'flow':
      return (await api.listFlows()).flatMap((f) => (f.id ? [{ id: f.id, label: f.name }] : []))
    case 'api':
      return []
  }
}

/**
 * Publishing: a kind, then either a resource this organization already has (the common path —
 * "publish my Linear MCP") or a pasted JSON payload; the words people read on the card; and the
 * scope, where there is more than one organization to choose between.
 */
export function MarketplacePublishDialog({ onClose, onPublished, pushError, prefill, editing }: Props) {
  const { t } = useTranslation()
  const [kind, setKind] = useState<MarketplaceKind>(editing?.kind ?? prefill?.kind ?? 'mcp')
  const [source, setSource] = useState<Source>('resource')
  const [resources, setResources] = useState<ResourceOption[] | null>(null)
  const [resourceId, setResourceId] = useState(prefill?.resourceId ?? '')
  const [payloadText, setPayloadText] = useState(editing ? JSON.stringify(editing.payload, null, 2) : '')
  const [name, setName] = useState(editing?.name ?? prefill?.name ?? '')
  const [summary, setSummary] = useState(editing?.summary ?? prefill?.summary ?? '')
  const [description, setDescription] = useState(editing?.description ?? prefill?.description ?? '')
  const [tags, setTags] = useState(editing?.tags.join(', ') ?? '')
  const [icon, setIcon] = useState(editing?.icon ?? '')
  const [scope, setScope] = useState<MarketplaceScope>(editing?.scope ?? 'organization')
  // With scope "group": which one. The groups the caller may publish to are the shared answer.
  const [groupId, setGroupId] = useState(editing?.groupId ?? '')
  const groups = useGroups({ all: true })
  const groupOptions = knownGroups(groups)
  // How many organizations there are decides whether scope is a choice at all.
  const [organizations, setOrganizations] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Read through a ref so the resource loader below depends on the kind alone.
  const pushErrorRef = useRef(pushError)
  pushErrorRef.current = pushError

  // An edit rewrites the stored payload; an API item has no resource to read one from.
  const effectiveSource: Source = editing || kind === 'api' ? 'json' : source

  useEffect(() => {
    let alive = true
    api
      .marketplaceStatus()
      .then((s) => alive && setOrganizations(s.organizations))
      .catch(() => alive && setOrganizations(1))
    return () => {
      alive = false
    }
  }, [])

  useEffect(() => {
    if (effectiveSource !== 'resource') return
    let alive = true
    setResources(null)
    loadResources(kind)
      .then((found) => alive && setResources(found))
      .catch((e) => {
        if (!alive) return
        setResources([])
        pushErrorRef.current(errMessage(e))
      })
    return () => {
      alive = false
    }
  }, [kind, effectiveSource])

  const pickResource = (id: string) => {
    setResourceId(id)
    const picked = resources?.find((r) => r.id === id)
    if (!picked) return
    // The resource's own words, unless the person already typed some.
    if (!name.trim()) setName(picked.label)
    if (!summary.trim() && picked.summary) setSummary(picked.summary)
  }

  const parsePayload = (): Record<string, unknown> => {
    let parsed: unknown
    try {
      parsed = JSON.parse(payloadText)
    } catch {
      throw new Error(t('The payload is not valid JSON.'))
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(t('The payload must be a JSON object.'))
    return parsed as Record<string, unknown>
  }

  const submit = async () => {
    setError(null)
    if (!name.trim() || !summary.trim()) {
      setError(t('A name and a one-line summary are required.'))
      return
    }
    if (effectiveSource === 'resource' && !resourceId) {
      setError(t('Pick the resource to publish.'))
      return
    }
    if (scope === 'group' && !groupId) {
      setError(t('Pick the group to publish to.'))
      return
    }
    const common = {
      name: name.trim(),
      summary: summary.trim(),
      description: description.trim() || undefined,
      tags: tags.split(',').map((s) => s.trim()).filter(Boolean),
      icon: icon.trim() || undefined,
      // A group is a choice whatever the deployment's size. Otherwise, one organization: nothing
      // to keep an item from, so everything is global.
      scope: scope === 'group' ? scope : (organizations ?? 1) > 1 ? scope : ('global' as MarketplaceScope),
      ...(scope === 'group' ? { groupId } : {}),
    }
    setBusy(true)
    try {
      if (editing) {
        onPublished(await api.updateMarketplaceItem(editing.id, { kind, ...common, payload: parsePayload() }), [])
      } else if (effectiveSource === 'resource') {
        const saved = await api.publishMarketplaceFrom({ kind, resourceId, ...common })
        onPublished(saved, saved.stripped ?? [])
      } else {
        onPublished(await api.publishMarketplaceItem({ kind, ...common, payload: parsePayload() }), [])
      }
    } catch (e) {
      // A payload the person typed wrong is theirs to fix here; a refusal from the server is a
      // toast like every other.
      if (e instanceof Error && !('status' in e)) setError(e.message)
      else pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={editing ? t('Edit {{name}}', { name: editing.name }) : t('Publish to the Marketplace')} onClose={onClose}>
      <label className={styles.formField}>
        <span>{t('Kind')}</span>
        <select value={kind} disabled={!!editing || !!prefill} onChange={(e) => setKind(e.target.value as MarketplaceKind)}>
          {KINDS.map((k) => (
            <option key={k} value={k}>
              {t(KIND_LABEL[k])}
            </option>
          ))}
        </select>
      </label>

      {!editing && kind !== 'api' && (
        <div className={styles.sourceRow} role="radiogroup" aria-label={t('Publish from')}>
          <label title={t('The common path: the server reads the definition from the resource itself and strips any credential.')}>
            <input type="radio" name="mkt-source" checked={source === 'resource'} onChange={() => setSource('resource')} />
            {t('From an existing resource')}
          </label>
          <label title={t('The definition as JSON — the same shape the resource has, without its id or credentials.')}>
            <input type="radio" name="mkt-source" checked={source === 'json'} onChange={() => setSource('json')} />
            {t('Paste JSON')}
          </label>
        </div>
      )}

      {effectiveSource === 'resource' ? (
        <label className={styles.formField}>
          <span>{t('Resource')}</span>
          <select value={resourceId} onChange={(e) => pickResource(e.target.value)} disabled={!!prefill}>
            <option value="">{resources === null ? t('Loading…') : resources.length === 0 ? t('(none of this kind yet)') : t('Pick one…')}</option>
            {(resources ?? []).map((r) => (
              <option key={r.id} value={r.id}>
                {r.label}
              </option>
            ))}
          </select>
        </label>
      ) : (
        <label className={styles.formField}>
          <span>{t('Payload (JSON)')}</span>
          <textarea
            rows={6}
            value={payloadText}
            placeholder={kind === 'api' ? '{"name": "…", "baseUrl": "https://…", "specUrl": "https://…/openapi.json", "description": "…"}' : '{ … }'}
            onChange={(e) => setPayloadText(e.target.value)}
            spellCheck={false}
          />
        </label>
      )}

      <label className={styles.formField}>
        <span>{t('Name')}</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder={t('Linear')} />
      </label>
      <label className={styles.formField}>
        <span>{t('Summary (one line)')}</span>
        <input value={summary} onChange={(e) => setSummary(e.target.value)} placeholder={t('Issues, projects and cycles')} />
      </label>
      <label className={styles.formField}>
        <span>{t('Description (markdown, optional)')}</span>
        <textarea rows={4} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <div className={styles.formRow}>
        <label className={styles.formField}>
          <span>{t('Tags (comma-separated)')}</span>
          <input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="planning, issues" />
        </label>
        <label className={styles.formField} title={t('One emoji, shown on the card instead of the kind glyph.')}>
          <span>{t('Icon')}</span>
          <input value={icon} maxLength={4} onChange={(e) => setIcon(e.target.value)} placeholder="⚙" />
        </label>
      </div>

      {/* Scope is a choice past one organization, or as soon as there is a group to publish to. */}
      {((organizations ?? 1) > 1 || groupOptions.length > 0) && (
        <>
          <label className={styles.formField}>
            <span>{t('Scope')}</span>
            <select value={scope} onChange={(e) => setScope(e.target.value as MarketplaceScope)}>
              <option value="organization">{t('This organization')}</option>
              {(organizations ?? 1) > 1 && <option value="global">{t('Global — every organization')}</option>}
              {groupOptions.length > 0 && <option value="group">{t('Group…')}</option>}
            </select>
          </label>
          {scope === 'group' && (
            <label className={styles.formField} title={t('Its members and the administrators see it; it is published at once, like an organization item.')}>
              <span>{t('Which group')}</span>
              <select value={groupId} onChange={(e) => setGroupId(e.target.value)}>
                <option value="">{t('Pick one…')}</option>
                {groupOptions.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          {(organizations ?? 1) > 1 && (
            <p className={styles.formHint}>{t('Global items are reviewed before everyone sees them')}</p>
          )}
        </>
      )}

      {error && <p className={fx.describeError}>{error}</p>}

      <div className={fx.modalActions}>
        <button className={fx.ghost} onClick={onClose} disabled={busy}>
          {t('Cancel')}
        </button>
        <button className={fx.primary} onClick={() => void submit()} disabled={busy}>
          {busy ? t('Publishing…') : editing ? t('Save') : t('Publish')}
        </button>
      </div>
    </Modal>
  )
}
