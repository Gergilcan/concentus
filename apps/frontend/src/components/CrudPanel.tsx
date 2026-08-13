import { type ReactNode, useEffect, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
import { cx } from '../utils/cx.ts'
import styles from './resources.module.scss'

/** Renamed from `Field`: fields.tsx exports a *component* by that name. */
interface FieldSpec {
  key: string
  label: string
  type?: 'text' | 'number' | 'textarea' | 'select'
  options?: string[]
  placeholder?: string
  /**
   * Renders this field with a real component instead of a bare input.
   *
   * Exists because some values deserve the same control here that they get on the canvas. A model
   * is the clearest case: on a node it is a grouped picker showing rates and locally-served
   * models, and as a text box it was a field you had to already know the answer to type into. One
   * control, used in both places, so the two cannot drift apart.
   *
   * A field that supplies this owns its whole row, label included — the components used here
   * render their own, and the wrapper would produce a second one.
   */
  render?: (value: unknown, onChange: (next: unknown) => void) => ReactNode
}

interface Props<T> {
  title: string
  fields: FieldSpec[]
  labelOf: (item: T) => string
  idOf: (item: T) => string | undefined
  empty: () => T
  load: () => Promise<T[]>
  save: (item: T) => Promise<T>
  remove: (id: string) => Promise<void>
  extra?: (draft: T) => ReactNode
}

export function CrudPanel<T extends Record<string, unknown>>({
  title,
  fields,
  labelOf,
  idOf,
  empty,
  load,
  save,
  remove,
  extra,
}: Props<T>) {
  const [items, setItems] = useState<T[]>([])
  const [draft, setDraft] = useState<T>(empty())
  const [status, setStatus] = useState<string | null>(null)

  const refresh = () =>
    load()
      .then(setItems)
      .catch((e) => setStatus(String(e)))
  useEffect(() => {
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const set = (key: string, value: unknown) => setDraft((d) => ({ ...d, [key]: value }) as T)

  const onSave = async () => {
    setStatus(null)
    try {
      const saved = await save(draft)
      await refresh()
      setDraft(saved)
      setStatus('Saved')
    } catch (e) {
      setStatus(errMessage(e))
    }
  }

  const onDelete = async () => {
    const id = idOf(draft)
    if (!id) {
      setDraft(empty())
      return
    }
    try {
      await remove(id)
      await refresh()
      setDraft(empty())
      setStatus('Deleted')
    } catch (e) {
      setStatus(errMessage(e))
    }
  }

  return (
    <div className={styles.crud}>
      <div className={styles.crudList}>
        <div className={styles.crudListHead}>
          <h3 className={styles.h3}>{title}</h3>
          <button
            className={styles.newBtn}
            onClick={() => {
              setDraft(empty())
              setStatus(null)
            }}
          >
            + New
          </button>
        </div>
        {items.length === 0 && <div className={styles.muted}>None yet.</div>}
        {items.map((it) => (
          <button
            key={idOf(it)}
            className={cx(styles.crudItem, idOf(it) === idOf(draft) && styles.active)}
            onClick={() => {
              setDraft(it)
              setStatus(null)
            }}
          >
            <span className={styles.crudItemLabel}>{labelOf(it) || '(unnamed)'}</span>
            {/* Delete from the LIST, without opening: a record with data the form cannot render
                must still be removable — opening it first is exactly what a broken one can't
                survive. A span with role=button because a button may not contain a button. */}
            <span
              role="button"
              aria-label={`Delete ${labelOf(it) || 'this entry'}`}
              title="Delete without opening"
              className={styles.crudItemDelete}
              onClick={(e) => {
                e.stopPropagation()
                const id = idOf(it)
                if (!id) return
                if (!window.confirm(`Delete "${labelOf(it) || 'this entry'}"?`)) return
                void remove(id)
                  .then(() => refresh())
                  .then(() => {
                    if (idOf(draft) === id) setDraft(empty())
                    setStatus('Deleted')
                  })
                  .catch((err) => setStatus(errMessage(err)))
              }}
            >
              ✕
            </span>
          </button>
        ))}
      </div>

      <div className={styles.crudForm}>
        {fields.map((f) =>
          f.render ? (
            <div key={f.key}>{f.render(draft[f.key], (v) => set(f.key, v))}</div>
          ) : (
          <label key={f.key} className={styles.field}>
            <span>{f.label}</span>
            {f.type === 'textarea' ? (
              <textarea
                rows={7}
                value={String(draft[f.key] ?? '')}
                onChange={(e) => set(f.key, e.target.value)}
              />
            ) : f.type === 'select' ? (
              <select value={String(draft[f.key] ?? '')} onChange={(e) => set(f.key, e.target.value)}>
                {(f.options ?? []).map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </select>
            ) : (
              <input
                type={f.type === 'number' ? 'number' : 'text'}
                placeholder={f.placeholder}
                value={String(draft[f.key] ?? '')}
                onChange={(e) => set(f.key, f.type === 'number' ? Number(e.target.value) : e.target.value)}
              />
            )}
          </label>
          ),
        )}

        <div className={styles.crudActions}>
          <button className={styles.saveBtn} onClick={() => void onSave()}>
            Save
          </button>
          <button className={styles.delBtn} onClick={() => void onDelete()}>
            Delete
          </button>
          {status && <span className={styles.status}>{status}</span>}
        </div>

        {extra && extra(draft)}
      </div>
    </div>
  )
}
