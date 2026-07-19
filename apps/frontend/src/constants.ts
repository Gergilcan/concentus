// Values that used to be duplicated across node/inspector components and the
// canvas store. Node color hexes mirror `styles/_theme.scss` ($agent/$mcp/$repo/$sql) —
// keep the two in sync if either changes.

/** Default model assigned to a freshly-created agent node. */
export const DEFAULT_MODEL = 'claude-opus-4-8'

/** Reasoning-effort levels selectable on an agent node. */
export const EFFORT_OPTIONS = ['low', 'medium', 'high', 'xhigh', 'max'] as const

/** Default max-output-tokens for a freshly-created agent node. */
export const DEFAULT_MAX_TOKENS = 16000

/** Node-kind accent colors, used for the minimap and node left-borders. */
export const NODE_COLORS: Record<string, string> = {
  agent: '#6ea8fe',
  coordinator: '#b98cff',
  mcp: '#63e6be',
  repo: '#ffd43b',
  sql: '#ff922b',
  input: '#51cf66',
  default: '#888',
}

/** Poll intervals (ms) used by the run console / auth badge. */
export const RUN_POLL_INTERVAL_MS = 1500
export const FLOWS_POLL_INTERVAL_MS = 4000
export const AUTH_POLL_INTERVAL_MS = 15000
export const TOAST_DURATION_MS = 5000
