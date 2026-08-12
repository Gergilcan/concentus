import type { NodeExec } from '../api/types.ts'

/**
 * The /context-style split of one block's window, shared by the node inspector's breakdown and
 * the golden comparison. Two parts are measured exactly from usage (the block's own answers, and
 * the conversation total); the starting prompt's split between "system + tools" and "task" is an
 * estimate (≈4 chars/token on the recorded input), because the stream reports the prompt as one
 * number. Estimated parts carry `approx`.
 */
export interface CtxPart {
  key: string
  label: string
  value: number
  approx: boolean
  /** Stable per-part hue, so the same part wears the same color everywhere it is drawn. */
  hue: number
  hint: string
}

export function contextParts(exec: NodeExec): CtxPart[] {
  const ctx = exec.contextTokens ?? 0
  if (ctx <= 0) return []
  const start = Math.min(exec.contextStartTokens ?? 0, ctx)
  const taskEst = Math.min(exec.input ? Math.round(exec.input.length / 4) : 0, start)
  const sysTools = start - taskEst
  const conversation = Math.max(0, ctx - start)
  const answers = Math.min(exec.outputTokens ?? 0, conversation)
  const other = conversation - answers
  return [
    {
      key: 'sys',
      label: 'system + tools',
      value: sysTools,
      approx: true,
      hue: 210,
      hint: 'System prompt, tool and MCP schemas — in context for the whole session. Estimated: the starting prompt minus the task estimate.',
    },
    {
      key: 'task',
      label: 'task',
      value: taskEst,
      approx: true,
      hue: 180,
      hint: 'The input this block was handed (its Input tab), estimated at ≈4 characters per token.',
    },
    {
      key: 'out',
      label: 'agent output',
      value: answers,
      approx: false,
      hue: 140,
      hint: "This block's own answers, measured from usage.",
    },
    {
      key: 'tools',
      label: 'tool results & other',
      value: other,
      approx: false,
      hue: 30,
      hint: 'Everything else the conversation brought into the window — tool results, commands, sub-agent reports. Measured: conversation growth minus the answers.',
    },
  ].filter((p) => p.value > 0)
}

/** What remains of the model's window; 0 when the window is unknown. */
export function freeContext(exec: NodeExec): number {
  const ctx = exec.contextTokens ?? 0
  const win = exec.contextWindow ?? 0
  return ctx > 0 && win > 0 ? Math.max(0, win - ctx) : 0
}

export const ctxColor = (hue: number) => `hsl(${hue} 50% 45%)`
