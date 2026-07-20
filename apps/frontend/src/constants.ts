// Values that used to be duplicated across node/inspector components and the
// canvas store. Node color hexes mirror `styles/_theme.scss` ($agent/$mcp/$repo/$sql) —
// keep the two in sync if either changes.

/** Default model assigned to a freshly-created agent node. */
export const DEFAULT_MODEL = 'claude-opus-4-8'

/**
 * Models offered in the agent inspector, grouped by the provider they route to.
 *
 * `providerId` matches what `GET /api/llm/providers` reports, so a group can be marked as
 * needing a key. Claude is the exception: it has no entry there because it runs on its own
 * backends rather than through a provider credential.
 *
 * The field stays free-text, so anything absent here (a new release, an Ollama model) still works
 * — this list is a shortcut, not a whitelist.
 */
export const MODEL_GROUPS: {
  label: string
  providerId: string | null
  hint: string
  models: string[]
}[] = [
  {
    label: 'Claude — subscription or API key',
    providerId: null,
    hint: 'Runs on your Claude subscription (or the cloud API). Full tools: files, bash, MCP.',
    models: ['claude-opus-4-8', 'claude-opus-4-7', 'claude-sonnet-5', 'claude-sonnet-4-6', 'claude-haiku-4-5'],
  },
  {
    label: 'OpenAI — API key, billed per token',
    providerId: 'openai',
    hint: 'Needs OPENAI_API_KEY. A ChatGPT subscription does not grant API access.',
    models: ['gpt-5', 'gpt-5-mini', 'o3'],
  },
  {
    label: 'Google Gemini — API key, billed per token',
    providerId: 'gemini',
    hint: 'Needs GEMINI_API_KEY (or VERTEX_* for Vertex AI).',
    models: ['gemini-3.5-flash', 'gemini-2.5-pro', 'gemini-2.5-flash', 'gemini-2.5-flash-lite'],
  },
  {
    label: 'DeepSeek — API key, billed per token',
    providerId: 'deepseek',
    hint: 'Needs DEEPSEEK_API_KEY.',
    models: ['deepseek-chat', 'deepseek-reasoner'],
  },
]

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
