import { useEffect, useRef, type ReactNode } from 'react'
import { cx } from '../utils/cx.ts'
import styles from './flows.module.scss'

/**
 * Open dialogs, innermost last. Every Modal listens for Escape on the window, so without this
 * a dialog opened from inside another one (the context breakdown over the comparison) would
 * close its parent as well — one key press, both gone, and the run comparison lost.
 */
const openDialogs: object[] = []

export function Modal({
  title,
  onClose,
  children,
  wide = false,
  className,
}: {
  title: string
  onClose: () => void
  children: ReactNode
  /** Roomier, for a dialog that is a task rather than a confirmation. */
  wide?: boolean
  /**
   * Extra class on the dialog box. For the shape a particular dialog needs and no other — a fixed
   * height, say — which cannot be set from inside `children`: the box is their parent.
   */
  className?: string
}) {
  // Read through a ref so the listener below can register once, on mount: re-registering on a
  // new onClose identity would push this dialog back to the top of the stack while a nested one
  // is open, and Escape would start closing the wrong one.
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  // Escape closes it — but only the topmost one. A dialog you can only leave by aiming at a
  // small ✕ is a dialog people dismiss by reloading the page, which on this canvas costs
  // unsaved work.
  useEffect(() => {
    const token = {}
    openDialogs.push(token)
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && openDialogs[openDialogs.length - 1] === token) closeRef.current()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      const i = openDialogs.indexOf(token)
      if (i >= 0) openDialogs.splice(i, 1)
    }
  }, [])

  return (
    <div className={styles.backdrop} onClick={onClose}>
      <div
        className={cx(styles.modal, wide && styles.modalWide, className)}
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
