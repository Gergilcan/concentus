import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { ApiNodeData, ApiOperationView, MarketplaceItem } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { CheckboxField, Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import { apiNodeFieldsFrom, KIND_GLYPH } from './marketplace.ts'
import { Modal } from './Modal.tsx'
import { Spinner } from './Spinner.tsx'
import mkt from './marketplace.module.scss'
import styles from './panels.module.scss'

interface Props {
  data: ApiNodeData
  set: (patch: Record<string, unknown>) => void
}

/**
 * The API node: an OpenAPI spec in, an explicit per-operation allowlist out.
 *
 * Reads can be allowed in one click; anything that writes is a deliberate individual tick. The
 * distinction is drawn from the spec's own methods, and the allowlist is what the run's tool
 * server enforces — an operation never ticked simply does not exist as far as the agent knows.
 */
export function ApiInspector({ data, set }: Props) {
  const { t } = useTranslation()
  const [ops, setOps] = useState<ApiOperationView[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // The Marketplace picker: an API somebody published fills this node's fields in one click.
  const [picking, setPicking] = useState(false)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      const preview = await api.previewApiSpec(data.specUrl, data.specInline)
      setOps(preview.operations)
      // The spec's own base URL is a sensible default the user can still override.
      if (!data.baseUrl && preview.baseUrl) set({ baseUrl: preview.baseUrl })
    } catch (e) {
      setError(errMessage(e))
      setOps(null)
    } finally {
      setLoading(false)
    }
  }

  const toggle = (key: string) => {
    const has = data.ops.includes(key)
    set({ ops: has ? data.ops.filter((k) => k !== key) : [...data.ops, key] })
  }

  const allowAllReads = () => {
    if (!ops) return
    const reads = ops.filter((o) => !o.write).map((o) => o.key)
    set({ ops: [...new Set([...data.ops, ...reads])] })
  }

  const endpoint = data.mode === 'endpoint'

  return (
    <>
      <Field label={t('Label')} value={data.label} onChange={(v) => set({ label: v })} />
      <SelectField
        label={
          <span title={t('A spec turns a whole API into typed tools and you tick the ones the agent may call. A single endpoint is for the URL-and-a-key case: a webhook, an internal service, one endpoint of an API whose document you do not have.')}>
            {t('This node calls ⓘ')}
          </span>
        }
        value={data.mode ?? 'spec'}
        onChange={(v) => set({ mode: v })}
      >
        <option value="spec">{t('an API described by an OpenAPI spec')}</option>
        <option value="endpoint">{t('a single endpoint I type here')}</option>
      </SelectField>

      <div className={styles.mcpBtns}>
        <button
          className={styles.linkBtn}
          onClick={() => setPicking(true)}
          title={t('Fills this node from an API published on the Marketplace: its URL, its spec and what it does. Nothing is created; the install is counted.')}
        >
          {t('Use from Marketplace…')}
        </button>
      </div>
      {picking && (
        <MarketplaceApiPicker
          onClose={() => setPicking(false)}
          onPick={(fields) => {
            set(fields)
            setPicking(false)
          }}
        />
      )}

      {endpoint && <EndpointFields data={data} set={set} />}

      {!endpoint && (
      <Field
        label={
          <span title={t("URL of the API's OpenAPI 3.x document (JSON or YAML). If it is not fetchable, paste the document under Fine-tuning instead.")}>
            {t('OpenAPI spec URL ⓘ')}
          </span>
        }
        placeholder={t('https://api.example.com/openapi.json')}
        value={data.specUrl}
        onChange={(v) => set({ specUrl: v })}
      />
      )}
      <Field
        label={
          <span title={t('Credential id from Resources → Credentials. Sent as Authorization: Bearer unless a different header is named under Fine-tuning. The agent never sees the token.')}>
            {t('Credential id ⓘ')}
          </span>
        }
        placeholder={t('from Resources → Credentials')}
        value={data.credentialId ?? ''}
        onChange={(v) => set({ credentialId: v })}
      />
      <FineTuning>
        {!endpoint && (
          <TextArea
            label={t('Paste the spec (when the URL is not fetchable)')}
            rows={3}
            placeholder={t('{"openapi": "3.0.0", …}')}
            value={data.specInline ?? ''}
            onChange={(v) => set({ specInline: v })}
          />
        )}
        {!endpoint && (
          <Field
            label={
              <span title={t("Overrides the spec's own servers[0].url — for sandboxes or self-hosted instances.")}>
                {t('Base URL (optional) ⓘ')}
              </span>
            }
            placeholder={t('filled from the spec after loading')}
            value={data.baseUrl ?? ''}
            onChange={(v) => set({ baseUrl: v })}
          />
        )}
        <Field
          label={t('Send token in (blank = Authorization: Bearer)')}
          placeholder={t('X-Api-Key')}
          value={data.authHeader ?? ''}
          onChange={(v) => set({ authHeader: v })}
        />
      </FineTuning>

      {endpoint ? null : (
      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => void load()} disabled={loading}>
          {loading ? t('Loading…') : t('Load operations')}
        </button>
        {ops && (
          <button className={styles.linkBtn} onClick={allowAllReads}>
            {t('Allow all reads')}
          </button>
        )}
      </div>
      )}
      {error && <p className={styles.hint}>{error}</p>}

      {ops && (
        <div className={styles.opList}>
          {ops.map((o) => (
            <label key={o.key} className={styles.opRow} title={o.description || o.key}>
              <input
                type="checkbox"
                checked={data.ops.includes(o.key)}
                onChange={() => toggle(o.key)}
              />
              <code className={o.write ? styles.opWrite : styles.opRead}>{o.method}</code>
              <span className={styles.opPath}>{o.path}</span>
            </label>
          ))}
        </div>
      )}
      {!endpoint && !ops && data.ops.length > 0 && (
        <p className={styles.hint}>
          {t('{{n}} operation(s) currently allowed. Load the spec to review them.', { n: data.ops.length })}
        </p>
      )}
    </>
  )
}

/**
 * The APIs published on the Marketplace, one row each, and the one action: fill this node from
 * it. The install is recorded so the item's count is honest, but a count that could not be
 * recorded never keeps the fields from being filled.
 */
function MarketplaceApiPicker({
  onClose,
  onPick,
}: {
  onClose: () => void
  onPick: (fields: Record<string, unknown>) => void
}) {
  const { t } = useTranslation()
  const [items, setItems] = useState<MarketplaceItem[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    api
      .listMarketplaceItems({ kind: 'api' })
      .then((list) => alive && setItems(list.items.filter((i) => i.kind === 'api' && i.status === 'published')))
      .catch((e) => alive && setError(errMessage(e)))
    return () => {
      alive = false
    }
  }, [])

  const use = (item: MarketplaceItem) => {
    onPick(apiNodeFieldsFrom(item.payload))
    void api.installMarketplaceItem(item.id).catch(() => {})
  }

  return (
    <Modal title={t('Use from Marketplace')} onClose={onClose}>
      {items === null && !error && <Spinner />}
      {error && <p className={styles.hint}>{error}</p>}
      {items && items.length === 0 && <p className={styles.hint}>{t('No API has been published yet.')}</p>}
      {items && items.length > 0 && (
        <div className={mkt.pickList}>
          {items.map((item) => (
            <div key={item.id} className={mkt.pickRow}>
              <span aria-hidden="true">{item.icon ?? KIND_GLYPH.api}</span>
              <span className={mkt.summary} title={item.summary}>
                <strong>{item.name}</strong> · {item.summary}
              </span>
              <button
                className={styles.linkBtn}
                onClick={() => use(item)}
                title={t('Fills the URL, the spec and the description from this item.')}
              >
                {t('Use')}
              </button>
            </div>
          ))}
        </div>
      )}
    </Modal>
  )
}

/**
 * The single-endpoint fields.
 *
 * <p>No allowlist here, and none is missing: with a spec the node holds a whole API and ticking
 * operations is what keeps the agent inside it, while here the URL IS the allowlist — somebody
 * typed exactly one call on exactly one node.
 */
function EndpointFields({ data, set }: Props) {
  const { t } = useTranslation()
  return (
    <>
      <SelectField
        label={t('Method')}
        value={data.method ?? 'GET'}
        onChange={(v) => set({ method: v })}
      >
        {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => (
          <option key={m} value={m}>
            {m}
          </option>
        ))}
      </SelectField>
      <Field
        label={
          <span title={t("The full URL. Anything in {braces} becomes an argument the agent fills in, encoded on the way out — https://api.example.com/orgs/{org}/repos gives the agent an 'org' argument.")}>
            {t('URL ⓘ')}
          </span>
        }
        placeholder={t('https://api.example.com/things/{id}')}
        value={data.url ?? ''}
        onChange={(v) => set({ url: v })}
      />
      <TextArea
        label={
          <span title={t('With no specification to read, this sentence is the only thing telling the model when to call this endpoint and what it does. Say what it acts on and what comes back.')}>
            {t('What this endpoint does ⓘ')}
          </span>
        }
        rows={2}
        placeholder={t('Posts a message to the ops channel. Returns the message id.')}
        value={data.description ?? ''}
        onChange={(v) => set({ description: v })}
      />
      <CheckboxField
        label={t('The agent may send a JSON body')}
        checked={data.sendsBody ?? false}
        onChange={(v) => set({ sendsBody: v })}
      />
    </>
  )
}
