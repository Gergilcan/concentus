import { type ReactNode, useEffect, useState } from 'react'
import styles from './resources.module.scss'

export interface Field {
  key: string
  label: string
  type?: 'text' | 'number' | 'textarea' | 'select'
  options?: string[]
  placeholder?: string
}

interface Props<T> {
  title: string
  fields: Field[]
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
      setStatus(e instanceof Error ? e.message : String(e))
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
      setStatus(e instanceof Error ? e.message : String(e))
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
            className={`${styles.crudItem} ${idOf(it) === idOf(draft) ? styles.active : ''}`}
            onClick={() => {
              setDraft(it)
              setStatus(null)
            }}
          >
            {labelOf(it) || '(unnamed)'}
          </button>
        ))}
      </div>

      <div className={styles.crudForm}>
        {fields.map((f) => (
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
        ))}

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
