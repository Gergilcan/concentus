import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
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
export function CredentialField({ label, value, onChange, what }: Props) {
  const { t } = useTranslation()
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

  const selected = credentials.find((c) => c.id === value)
  const missing = value !== '' && selected === undefined
  // Locked and missing look identical at run time and need different fixes: a locked one is the
  // right credential waiting for its value, and picking another would leave it behind.
  const locked = selected?.locked === true

  return (
    <>
      <SelectField label={label} value={value} onChange={onChange}>
        <option value="">{t('— none —')}</option>
        {credentials.map((c) => (
          <option key={c.id} value={c.id}>
            {c.label} ({c.locked ? t('locked') : (c.hint ?? '••••')})
          </option>
        ))}
      </SelectField>
      <p className={styles.hint}>
        {t('Stored under')} <b>{t('Resources → Credentials')}</b>
        {t(
          ', and never shown again. This node keeps only its id, so the flow can be exported, duplicated or rolled back without carrying the secret for {{what}}.',
          { what: what ?? t('this connection') },
        )}
      </p>
      {missing && (
        <p className={styles.hint}>
          <b>{t('The selected credential no longer exists.')}</b> {t('Pick another one.')}
        </p>
      )}
      {locked && (
        <p className={styles.hint}>
          <b>{t('The selected credential is locked: it was encrypted with a key this installation does not have.')}</b>{' '}
          {t('Enter its value again under Resources → Credentials; this node keeps pointing at it.')}
        </p>
      )}
    </>
  )
}
