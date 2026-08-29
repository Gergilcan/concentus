import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { McpDef } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { CredentialField } from './CredentialField.tsx'
import { Field } from './fields.tsx'
import type { CatalogSetup } from './McpCatalog.tsx'
import { Modal } from './Modal.tsx'
import { RuntimeNotice } from './RuntimeNotice.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * Setting up a catalogue server that cannot just be added.
 *
 * It opens from the catalogue, for the entries that need something before they can run — never as
 * a way to invent a server from scratch. That is what the list below and each server's own JSON
 * box are for, and a dialog asking someone to type a transport and a URL was answering a question
 * the catalogue had already answered.
 *
 * So it asks only what is genuinely left:
 *
 * - **Can this machine launch it?** A local server is a command, and the command comes from a
 *   runtime — npx from npm, uvx from uv. If it is missing, the install button is right there and
 *   the exact command it runs is shown next to it.
 * - **What does it need to know?** The environment variables the server reads, with the secret
 *   ones going through the credential store rather than into the definition.
 */

/** One environment variable the server expects. */
interface EnvRow {
  key: string
  /** The literal value, or the credential id when `secret`. */
  value: string
  /** Stored as `credential:<id>` and resolved when the run starts, so no secret lands here. */
  secret: boolean
}

const CREDENTIAL_PREFIX = 'credential:'

function rowsFrom(env: Record<string, string> | undefined): EnvRow[] {
  return Object.entries(env ?? {}).map(([key, value]) =>
    value.startsWith(CREDENTIAL_PREFIX)
      ? { key, value: value.slice(CREDENTIAL_PREFIX.length), secret: true }
      : { key, value, secret: false },
  )
}

function envFrom(rows: EnvRow[]): Record<string, string> {
  const env: Record<string, string> = {}
  for (const row of rows) {
    const key = row.key.trim()
    if (!key) continue
    env[key] = row.secret && row.value ? `${CREDENTIAL_PREFIX}${row.value}` : row.value
  }
  return env
}

type Step = 'requirements' | 'values'

export function AddMcpServerModal({
  entry,
  onClose,
  onSaved,
}: {
  /** The catalogue entry being set up. There is no blank state: this always configures one. */
  entry: CatalogSetup
  onClose: () => void
  onSaved: (saved: McpDef) => void
}) {
  const { t } = useTranslation()
  const local = entry.auth === 'stdio'
  // A local server has both questions; a remote one launches nothing, so it has only the second.
  const steps: Step[] = local ? ['requirements', 'values'] : ['values']
  const [step, setStep] = useState<Step>(steps[0])
  const [rows, setRows] = useState<EnvRow[]>(rowsFrom(entry.env))
  const [credentialId, setCredentialId] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const index = Math.max(0, steps.indexOf(step))
  const last = index === steps.length - 1

  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      const def = local
        ? {
            name: entry.name,
            url: '',
            credentialId: '',
            authHeader: '',
            command: entry.command,
            args: entry.args,
            env: envFrom(rows),
          }
        : {
            name: entry.name,
            url: entry.url ?? '',
            credentialId,
            authHeader: entry.authHeader ?? '',
          }
      onSaved(await api.saveMcpDef(def as McpDef))
      onClose()
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title={t('Set up {{name}}', { name: entry.name })} onClose={onClose}>
      <p className={panels.hint}>{t(entry.note)}</p>
      {steps.length > 1 && (
        <p className={panels.hint}>
          {t('Step {{step}} of {{total}}', { step: index + 1, total: steps.length })} —{' '}
          {step === 'requirements'
            ? t('what this machine needs to launch it')
            : t('what the server needs to know')}
        </p>
      )}

      {step === 'requirements' && (
        <>
          <p className={panels.hint}>
            {t('It runs on this machine as')}{' '}
            <code>{[entry.command, ...(entry.args ?? [])].join(' ')}</code>
          </p>
          {/* The install button lives here: one click, the exact command shown before it runs. */}
          <RuntimeNotice command={entry.command ?? ''} />
          <p className={panels.hint}>
            {t(
              'You can continue either way — this never blocks adding the server. It just means the first run would fail to start it.',
            )}
          </p>
        </>
      )}

      {step === 'values' && local && (
        <>
          <p className={panels.hint}>
            {t(
              'Environment variables this server reads. Mark the secret ones — those are stored in',
            )}
            <b> {t('Resources → Credentials')}</b>{' '}
            {t('and referenced by id, so the definition never holds a token.')}
          </p>
          {rows.length === 0 && (
            <p className={panels.hint}>{t('This server declares no environment variables.')}</p>
          )}
          {rows.map((row, i) => {
            const setValue = (v: string) =>
              setRows((prev) => prev.map((r, j) => (j === i ? { ...r, value: v } : r)))
            return (
              <div key={i}>
                {row.secret ? (
                  <CredentialField label={row.key} value={row.value} onChange={setValue} what={t('this MCP server')} />
                ) : (
                  <Field label={row.key} value={row.value} onChange={setValue} />
                )}
                {/* Under its field, and short: the variable's name is already the label above, and
                    repeating it made every row say the same word twice in a row. The accessible
                    name still carries it, because several rows each have one of these. */}
                <label className={panels.hint}>
                  <input
                    type="checkbox"
                    aria-label={t('{{key}} — store as a credential', { key: row.key })}
                    checked={row.secret}
                    onChange={(e) =>
                      setRows((prev) =>
                        // The value is cleared when the kind changes: a literal token left behind
                        // after ticking "secret" would be saved as a credential id, and a credential
                        // id left behind after unticking would be sent to the server verbatim.
                        prev.map((r, j) => (j === i ? { ...r, secret: e.target.checked, value: '' } : r)),
                      )
                    }
                  />{' '}
                  {t('Store as a credential')}
                </label>
              </div>
            )
          })}
        </>
      )}

      {step === 'values' && !local && (
        <>
          <CredentialField
            label={t('Access token')}
            value={credentialId}
            onChange={setCredentialId}
            what={t('this MCP server')}
          />
          <p className={panels.hint}>
            {entry.authHeader
              ? t('Sent in the {{header}} header, as this server expects.', {
                  header: entry.authHeader,
                })
              : t('Sent as an Authorization: Bearer header.')}
          </p>
        </>
      )}

      {error && <p className={styles.errText}>{error}</p>}

      <div className={styles.crudActions}>
        {index > 0 && (
          <button className={styles.newBtn} disabled={saving} onClick={() => setStep(steps[index - 1])}>
            {t('Back')}
          </button>
        )}
        {last ? (
          <button className={styles.saveBtn} disabled={saving} onClick={() => void save()}>
            {saving ? t('Adding…') : t('Add {{name}}', { name: entry.name })}
          </button>
        ) : (
          <button className={styles.saveBtn} onClick={() => setStep(steps[index + 1])}>
            {t('Next')}
          </button>
        )}
      </div>
    </Modal>
  )
}
