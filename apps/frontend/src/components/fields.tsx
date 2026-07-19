import { useId } from 'react'
import type { FocusEvent, ReactNode } from 'react'
import { cx } from '../utils/cx.ts'
import styles from './panels.module.scss'

/** Accessible label+input pair — mirrors the field-config idea in CrudPanel.tsx. */
interface FieldProps {
  label: ReactNode
  value: string | number
  onChange?: (value: string) => void
  type?: 'text' | 'number'
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
}

export function SelectField({ label, value, onChange, children, className }: SelectFieldProps) {
  const id = useId()
  return (
    <label className={cx(styles.field, className)} htmlFor={id}>
      <span>{label}</span>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
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
}

export function TextArea({ label, value, onChange, rows, placeholder, className }: TextAreaFieldProps) {
  const id = useId()
  return (
    <label className={cx(styles.field, className)} htmlFor={id}>
      <span>{label}</span>
      <textarea id={id} rows={rows} placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} />
    </label>
  )
}
