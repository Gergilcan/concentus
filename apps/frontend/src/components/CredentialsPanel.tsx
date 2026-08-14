import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client.ts'
import type { Credential, CredentialStatus } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Field, SelectField } from './fields.tsx'
import styles from './resources.module.scss'

const KINDS = [
  { value: 'mail-password', label: 'Mailbox password (IMAP)' },
  { value: 'api-token', label: 'API token' },
]

/**
 * Credentials entered in the app, encrypted before storage.
 *
 * The value field is **write-only**: nothing here ever displays a stored secret, because the API
 * has none to give — a credential comes back as a label, a kind and a masked hint. Editing shows
 * an empty value box, and leaving it empty keeps the stored secret untouched. That last part is
 * what stops "rename it and save" from overwriting the password with a mask.
 */
export function CredentialsPanel({ pushError }: { pushError: (m: string) => void }) {
  const [status, setStatus] = useState<CredentialStatus | null>(null)
  const [items, setItems] = useState<Credential[]>([])
  const [editing, setEditing] = useState<Credential | null>(null)
  const [creating, setCreating] = useState(false)
  const [label, setLabel] = useState('')
  const [kind, setKind] = useState(KINDS[0].value)
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setStatus(await api.credentialStatus())
      setItems(await api.listCredentials())
    } catch (e) {
      pushError(errMessage(e))
    }
  }, [pushError])

  useEffect(() => {
    void load()
  }, [load])

  const startCreate = () => {
    setCreating(true)
    setEditing(null)
    setLabel('')
    setKind(KINDS[0].value)
    setValue('')
  }

  const startEdit = (c: Credential) => {
    setEditing(c)
    setCreating(false)
    setLabel(c.label)
    setKind(c.kind)
    // Empty on purpose: there is nothing to prefill it with, and an empty box is an honest
    // representation of "the app cannot read this".
    setValue('')
  }

  const cancel = () => {
    setCreating(false)
    setEditing(null)
    setValue('')
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      if (editing) {
        await api.updateCredential(editing.id, label, value)
      } else {
        await api.createCredential(label, kind, value)
      }
      cancel()
      await load()
    } catch (err) {
      pushError(errMessage(err))
    } finally {
      setBusy(false)
      setValue('')
    }
  }

  const remove = async (c: Credential) => {
    if (!confirm(`Delete the credential "${c.label}"? Any flow using it will stop working.`)) return
    try {
      await api.deleteCredential(c.id)
      await load()
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  if (status && !status.available) {
    return (
      <div className={styles.muted}>
        <p>
          <b>Credential storage is disabled.</b> {status.hint}
        </p>
        <p>
          Credentials are encrypted with AES-256-GCM before being written. Without a master key
          there is nowhere safe to put them, so nothing is stored rather than being kept in plain
          text.
        </p>
      </div>
    )
  }

  return (
    <div className={styles.crud}>
      <div className={styles.crudList}>
        <div className={styles.crudListHead}>
          <span>Credentials</span>
          <button className={styles.newBtn} onClick={startCreate}>+ New</button>
        </div>
        {items.length === 0 && <p className={styles.muted}>None yet.</p>}
        {items.map((c) => (
          <div
            key={c.id}
            className={editing?.id === c.id ? `${styles.crudItem} ${styles.active}` : styles.crudItem}
            onClick={() => startEdit(c)}
          >
            {/* Grouped: the row is a flex line, so two loose children would sit side by side
                instead of stacking — the label and its metadata each squeezed into half a column. */}
            <div className={styles.crudItemStack}>
              <div>{c.label}</div>
              <div className={styles.muted}>
                {c.kind} · {c.hint ?? '••••'}
                {c.lastUsedAt ? ` · used ${new Date(c.lastUsedAt).toLocaleDateString()}` : ' · never used'}
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className={styles.crudForm}>
        {!creating && !editing ? (
          <p className={styles.muted}>Select a credential, or create one.</p>
        ) : (
          <form onSubmit={submit}>
            <Field label="Label" value={label} onChange={setLabel} placeholder="Buzón presupuestos" />

            {creating && (
              <SelectField label="Kind" value={kind} onChange={setKind}>
                {KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </SelectField>
            )}

            <label className={styles.field}>
              <span>{editing ? 'New value (leave blank to keep the current one)' : 'Value'}</span>
              <input
                type="password"
                autoComplete="new-password"
                value={value}
                placeholder={editing ? '••••••••' : ''}
                onChange={(e) => setValue(e.target.value)}
              />
            </label>

            <p className={styles.hint}>
              Encrypted before it is written, and never sent back — not to this screen, not to any
              API, not to an administrator. To change it, type a new one.
            </p>
            <p className={styles.hint}>
              This protects a leaked database backup or a database-only compromise. It does not
              protect against someone who compromises the server itself, since the key has to be
              readable here to be usable.
            </p>

            <div className={styles.crudActions}>
              <button type="submit" className={styles.saveBtn} disabled={busy || !label || (creating && !value)}>
                {busy ? 'Saving…' : 'Save'}
              </button>
              <button type="button" className={styles.newBtn} onClick={cancel}>
                Cancel
              </button>
              {editing && (
                <button type="button" className={styles.delBtn} onClick={() => void remove(editing)}>
                  Delete
                </button>
              )}
            </div>
          </form>
        )}
      </div>
    </div>
  )
}
