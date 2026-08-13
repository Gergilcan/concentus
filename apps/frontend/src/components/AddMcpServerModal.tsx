import { useState } from 'react'
import { api } from '../api/client.ts'
import type { McpDef } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { CredentialField } from './CredentialField.tsx'
import { Field, SelectField, TextArea } from './fields.tsx'
import { Modal } from './Modal.tsx'
import { RuntimeNotice } from './RuntimeNotice.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * Adding an MCP server as a few answered questions instead of a form to be decoded.
 *
 * The raw form still exists and still works — this is the path for the case it handles badly: a
 * local (stdio) server, whose README gives you a command, a list of arguments and a handful of
 * environment variables, one of which is a secret. Filling that in a JSON editor means knowing
 * that `credential:<id>` is a thing, and finding out that `uvx` is not installed only when a run
 * fails hours later.
 *
 * So the wizard asks in the order the answers matter: what the server is, whether this machine
 * can launch it (with the install button right there), and then each value it needs — with the
 * secret ones going through the credential store rather than into the definition.
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

type Step = 'server' | 'requirements' | 'values'

export function AddMcpServerModal({
  initial,
  onClose,
  onSaved,
}: {
  /** Pre-filled from a catalogue entry, or empty for a server the user describes themselves. */
  initial?: Partial<McpDef>
  onClose: () => void
  onSaved: (saved: McpDef) => void
}) {
  const [step, setStep] = useState<Step>('server')
  const [name, setName] = useState(initial?.name ?? '')
  const [stdio, setStdio] = useState((initial?.command ?? '') !== '')
  const [url, setUrl] = useState(initial?.url ?? '')
  const [command, setCommand] = useState(initial?.command ?? '')
  const [argsText, setArgsText] = useState((initial?.args ?? []).join('\n'))
  const [rows, setRows] = useState<EnvRow[]>(rowsFrom(initial?.env))
  const [credentialId, setCredentialId] = useState(initial?.credentialId ?? '')
  const [authHeader, setAuthHeader] = useState(initial?.authHeader ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Only the identity of the server is required. Everything after it can legitimately be empty —
  // a server with no env, a command with no arguments — and demanding it would be theatre.
  const describedEnough = name.trim() !== '' && (stdio ? command.trim() !== '' : url.trim() !== '')
  // stdio servers get the requirements step; a remote one launches no process, so it has none.
  const steps: Step[] = stdio ? ['server', 'requirements', 'values'] : ['server', 'values']
  const index = Math.max(0, steps.indexOf(step))

  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      const def = stdio
        ? {
            name: name.trim(),
            url: '',
            credentialId: '',
            authHeader: '',
            command: command.trim(),
            args: argsText.split('\n').map((s) => s.trim()).filter(Boolean),
            env: envFrom(rows),
          }
        : {
            name: name.trim(),
            url: url.trim(),
            credentialId,
            authHeader,
          }
      onSaved(await api.saveMcpDef({ ...initial, ...def } as McpDef))
      onClose()
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title={initial?.name ? `Add ${initial.name}` : 'Add an MCP server'} onClose={onClose}>
      <p className={panels.hint}>
        Step {index + 1} of {steps.length}
        {step === 'server' && ' — what the server is'}
        {step === 'requirements' && ' — what this machine needs to launch it'}
        {step === 'values' && ' — the values it needs'}
      </p>

      {step === 'server' && (
        <>
          <Field label="Name" placeholder="google-ads" value={name} onChange={setName} />
          <SelectField
            label="Transport"
            value={stdio ? 'stdio' : 'http'}
            onChange={(v) => setStdio(v === 'stdio')}
          >
            <option value="http">Remote server (URL)</option>
            <option value="stdio">Command (stdio) — launched on this machine</option>
          </SelectField>
          {stdio ? (
            <>
              <Field label="Command" placeholder="uvx" value={command} onChange={setCommand} />
              <TextArea
                label="Arguments (one per line)"
                rows={3}
                placeholder={'-y\n@googleads/google-ads-mcp'}
                value={argsText}
                onChange={setArgsText}
              />
            </>
          ) : (
            <Field
              label="URL"
              placeholder="https://mcp.linear.app/mcp"
              value={url}
              onChange={setUrl}
            />
          )}
        </>
      )}

      {step === 'requirements' && (
        <>
          <RuntimeNotice command={command} />
          <p className={panels.hint}>
            You can continue either way — this never blocks adding the server. It just means the
            first run would fail to start it.
          </p>
        </>
      )}

      {step === 'values' && stdio && (
        <>
          <p className={panels.hint}>
            Environment variables this server reads. Mark the secret ones — those are stored in
            <b> Resources → Credentials</b> and referenced by id, so the definition never holds a
            token.
          </p>
          {rows.length === 0 && (
            <p className={panels.hint}>This server declares no environment variables.</p>
          )}
          {rows.map((row, i) => (
            <div key={i} className={styles.crudForm}>
              <Field
                label="Variable"
                placeholder="GOOGLE_ADS_DEVELOPER_TOKEN"
                value={row.key}
                onChange={(v) =>
                  setRows((prev) => prev.map((r, j) => (j === i ? { ...r, key: v } : r)))
                }
              />
              <label className={panels.hint}>
                <input
                  type="checkbox"
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
                This is a secret
              </label>
              {row.secret ? (
                <CredentialField
                  label="Credential"
                  value={row.value}
                  onChange={(v) => setRows((prev) => prev.map((r, j) => (j === i ? { ...r, value: v } : r)))}
                  what="this MCP server"
                />
              ) : (
                <Field
                  label="Value"
                  value={row.value}
                  onChange={(v) => setRows((prev) => prev.map((r, j) => (j === i ? { ...r, value: v } : r)))}
                />
              )}
              <button
                className={styles.newBtn}
                onClick={() => setRows((prev) => prev.filter((_, j) => j !== i))}
              >
                Remove {row.key || 'this variable'}
              </button>
            </div>
          ))}
          <button
            className={styles.newBtn}
            onClick={() => setRows((prev) => [...prev, { key: '', value: '', secret: false }])}
          >
            + Add a variable
          </button>
        </>
      )}

      {step === 'values' && !stdio && (
        <>
          <CredentialField
            label="Access token (optional)"
            value={credentialId}
            onChange={setCredentialId}
            what="this MCP server"
          />
          <SelectField label="Send token in" value={authHeader} onChange={setAuthHeader}>
            <option value="">Authorization: Bearer</option>
            <option value="PRIVATE-TOKEN">PRIVATE-TOKEN</option>
          </SelectField>
          <p className={panels.hint}>
            Servers that sign in with OAuth need neither: add it, then press “Sign in to this
            server” on the MCP node.
          </p>
        </>
      )}

      {error && <p className={styles.errText}>{error}</p>}

      <div className={styles.crudActions}>
        {index > 0 && (
          <button className={styles.newBtn} disabled={saving} onClick={() => setStep(steps[index - 1])}>
            Back
          </button>
        )}
        {index < steps.length - 1 ? (
          <button
            className={styles.saveBtn}
            disabled={!describedEnough}
            title={describedEnough ? undefined : 'A name and a URL or command first'}
            onClick={() => setStep(steps[index + 1])}
          >
            Next
          </button>
        ) : (
          <button className={styles.saveBtn} disabled={!describedEnough || saving} onClick={() => void save()}>
            {saving ? 'Saving…' : 'Add server'}
          </button>
        )}
      </div>
    </Modal>
  )
}
