import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CommandPalette } from './CommandPalette.tsx'
import type { Command } from './commandPalette.ts'

function commands(runs: Record<string, () => void> = {}): Command[] {
  return [
    { id: 'v1', group: 'Go to', label: 'Flows', run: runs.flows ?? vi.fn() },
    { id: 'f1', group: 'Flows', label: 'Open Mail triage', run: runs.mail ?? vi.fn() },
    { id: 'f2', group: 'Flows', label: 'Run Daily briefing', run: runs.daily ?? vi.fn() },
  ]
}

function open(list = commands()) {
  const onClose = vi.fn()
  render(<CommandPalette commands={list} onClose={onClose} />)
  return { onClose, input: screen.getByLabelText('Command') }
}

describe('CommandPalette', () => {
  it('opens focused, so the first keystroke is already the search', () => {
    const { input } = open()

    expect(input).toHaveFocus()
    expect(screen.getAllByRole('option')).toHaveLength(3)
  })

  it('narrows as you type and runs the first match on Enter', () => {
    const mail = vi.fn()
    const { input, onClose } = open(commands({ mail }))

    fireEvent.change(input, { target: { value: 'mail' } })
    expect(screen.getAllByRole('option')).toHaveLength(1)

    fireEvent.keyDown(input, { key: 'Enter' })
    expect(mail).toHaveBeenCalled()
    // Closed before the action runs: an action that opens a dialog should not have to compete
    // with a palette still on top of it.
    expect(onClose).toHaveBeenCalled()
  })

  it('moves with the arrows and wraps around', () => {
    const daily = vi.fn()
    const { input } = open(commands({ daily }))

    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    expect(screen.getAllByRole('option')[2]).toHaveAttribute('aria-selected', 'true')

    fireEvent.keyDown(input, { key: 'ArrowDown' }) // past the end
    expect(screen.getAllByRole('option')[0]).toHaveAttribute('aria-selected', 'true')

    fireEvent.keyDown(input, { key: 'ArrowUp' }) // and back off the top
    expect(screen.getAllByRole('option')[2]).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(daily).toHaveBeenCalled()
  })

  it('keeps the highlight inside a list that just got shorter', () => {
    // Arrow to the last row, then type something that leaves fewer rows: Enter must still run
    // something rather than nothing.
    const mail = vi.fn()
    const { input } = open(commands({ mail }))

    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.change(input, { target: { value: 'mail' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(mail).toHaveBeenCalled()
  })

  it('says so when nothing matches, instead of showing an empty box', () => {
    const { input } = open()

    fireEvent.change(input, { target: { value: 'zzzz' } })

    expect(screen.getByText(/Nothing matches/)).toBeInTheDocument()
    expect(screen.queryAllByRole('option')).toHaveLength(0)
  })

  it('closes on Escape without running anything', () => {
    const mail = vi.fn()
    const { input, onClose } = open(commands({ mail }))

    fireEvent.keyDown(input, { key: 'Escape' })

    expect(onClose).toHaveBeenCalled()
    expect(mail).not.toHaveBeenCalled()
  })

  it('gives focus back to whatever had it', () => {
    // A palette that leaves you focused on nothing has taken your place in the page away, which
    // is the exact cost keyboard users are trying to avoid.
    const button = document.createElement('button')
    document.body.appendChild(button)
    button.focus()

    const { unmount } = render(<CommandPalette commands={commands()} onClose={vi.fn()} />)
    expect(screen.getByLabelText('Command')).toHaveFocus()

    unmount()
    expect(button).toHaveFocus()
    button.remove()
  })
})
