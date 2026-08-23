import { useTranslation } from 'react-i18next'
import styles from './spinner.module.scss'

/**
 * The one loading indicator, used wherever content is still on its way — a quiet two-tone ring
 * instead of a bare "Loading…" string. The optional label is for spots where WHAT is loading
 * isn't obvious from the surroundings. Under prefers-reduced-motion the global rule freezes the
 * rotation; the two-tone ring still reads as "busy".
 */
export function Spinner({ label }: { label?: string }) {
  const { t } = useTranslation()
  return (
    <span className={styles.wrap} role="status" aria-label={label ?? t('Loading')}>
      <span className={styles.ring} aria-hidden />
      {label && <span>{label}</span>}
    </span>
  )
}
