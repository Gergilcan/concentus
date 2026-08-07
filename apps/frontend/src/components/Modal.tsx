import { useEffect, type ReactNode } from 'react'
import styles from './flows.module.scss'

export function Modal({
  title,
  onClose,
  children,
  wide = false,
}: {
  title: string
  onClose: () => void
  children: ReactNode
  /** Roomier, for a dialog that is a task rather than a confirmation. */
  wide?: boolean
}) {
  // Escape closes it. A dialog you can only leave by aiming at a small ✕ is a dialog people
  // dismiss by reloading the page, which on this canvas costs unsaved work.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className={styles.backdrop} onClick={onClose}>
      <div
        className={wide ? `${styles.modal} ${styles.modalWide}` : styles.modal}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className={styles.modalHead}>
          <h3>{title}</h3>
          <button className={styles.icon} onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
