import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { NodeExec } from '../api/types.ts'
import { OutputView } from './NodeExecView.tsx'

const exec = (overrides: Partial<NodeExec>): NodeExec => ({
  nodeId: 'n1',
  kind: 'agent',
  label: 'Coordinator',
  status: 'passed',
  inputTokens: 100,
  outputTokens: 40,
  startedAt: 0,
  endedAt: 0,
  output: 'done',
  ...overrides,
})

// Context occupancy, like Claude Code's /context: a snapshot of the block's latest message,
// with a percentage only when the model's window is actually known.
describe('OutputView context line', () => {
  // Assertions read the segment's textContent and format expectations with toLocaleString,
  // because the rendered digits depend on the machine's locale (50,000 here, 50.000 there).
  it('shows context with a percentage when the window is known', () => {
    render(<OutputView exec={exec({ contextTokens: 50_000, contextWindow: 200_000 })} />)
    const seg = screen.getByTitle(/Context window in use/)
    expect(seg.textContent).toContain(`ctx ${(50_000).toLocaleString()}`)
    expect(seg.textContent).toContain('(25%)')
  })

  it('shows the raw count without a percentage when the window is unknown', () => {
    render(<OutputView exec={exec({ contextTokens: 50_000, contextWindow: null })} />)
    const seg = screen.getByTitle(/Context window in use/)
    expect(seg.textContent).toContain(`ctx ${(50_000).toLocaleString()}`)
    expect(seg.textContent).not.toContain('%')
  })

  it('omits the context segment entirely before any usage arrives', () => {
    render(<OutputView exec={exec({ contextTokens: 0 })} />)
    expect(screen.queryByTitle(/Context window in use/)).not.toBeInTheDocument()
  })

  // The split itself is no longer on the output panel — it lives one click away, in the dialog.
  it('keeps the breakdown out of the panel until the ctx figure is clicked', () => {
    render(<OutputView exec={exec({ contextTokens: 87_000, contextWindow: 200_000 })} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(screen.getByTitle(/Context window in use/))
    expect(screen.getByRole('dialog', { name: 'Context usage' })).toBeInTheDocument()
  })

  it('splits the window into parts: prompt estimates, measured answers, the rest, free', () => {
    // Started at 24,000 (prompt: system + tools + a 4,000-char task ≈ 1,000 tokens), now at
    // 87,000 with 8,400 of those being the block's own answers.
    render(
      <OutputView
        exec={exec({
          contextTokens: 87_000,
          contextStartTokens: 24_000,
          contextWindow: 200_000,
          outputTokens: 8_400,
          input: 'x'.repeat(4_000),
        })}
      />,
    )
    fireEvent.click(screen.getByTitle(/Context window in use/))
    const row = (re: RegExp) =>
      screen.getByText((_, el) => el?.tagName === 'TR' && re.test(el.textContent ?? ''))
    expect(row(/^system \+ tools ~/).textContent).toContain('23.0k')
    expect(row(/^task ~/).textContent).toContain('1.0k')
    expect(row(/^agent output/).textContent).toContain('8.4k')
    // conversation (87k − 24k = 63k) minus the answers (8.4k) = tool results & other.
    expect(row(/^tool results & other/).textContent).toContain('54.6k')
    expect(row(/^Free space/).textContent).toContain('113.0k')
    // Exact counts stay reachable on the cell's tooltip; the column itself reads like /context.
    expect(screen.getByTitle(`${(113_000).toLocaleString()} tokens`)).toBeInTheDocument()
  })

  it('heads the dialog with the model and its occupancy, /context style', () => {
    render(
      <OutputView
        exec={exec({
          contextTokens: 87_000,
          contextWindow: 200_000,
          model: 'claude-opus-5',
        })}
      />,
    )
    fireEvent.click(screen.getByTitle(/Context window in use/))
    const dialog = screen.getByRole('dialog', { name: 'Context usage' })
    expect(dialog.textContent).toContain('claude-opus-5')
    expect(dialog.textContent).toContain('87.0k / 200.0k tokens (44%)')
  })
})
