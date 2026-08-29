import { useTranslation } from 'react-i18next'
import type { MailNodeData } from '../api/types.ts'
import { CredentialField } from './CredentialField.tsx'
import { CheckboxField, Field } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: MailNodeData
  set: (patch: Record<string, unknown>) => void
}

/**
 * Who gets the mail, and through which account.
 *
 * There is no body field, and that is the point of the node: the body is whatever the wire
 * carries — the run's final answer, a block's failure and log, the verifier's report. A field
 * here would be a second place for the same text to come from, and the two would disagree.
 */
export function MailInspector({ data, set }: Props) {
  const { t } = useTranslation()
  // Built outside t(): the braces are the placeholders the backend fills in, and handing them
  // to i18next would have it try to fill them in first.
  const placeholders = `{{flow}} ${t("becomes the flow's name;")} {{status}} ${t('becomes completed, failed or rejected — whichever output fired.')}`

  return (
    <>
      <Field label={t('Label')} value={data.label} onChange={(v) => set({ label: v })} />

      <Field
        label={<span title={t('One or more addresses, separated by commas.')}>{t('To ⓘ')}</span>}
        value={data.to}
        placeholder="gerard@example.com, ops@example.com"
        onChange={(v) => set({ to: v })}
      />
      <Field
        label={<span title={placeholders}>{t('Subject ⓘ')}</span>}
        value={data.subject}
        onChange={(v) => set({ subject: v })}
      />
      <p className={styles.hint}>
        <code>{'{{flow}}'}</code> {t("becomes the flow's name;")} <code>{'{{status}}'}</code>{' '}
        {t('becomes completed, failed or rejected — whichever output fired.')}
      </p>
      <p className={styles.hint}>
        {t(
          "Sent when the wire into this node fires. The body is what arrives on the wire — the run's final answer, a block's failure and log, or the verifier's report — so there is nothing to write here.",
        )}
      </p>

      <Field
        label={
          <span title={t("Your provider's SMTP host. Gmail: smtp.gmail.com · Microsoft 365: smtp.office365.com")}>
            {t('SMTP host ⓘ')}
          </span>
        }
        value={data.smtpHost}
        placeholder="smtp.gmail.com"
        onChange={(v) => set({ smtpHost: v })}
      />
      <Field
        label={t('Port')}
        type="number"
        value={data.smtpPort}
        onChange={(v) => set({ smtpPort: Number(v) || (data.smtpStarttls ? 587 : 465) })}
      />
      <CheckboxField
        label={
          <span
            title={t(
              'On: plain SMTP upgraded to TLS on 587, which is what nearly every provider documents. Off: SMTP over TLS on 465. Never unencrypted, either way.',
            )}
          >
            {t('Use STARTTLS (port 587) ⓘ')}
          </span>
        }
        checked={data.smtpStarttls}
        onChange={(v) => set({ smtpStarttls: v, smtpPort: v ? 587 : 465 })}
      />
      <Field
        label={
          <span title={t('The mailbox the mail is sent as. Most providers refuse a From that is not the signed-in mailbox.')}>
            {t('From address ⓘ')}
          </span>
        }
        value={data.from}
        placeholder="bot@example.com"
        onChange={(v) => set({ from: v })}
      />
      <Field
        label={
          <span title={t('The login, when it differs from the From address. Blank signs in as the From address.')}>
            {t('Username ⓘ')}
          </span>
        }
        value={data.smtpUsername}
        onChange={(v) => set({ smtpUsername: v })}
      />
      <CredentialField
        label={t('Mailbox password')}
        value={data.credentialId}
        onChange={(v) => set({ credentialId: v })}
        what={t('this mailbox')}
      />
      <p className={styles.hint}>
        {t('Gmail needs an app password here — a normal account password is refused over SMTP.')}
      </p>
    </>
  )
}
