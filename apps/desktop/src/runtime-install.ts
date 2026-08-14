import { spawn } from 'node:child_process'
import { log } from './log'

/**
 * Installing the runtimes stdio MCP servers need — Node/npm, pnpm, Python, pipx, uv.
 *
 * <p>Same deal as {@link ./claude-install}, and for the same person: someone who added an MCP
 * server from the catalogue, was told "uvx is not installed", and has never opened a terminal.
 * The rules that made the Claude Code installer acceptable apply here unchanged:
 *
 * <ul>
 *   <li>Every command is HARDCODED below — the official one each project publishes. Nothing a
 *       user, a setting or an MCP definition can influence is interpolated into it. The renderer
 *       passes a runtime id, never a command line.</li>
 *   <li>Nothing runs automatically. The UI shows the exact command next to the button that runs
 *       it, and streams every line of output rather than hiding it behind a spinner.</li>
 *   <li>No administrator rights. Where the only real install path needs them (Linux system
 *       package managers), there is deliberately NO command — the UI links the instructions
 *       instead of running `sudo` behind someone's back or guessing their distribution.</li>
 * </ul>
 *
 * <p>The commands live here rather than in the backend on purpose: the backend also runs on a
 * server, where an authenticated request must not be able to change what is installed on the host.
 * The backend only detects (see RuntimeProbe); this executes.
 */

export type RuntimeId = 'node' | 'npm' | 'pnpm' | 'python' | 'pipx' | 'uv'

/** What running the installer for a runtime would do on this platform. */
export interface RuntimeInstallPlan {
  runtime: RuntimeId
  /** The exact command, shown before it runs. Null when there is no safe unattended path here. */
  command: string | null
  /** Why there is no command, when there is none. */
  reason?: string
}

type Platform = 'win32' | 'darwin' | 'linux'

/**
 * The official commands, per platform.
 *
 * npm is not a row of its own: it ships with Node, so asking to install npm installs Node — which
 * is also the honest answer to give someone whose `npx` is missing.
 */
const COMMANDS: Record<RuntimeId, Partial<Record<Platform, string>>> = {
  // winget is present on Windows 11 and current Windows 10; brew is the de-facto macOS route.
  // Linux Node is a distribution package (or nvm) and both want decisions this app should not
  // make for someone — no command, instructions instead.
  node: {
    win32: 'winget install -e --id OpenJS.NodeJS.LTS',
    darwin: 'brew install node',
  },
  npm: {
    win32: 'winget install -e --id OpenJS.NodeJS.LTS',
    darwin: 'brew install node',
  },
  // pnpm publishes a standalone installer for all three platforms that needs no Node and no admin.
  pnpm: {
    win32: 'iwr https://get.pnpm.io/install.ps1 -useb | iex',
    darwin: 'curl -fsSL https://get.pnpm.io/install.sh | sh -',
    linux: 'curl -fsSL https://get.pnpm.io/install.sh | sh -',
  },
  python: {
    win32: 'winget install -e --id Python.Python.3.12',
    darwin: 'brew install python',
  },
  // pipx installs into the user site with pip, which is why this one works everywhere: it needs
  // Python (checked first by the UI, which offers Python's own install when it is missing).
  pipx: {
    win32: 'py -m pip install --user pipx',
    darwin: 'python3 -m pip install --user pipx',
    linux: 'python3 -m pip install --user pipx',
  },
  // uv's official installers are user-local on every platform, and uvx comes with it.
  uv: {
    win32: 'powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"',
    darwin: 'curl -LsSf https://astral.sh/uv/install.sh | sh',
    linux: 'curl -LsSf https://astral.sh/uv/install.sh | sh',
  },
}

const NO_COMMAND_REASON: Partial<Record<RuntimeId, string>> = {
  node: 'On Linux, Node comes from your distribution’s package manager (or nvm), which needs a choice this app should not make for you.',
  npm: 'npm ships with Node. On Linux, Node comes from your distribution’s package manager (or nvm).',
  python: 'On Linux, Python is part of the system and is managed by your distribution’s package manager.',
}

function platform(): Platform {
  const p = process.platform
  return p === 'win32' || p === 'darwin' ? p : 'linux'
}

/** What would run, so the UI can show it before anything happens. */
export function runtimeInstallPlan(runtime: RuntimeId): RuntimeInstallPlan {
  const command = COMMANDS[runtime]?.[platform()] ?? null
  if (command) return { runtime, command }
  return {
    runtime,
    command: null,
    reason: NO_COMMAND_REASON[runtime] ?? 'There is no unattended installer for this on your platform.',
  }
}

export interface RuntimeInstallResult {
  ok: boolean
  /** Why it failed, for the panel to show. Empty on success. */
  detail: string
}

/**
 * Runs the installer for one runtime, reporting output line by line.
 *
 * @param onOutput called for each chunk, stdout and stderr alike — installers use both, and
 *                 separating them here would only reorder the story
 */
export function installRuntime(
  runtime: RuntimeId,
  onOutput: (line: string) => void,
): Promise<RuntimeInstallResult> {
  const plan = runtimeInstallPlan(runtime)
  if (!plan.command) {
    return Promise.resolve({ ok: false, detail: plan.reason ?? 'Nothing to run on this platform.' })
  }

  const isWindows = process.platform === 'win32'
  // -NoProfile so a user's PowerShell profile cannot change what runs; a login shell on Unix so
  // the installer sees the PATH it expects (and so a freshly installed tool lands where the user's
  // own shell will find it).
  const [file, args] = isWindows
    ? ['powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', plan.command]]
    : ['/bin/bash', ['-lc', plan.command]]

  log.info(`Installing ${runtime}: ${plan.command}`)
  onOutput(`> ${plan.command}\n`)

  return new Promise((resolve) => {
    const child = spawn(file as string, args as string[], {
      env: process.env,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    })

    let tail = ''
    const capture = (chunk: Buffer) => {
      const text = chunk.toString()
      tail = (tail + text).slice(-2000)
      onOutput(text)
    }
    child.stdout?.on('data', capture)
    child.stderr?.on('data', capture)

    child.on('error', (err) => {
      log.error(`Could not start the ${runtime} installer`, err)
      resolve({ ok: false, detail: `Could not run the installer: ${err.message}` })
    })

    child.on('exit', (code) => {
      if (code === 0) {
        log.info(`${runtime} installer finished.`)
        resolve({ ok: true, detail: '' })
        return
      }
      log.warn(`${runtime} installer exited with ${code}.`)
      resolve({
        ok: false,
        // The installer's own last words beat an exit code — "winget is not recognized" tells the
        // user what to do, "exit 1" does not.
        detail: tail.trim().split('\n').slice(-3).join(' ') || `The installer exited with code ${code}.`,
      })
    })
  })
}

/** Guards the IPC boundary: the renderer sends a string, and only these six are commands here. */
export function isRuntimeId(value: unknown): value is RuntimeId {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(COMMANDS, value)
}
