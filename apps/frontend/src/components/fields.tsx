import { useId, useState } from 'react'
import type { FocusEvent, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { cx } from '../utils/cx.ts'
import styles from './panels.module.scss'

/** Accessible label+input pair — mirrors the field-config idea in CrudPanel.tsx. */
interface FieldProps {
  label: ReactNode
  value: string | number
  onChange?: (value: string) => void
  /** `password` masks the value — for a secret being typed, never for one read back from the API. */
  type?: 'text' | 'number' | 'password' | 'time'
  placeholder?: string
  readOnly?: boolean
  className?: string
  onFocus?: (e: FocusEvent<HTMLInputElement>) => void
}

export function Field({ label, value, onChange, type = 'text', placeholder, readOnly, className, onFocus }: FieldProps) {
  const id = useId()
  return (
    <label className={cx(styles.field, className)} htmlFor={id}>
      <span>{label}</span>
      <input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        readOnly={readOnly}
        onFocus={onFocus}
        onChange={onChange ? (e) => onChange(e.target.value) : undefined}
      />
    </label>
  )
}

interface SelectFieldProps {
  label: ReactNode
  value: string
  onChange: (value: string) => void
  children: ReactNode
  className?: string
  /** A select has no read-only state, so this disables it — the value is shown, not editable. */
  readOnly?: boolean
}

export function SelectField({ label, value, onChange, children, className, readOnly }: SelectFieldProps) {
  const id = useId()
  return (
    <label className={cx(styles.field, className)} htmlFor={id}>
      <span>{label}</span>
      <select id={id} value={value} disabled={readOnly} onChange={(e) => onChange(e.target.value)}>
        {children}
      </select>
    </label>
  )
}

interface TextAreaFieldProps {
  label: ReactNode
  value: string
  onChange: (value: string) => void
  rows?: number
  placeholder?: string
  className?: string
  readOnly?: boolean
}

export function TextArea({ label, value, onChange, rows, placeholder, className, readOnly }: TextAreaFieldProps) {
  const id = useId()
  return (
    <label className={cx(styles.field, className)} htmlFor={id}>
      <span>{label}</span>
      <textarea
        id={id}
        rows={rows}
        placeholder={placeholder}
        value={value}
        readOnly={readOnly}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  )
}

interface CheckboxFieldProps {
  label: ReactNode
  checked: boolean
  onChange: (checked: boolean) => void
  className?: string
}

/**
 * A labelled checkbox.
 *
 * Laid out label-after-input rather than reusing `.field`'s stacked shape: a lone checkbox under
 * its own caption reads as a heading with an orphaned box, which is exactly how a condition gets
 * ticked by accident.
 */
export function CheckboxField({ label, checked, onChange, className }: CheckboxFieldProps) {
  const id = useId()
  return (
    <label className={cx(styles.checkField, className)} htmlFor={id}>
      <input id={id} type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>{label}</span>
    </label>
  )
}

/**
 * The home for a node's optional settings — everything that already has a sensible default.
 *
 * Every inspector was showing its whole vocabulary at once, and the fields someone actually has
 * to fill drowned among the ones they almost never touch. The split is by one rule: if the node
 * works without touching it, it lives here, collapsed; if the node needs it, it stays outside.
 * The values keep working exactly as before whether the group is open or not — this is about
 * where fields live, never about what they do.
 */
export function FineTuning({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  return (
    <div className={styles.fineTuning}>
      <button
        type="button"
        className={styles.fineTuningHead}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        title={t('Optional settings with sensible defaults. The node works without opening this.')}
      >
        <span className={styles.fineTuningArrow}>{open ? '▾' : '▸'}</span>
        {t('Fine-tuning')}
      </button>
      {open && children}
    </div>
  )
}

interface PagerProps {
  /** Zero-based current page. */
  page: number
  pages: number
  total: number
  /** What one item is, for the "N unit(s)" readout — "skill", "row". */
  unit: string
  onPage: (page: number) => void
}

/** The list pager the Knowledge panel established, shared: ‹ 1 / 3 · 42 skills ›. */
export function Pager({ page, pages, total, unit, onPage }: PagerProps) {
  if (pages <= 1) return null
  return (
    <div className={styles.pager}>
      <button type="button" disabled={page === 0} onClick={() => onPage(page - 1)}>‹</button>
      <span>{page + 1} / {pages} · {total} {unit}{total === 1 ? '' : 's'}</span>
      <button type="button" disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>›</button>
    </div>
  )
}
