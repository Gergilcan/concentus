import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { Credential } from '../api/types.ts'
import { SelectField } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  label: string
  value: string
  onChange: (credentialId: string) => void
  /** What the credential is for, so the hint can name it. */
  what?: string
}

/**
 * Picks a stored credential for a node.
 *
 * Shared by the MCP, repository, SQL and mail nodes so they cannot drift apart on the one thing
 * that matters: the node stores an **id**, never the secret. Every flow save snapshots the flow
 * JSON into version history and duplicating a flow copies its nodes, so a value here would fan out
 * into every revision and every copy.
 *
 * A referenced credential that no longer exists is called out rather than silently reading as
 * "none configured" — the two look identical at run time and need different fixes.
 */
export function CredentialField({ label, value, onChange, what = 'this connection' }: Props) {
  const [credentials, setCredentials] = useState<Credential[]>([])

  useEffect(() => {
    let alive = true
    void api
      .listCredentials()
      .then((c) => alive && setCredentials(c))
      .catch(() => alive && setCredentials([]))
    return () => {
      alive = false
    }
  }, [])

  const missing = value !== '' && !credentials.some((c) => c.id === value)

  return (
    <>
      <SelectField label={label} value={value} onChange={onChange}>
        <option value="">— none —</option>
        {credentials.map((c) => (
          <option key={c.id} value={c.id}>
            {c.label} ({c.hint ?? '••••'})
          </option>
        ))}
      </SelectField>
      <p className={styles.hint}>
        Stored under <b>Resources → Credentials</b>, encrypted, and never shown again. This node
        keeps only its id, so the flow can be exported, duplicated or rolled back without carrying
        the secret for {what}.
      </p>
      {missing && (
        <p className={styles.hint}>
          <b>The selected credential no longer exists.</b> Pick another one.
        </p>
      )}
    </>
  )
}
