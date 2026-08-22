import { useEffect, useRef, useState, type ReactNode } from 'react'
import styles from './flows.module.scss'

/**
 * One item in a card's overflow menu.
 *
 * @param label what the action does, in words. The whole point of the menu: the row it replaced
 *              said ⚕ ⟲ ⚙ ↓ ⎘ ⧉ 🧪 ✕ and asked people to hover each one to find out which was which
 * @param hint  the longer explanation, where there is one worth reading
 * @param danger destructive, and coloured as such — the only item that should give anyone pause
 */
export interface CardMenuItem {
  label: string
  icon: string
  hint?: string
  danger?: boolean
  disabled?: boolean
  disabledReason?: string
  onSelect: () => void
}

/**
 * The rest of a card's actions, behind one button.
 *
 * <p>A flow card carries up to nine controls, and at four or five columns they did not fit: they
 * wrapped onto a second line, and before that they sat on top of the card's own border. Neither is
 * a layout problem to solve with more pixels — nine controls is simply more than a card footer has
 * room for, and seven of them are things somebody does rarely.
 *
 * <p>So Open and Run stay where they are, and everything else moves in here — where each one gets
 * a name instead of a glyph. A menu is not only smaller; it is the first time these actions have
 * been readable without hovering them one at a time.
 *
 * <p>Disabled items stay visible rather than disappearing. An action that vanishes when you lack
 * permission teaches you it does not exist; one that is there and says why teaches you who to ask.
 */
export function CardMenu({ items, label }: { items: CardMenuItem[]; label: string }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false)
    }
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', away)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  if (items.length === 0) return null

  return (
    <div className={styles.menuWrap} ref={box}>
      <button
        type="button"
        className={styles.icon}
        aria-haspopup="menu"
        aria-expanded={open}
        title={`More actions for ${label}`}
        aria-label={`More actions for ${label}`}
        onClick={() => setOpen((v) => !v)}
      >
        ⋯
      </button>

      {open && (
        <div className={styles.menu} role="menu">
          {items.map((item) => (
            <button
              key={item.label}
              type="button"
              role="menuitem"
              className={item.danger ? `${styles.menuItem} ${styles.menuDanger}` : styles.menuItem}
              disabled={item.disabled}
              title={item.disabled ? item.disabledReason : item.hint}
              onClick={() => {
                setOpen(false)
                item.onSelect()
              }}
            >
              <span className={styles.menuIcon} aria-hidden="true">
                {item.icon}
              </span>
              <span className={styles.menuLabel}>{item.label}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/** Convenience for the caller: drop the items whose feature is not wired up on this card. */
// The falsy union is wide on purpose: a caller guards an item with `flow.id && …`, and an
// absent id is an empty string rather than false. Narrowing this to `false` makes the call site
// write a ternary for every optional item, which is noise around the thing that matters.
export function menuItems(
  items: Array<CardMenuItem | false | null | undefined | "" | 0>,
): CardMenuItem[] {
  return items.filter((i): i is CardMenuItem => !!i)
}

/** Re-exported so a caller can type an item inline without a second import. */
export type { ReactNode }
