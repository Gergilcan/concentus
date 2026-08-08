import { execFile } from 'node:child_process'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { promisify } from 'node:util'
import { log } from './log'

const run = promisify(execFile)

/**
 * Finding the `claude` CLI, and the PATH it needs to run.
 *
 * This is the single most likely thing to break in a desktop build, and it breaks in a way that
 * looks like the user's fault. An app started from a desktop launcher does not inherit the PATH
 * from the user's shell — the launcher's environment comes from the session manager, which never
 * sourced ~/.bashrc, ~/.zshrc or nvm. So `claude` is installed, works perfectly in a terminal, and
 * is invisible to us.
 *
 * The backend's own resolution (LocalClaudeSupport) checks ~/.local/bin and then falls back to the
 * bare name `claude` for PATH resolution — which is exactly the case that fails here. So the shell
 * resolves an absolute path itself and hands it over as CLAUDE_COMMAND, leaving the backend's logic
 * untouched and correct for the server case it was written for.
 */

export interface ClaudeCli {
  /** Absolute path to the CLI, or null if it could not be found. */
  command: string | null
  /** PATH to give the backend — the login shell's, when we could read it. */
  path: string
  /** True when a Claude Code login exists on disk; without one, runs cannot start. */
  loggedIn: boolean
}

/**
 * The PATH a login shell would have.
 *
 * Windows is excluded deliberately: there the environment block is inherited properly from
 * Explorer, and there is no login shell to ask.
 */
async function loginShellPath(): Promise<string> {
  const inherited = process.env.PATH ?? ''
  if (process.platform === 'win32') return inherited

  const shell = process.env.SHELL || '/bin/bash'
  try {
    // -i (interactive) is what makes the shell read the rc files where version managers put
    // themselves; -l (login) covers profile files. Printing PATH rather than parsing rc files is
    // the only approach that works regardless of how the user set theirs up.
    const { stdout } = await run(shell, ['-ilc', 'printf %s "$PATH"'], { timeout: 5_000 })
    const resolved = stdout.trim()
    if (resolved) return resolved
    log.warn('Login shell returned an empty PATH; using the inherited one.')
  } catch (err) {
    // Not fatal, and not worth alarming the user about: some shells refuse -i without a tty. The
    // inherited PATH may still contain the CLI, and the explicit locations below are checked next.
    log.warn(`Could not read PATH from ${shell}: ${err instanceof Error ? err.message : String(err)}`)
  }
  return inherited
}

/** Locations the CLI installs itself into, checked before falling back to a PATH lookup. */
function knownInstallPaths(): string[] {
  const home = os.homedir()
  const exe = process.platform === 'win32' ? 'claude.exe' : 'claude'
  return [
    path.join(home, '.local', 'bin', exe),
    path.join(home, '.claude', 'local', exe),
    ...(process.platform === 'win32'
      ? [path.join(process.env.APPDATA ?? path.join(home, 'AppData', 'Roaming'), 'npm', 'claude.cmd')]
      : ['/usr/local/bin/claude', '/opt/homebrew/bin/claude']),
  ]
}

/** Ask the resolved PATH where the CLI is, the way the user's own shell would. */
async function which(searchPath: string): Promise<string | null> {
  const env = { ...process.env, PATH: searchPath }
  try {
    if (process.platform === 'win32') {
      const { stdout } = await run('where', ['claude'], { env, timeout: 5_000 })
      // `where` lists every match; the first is the one that would run.
      const first = stdout.split(/\r?\n/).map((l) => l.trim()).find(Boolean)
      return first ?? null
    }
    const { stdout } = await run('/bin/sh', ['-c', 'command -v claude'], { env, timeout: 5_000 })
    return stdout.trim() || null
  } catch {
    // A non-zero exit just means "not found" for both commands.
    return null
  }
}

/**
 * True when Claude Code has been signed in on this machine. The backend requires this before it
 * will report the local execution backend usable, so the shell checks it to tell the user to run
 * `claude` and `/login` rather than leaving them with a backend that silently refuses to run flows.
 */
function hasLogin(): boolean {
  const home = os.homedir()
  return fs.existsSync(path.join(home, '.claude.json')) || fs.existsSync(path.join(home, '.claude'))
}

export async function resolveClaudeCli(override?: string): Promise<ClaudeCli> {
  const searchPath = await loginShellPath()
  const loggedIn = hasLogin()

  // An explicit setting always wins — it exists precisely for the machine where discovery fails.
  if (override && override.trim()) {
    const chosen = override.trim()
    log.info(`Using configured claude command: ${chosen}`)
    return { command: chosen, path: searchPath, loggedIn }
  }

  const known = knownInstallPaths().find((p) => fs.existsSync(p))
  if (known) {
    log.info(`Found claude CLI at ${known}`)
    return { command: known, path: searchPath, loggedIn }
  }

  const found = await which(searchPath)
  if (found) {
    log.info(`Resolved claude CLI from PATH: ${found}`)
    return { command: found, path: searchPath, loggedIn }
  }

  log.warn('claude CLI not found. Local (subscription) runs will be unavailable until it is set.')
  return { command: null, path: searchPath, loggedIn }
}
