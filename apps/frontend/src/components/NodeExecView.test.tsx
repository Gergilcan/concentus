import { render, screen } from '@testing-library/react'
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
    const legend = (re: RegExp) => screen.getByText((_, el) => re.test(el?.textContent ?? ''), { selector: 'span[title]' })
    expect(legend(/^system \+ tools ~/).textContent).toContain(`~${(23_000).toLocaleString()}`)
    expect(legend(/^task ~/).textContent).toContain(`~${(1_000).toLocaleString()}`)
    expect(legend(/^agent output /).textContent).toContain((8_400).toLocaleString())
    // conversation (87k − 24k = 63k) minus the answers (8.4k) = tool results & other.
    expect(legend(/^tool results & other /).textContent).toContain((54_600).toLocaleString())
    expect(legend(/^free /).textContent).toContain((113_000).toLocaleString())
  })
})
