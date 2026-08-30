import { type ReactNode, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { errMessage } from '../utils/errMessage.ts'
import { cx } from '../utils/cx.ts'
import { GroupChip } from './GroupChip.tsx'
import styles from './resources.module.scss'

/** Renamed from `Field`: fields.tsx exports a *component* by that name. */
interface FieldSpec {
  key: string
  label: string
  type?: 'text' | 'number' | 'textarea' | 'select'
  /**
   * Choices for a select. A bare string is both the stored value and the label, which is right
   * when the value is already a word a person would recognise. Where it is not — a role name, an
   * empty string meaning "anybody" — the pair says which is which, rather than making the label
   * do a job it cannot do.
   */
  options?: Array<string | { value: string; label: string }>
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
  /**
   * Leaves the field out for records it means nothing to — an MCP server launched by a command has
   * no URL and no token header, and an empty box with a URL placeholder in it invited people to
   * fill one in and wonder why nothing changed.
   */
  hidden?: (draft: Record<string, unknown>) => boolean
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
  /**
   * Extra controls under the form, for whatever this record needs that fields cannot express.
   *
   * `apply` exists for the ones that save on their own: it puts the saved record back in the form
   * and refreshes the list, so the selection survives. Without it such a panel had to ask the page
   * to remount this one, which reset the form and closed whatever was open.
   */
  extra?: (draft: T, apply: (saved: T) => void) => ReactNode
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
  const { t } = useTranslation()
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
      setStatus(t('Saved'))
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
      setStatus(t('Deleted'))
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
            {t('+ New')}
          </button>
        </div>
        {items.length === 0 && <div className={styles.muted}>{t('None yet.')}</div>}
        {items.map((it) => (
          <button
            key={idOf(it)}
            className={cx(styles.crudItem, idOf(it) === idOf(draft) && styles.active)}
            onClick={() => {
              setDraft(it)
              setStatus(null)
            }}
          >
            <span className={styles.crudItemLabel}>{labelOf(it) || t('(unnamed)')}</span>
            {/* Every kind listed here can be scoped to a group, and the wire shape names it the
                same way on all of them — so the chip is drawn once, here, for whichever has one. */}
            {typeof it.groupId === 'string' && <GroupChip groupId={it.groupId} />}
            {/* Delete from the LIST, without opening: a record with data the form cannot render
                must still be removable — opening it first is exactly what a broken one can't
                survive. A span with role=button because a button may not contain a button. */}
            <span
              role="button"
              aria-label={t('Delete {{name}}', { name: labelOf(it) || t('this entry') })}
              title={t('Delete without opening')}
              className={styles.crudItemDelete}
              onClick={(e) => {
                e.stopPropagation()
                const id = idOf(it)
                if (!id) return
                if (!window.confirm(t('Delete "{{name}}"?', { name: labelOf(it) || t('this entry') }))) return
                void remove(id)
                  .then(() => refresh())
                  .then(() => {
                    if (idOf(draft) === id) setDraft(empty())
                    setStatus(t('Deleted'))
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
        {fields
          .filter((f) => !f.hidden?.(draft))
          .map((f) =>
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
                {(f.options ?? []).map((o) => {
                  const value = typeof o === 'string' ? o : o.value
                  const label = typeof o === 'string' ? o : o.label
                  return (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  )
                })}
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
            {idOf(draft) ? t('Save') : t('Create')}
          </button>
          {/* Delete acted on nothing while the form held an unsaved draft, which is also the
              state the page opens in — so the first thing anybody saw was a blank form offering
              to delete itself. */}
          {idOf(draft) && (
            <button className={styles.delBtn} onClick={() => void onDelete()}>
              {t('Delete')}
            </button>
          )}
          {status && <span className={styles.status}>{status}</span>}
        </div>

        {extra &&
          extra(draft, (saved) => {
            setDraft(saved)
            void refresh()
          })}
      </div>
    </div>
  )
}
