import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { createPortal } from 'react-dom'
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

/** Where the popup sits, in viewport coordinates. */
interface At {
  top: number
  right: number
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
 * <p><b>Rendered into the body, not the card.</b> A card sets {@code overflow: hidden} to keep its
 * tone stripe inside its rounded corner, and that clips anything that tries to escape it — the
 * menu opened and had its top three items sliced off by the card's own edge. A popup positioned
 * from the button's rect and painted at the top of the document is the only version of this that
 * cannot be cut off by whatever it happens to be inside.
 *
 * <p>Disabled items stay visible rather than disappearing. An action that vanishes when you lack
 * permission teaches you it does not exist; one that is there and says why teaches you who to ask.
 */
export function CardMenu({ items, label }: { items: CardMenuItem[]; label: string }) {
  const { t } = useTranslation()
  const [at, setAt] = useState<At | null>(null)
  const button = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)

  const place = () => {
    const b = button.current?.getBoundingClientRect()
    if (!b) return
    const height = menu.current?.offsetHeight ?? 0
    // Above the button by default — this row is the last thing in a card, and a menu opening
    // downwards from the bottom row of a grid opens off the screen. Below when there is no room
    // above, which is the case for the first row of cards on a short window.
    const above = b.top - 6 - height
    setAt({ top: above >= 8 ? above : b.bottom + 6, right: window.innerWidth - b.right })
  }

  // Placed after the menu exists, so its real height decides which way it opens.
  useLayoutEffect(() => {
    if (at) place()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [at !== null])

  useEffect(() => {
    if (!at) return
    const away = (e: MouseEvent) => {
      const target = e.target as Node
      if (menu.current?.contains(target) || button.current?.contains(target)) return
      setAt(null)
    }
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setAt(null)
    }
    // A menu anchored to a rect is wrong the moment the page moves under it, and closing is the
    // honest response: re-positioning mid-scroll makes it look like it is following the cursor.
    const close = () => setAt(null)
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', escape)
    window.addEventListener('resize', close)
    window.addEventListener('scroll', close, true)
    return () => {
      document.removeEventListener('mousedown', away)
      document.removeEventListener('keydown', escape)
      window.removeEventListener('resize', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [at])

  if (items.length === 0) return null

  return (
    <>
      <button
        ref={button}
        type="button"
        className={styles.icon}
        aria-haspopup="menu"
        aria-expanded={at !== null}
        title={t('More actions for {{label}}', { label })}
        aria-label={t('More actions for {{label}}', { label })}
        onClick={() => setAt((v) => (v ? null : { top: -9999, right: 0 }))}
      >
        ⋯
      </button>

      {at &&
        createPortal(
          <div
            ref={menu}
            className={styles.menu}
            role="menu"
            style={{ top: at.top, right: at.right }}
          >
            {items.map((item) => (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                className={item.danger ? `${styles.menuItem} ${styles.menuDanger}` : styles.menuItem}
                disabled={item.disabled}
                title={item.disabled ? item.disabledReason : item.hint}
                onClick={() => {
                  setAt(null)
                  item.onSelect()
                }}
              >
                <span className={styles.menuIcon} aria-hidden="true">
                  {item.icon}
                </span>
                <span className={styles.menuLabel}>{item.label}</span>
              </button>
            ))}
          </div>,
          document.body,
        )}
    </>
  )
}
