import { useEffect, useMemo, useRef, useState } from 'react'
import { cx } from '../utils/cx.ts'
import { filterCommands, type Command } from './commandPalette.ts'
import styles from './commandPalette.module.scss'

/**
 * Ctrl+K: everything the app can do, reachable without knowing where it lives.
 *
 * The whole point is keystrokes, so it is built around them — type to narrow, arrows to move,
 * Enter to run, Escape to leave — and it hands focus back to whatever had it when it opened. A
 * palette that leaves you focused on nothing has taken your place in the page away, which is
 * exactly the cost keyboard users are trying to avoid.
 */
export function CommandPalette({ commands, onClose }: { commands: Command[]; onClose: () => void }) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  const matches = useMemo(() => filterCommands(commands, query), [commands, query])
  // A narrowed list can be shorter than where the cursor was; landing on nothing would make Enter
  // do nothing with no explanation.
  const index = Math.min(active, Math.max(0, matches.length - 1))

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    inputRef.current?.focus()
    return () => previous?.focus?.()
  }, [])

  // Keeps the highlighted row on screen when arrowing past the fold. Optional call: scrolling is
  // a nicety, and an environment without scrollIntoView (jsdom, for one) must not take the whole
  // palette down over it.
  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    row?.scrollIntoView?.({ block: 'nearest' })
  }, [index, matches.length])

  const runAt = (i: number) => {
    const command = matches[i]
    if (!command) return
    // Closed BEFORE running: an action that navigates or opens a dialog should not have to
    // compete with a palette still sitting on top of it.
    onClose()
    command.run()
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive(matches.length === 0 ? 0 : (index + 1) % matches.length)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive(matches.length === 0 ? 0 : (index - 1 + matches.length) % matches.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      runAt(index)
    } else if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    }
  }

  return (
    <div className={styles.backdrop} onClick={onClose}>
      <div
        className={styles.palette}
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        onClick={(e) => e.stopPropagation()}
      >
        <input
          ref={inputRef}
          className={styles.input}
          value={query}
          placeholder="Go to a flow, run one, switch view or theme…"
          aria-label="Command"
          onChange={(e) => {
            setQuery(e.target.value)
            setActive(0)
          }}
          onKeyDown={onKeyDown}
        />
        {matches.length === 0 ? (
          <p className={styles.empty}>Nothing matches “{query}”.</p>
        ) : (
          <ul className={styles.list} role="listbox" aria-label="Commands" ref={listRef}>
            {matches.map((command, i) => (
              <li
                key={command.id}
                role="option"
                aria-selected={i === index}
                className={cx(styles.row, i === index && styles.rowActive)}
                // onMouseDown, not onClick: the input must not lose focus first, which would
                // unmount this list before the click lands on it.
                onMouseDown={(e) => {
                  e.preventDefault()
                  runAt(i)
                }}
                onMouseEnter={() => setActive(i)}
              >
                <span className={styles.group}>{command.group}</span>
                <span className={styles.label}>{command.label}</span>
                {command.hint && <span className={styles.hint}>{command.hint}</span>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
