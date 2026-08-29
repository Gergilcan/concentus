// Values that used to be duplicated across node/inspector components and the canvas store.
// Colours are NOT here: the node cards and the minimap both read the theme tokens in
// `styles/global.scss`, so there is no second table to keep in step with them.

/** Default model assigned to a freshly-created agent node. */
export const DEFAULT_MODEL = 'claude-opus-4-8'

/**
 * Models offered in the agent inspector.
 *
 * Claude only. There is no provider grouping because there are no providers to choose between:
 * a flow names a Claude model, and which credential is present decides where it runs — the
 * `claude` CLI on your subscription, or the cloud API on `ANTHROPIC_API_KEY`.
 *
 * The field stays free-text, so a model absent here (a new release) still works — this list is a
 * shortcut, not a whitelist.
 */
export const MODEL_GROUPS: {
  label: string
  hint: string
  models: string[]
}[] = [
  {
    label: 'Claude',
    hint: 'Runs on your Claude subscription via the local CLI, or on the API with ANTHROPIC_API_KEY.',
    models: [
      'claude-opus-4-8',
      'claude-opus-4-7',
      'claude-opus-4-6',
      'claude-sonnet-5',
      'claude-sonnet-4-6',
      'claude-haiku-4-5',
      'claude-fable-5',
    ],
  },
]

/** Reasoning-effort levels selectable on an agent node. */
export const EFFORT_OPTIONS = ['low', 'medium', 'high', 'xhigh', 'max'] as const

/** Default max-output-tokens for a freshly-created agent node. */
export const DEFAULT_MAX_TOKENS = 16000

/** Poll intervals (ms) used by the run console / auth badge. */
export const RUN_POLL_INTERVAL_MS = 1500
export const FLOWS_POLL_INTERVAL_MS = 4000
export const AUTH_POLL_INTERVAL_MS = 15000
export const TOAST_DURATION_MS = 5000
