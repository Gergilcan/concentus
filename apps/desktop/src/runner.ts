import { hasRunnerToken, saveRunnerToken } from './runner-token'
import { loadSettings, saveSettings } from './settings'
import { log } from './log'

/**
 * Connecting this machine to a Concentus server, as one setting with two halves.
 *
 * <p>The URL is in the settings file and the token is in the keyring, but nobody configures half
 * a runner: a URL with no token dials nothing, and a token with no URL has nowhere to go. So the
 * wizard's Connect and Disconnect land here, where both are written or neither is — and the
 * checks that would otherwise sit inside an IPC handler nothing can call from a test sit in a
 * function that anything can.
 */

/** What the wizard's Server step is rendered with, and what it gets back after saving. */
export interface RunnerState {
  url: string | null
  name: string | null
  /** Whether a token is stored — never the token; the page only shows that one exists. */
  hasToken: boolean
}

export function runnerState(): RunnerState {
  const runner = loadSettings().runner
  return {
    url: runner?.url ?? null,
    name: runner?.name ?? null,
    hasToken: hasRunnerToken(),
  }
}

/** What the page sends: three strings, none of which it has checked. */
export interface RunnerDraft {
  url?: unknown
  token?: unknown
  name?: unknown
}

export interface RunnerSaveResult {
  ok: boolean
  detail: string
}

/**
 * The server's address, or why the text is not one.
 *
 * <p>Only the scheme is judged. Whether the host answers is the backend's to find out when it
 * dials, and it reports that through the runner status; refusing a name that does not resolve
 * from here would refuse every server that is reachable only once the VPN is up.
 */
export function validateRunnerUrl(raw: unknown): { ok: true; url: string } | { ok: false; detail: string } {
  const text = String(raw ?? '').trim()
  if (!text) return { ok: false, detail: 'Enter the server\'s address, like https://concentus.example.com.' }
  let parsed: URL
  try {
    parsed = new URL(text)
  } catch {
    return { ok: false, detail: `"${text}" is not a URL. It should start with https:// (or http:// on a private network).` }
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    return { ok: false, detail: `The server address must start with https:// or http://, not ${parsed.protocol}//.` }
  }
  // Without the trailing slash: the backend appends /ws/runner to it, and the tray shows it as
  // typed, where a slash on the end of a host name reads as a typo.
  return { ok: true, url: text.replace(/\/+$/, '') }
}

/**
 * Stores the server and the token, both or neither.
 *
 * <p>The URL is judged first because it costs nothing, then the token, which writes on success —
 * so a refused URL never leaves a token behind for a server that was never recorded, and a refused
 * token never records a server the backend would dial with nothing to say.
 */
export function saveRunner(draft: RunnerDraft): RunnerSaveResult {
  const url = validateRunnerUrl(draft.url)
  if (!url.ok) return { ok: false, detail: url.detail }

  const token = saveRunnerToken(typeof draft.token === 'string' ? draft.token : '')
  if (!token.ok) return token

  const name = String(draft.name ?? '').trim()
  saveSettings({ ...loadSettings(), runner: { url: url.url, ...(name ? { name } : {}) } })
  log.info(`Runner configured for ${url.url}${name ? ` as "${name}"` : ''}.`)
  return { ok: true, detail: `${token.detail} This machine will register with ${url.url} when the backend restarts.` }
}

/** Forgets the server and removes the token; the other settings stay as they were. */
export function clearRunner(): RunnerSaveResult {
  const removed = saveRunnerToken(null)
  if (!removed.ok) return removed
  const { runner: _forgotten, ...rest } = loadSettings()
  saveSettings(rest)
  log.info('Runner configuration removed.')
  return removed
}
