import { spawn } from 'node:child_process'
import { log } from './log'

/**
 * Installs Claude Code by running Anthropic's official installer.
 *
 * <p>Offered because the alternative is worse for the person it affects most: someone who has just
 * installed a desktop app, has never opened a terminal, and is told the product needs a command
 * line tool before it will do anything. The instructions are three lines and still lose people.
 *
 * <p><b>What this does and does not do.</b> It downloads and runs a script from Anthropic's own
 * domain — the exact command their documentation publishes, hardcoded here, over HTTPS, with no
 * interpolation of anything a user or a setting could influence. It runs as the current user and
 * needs no administrator rights. It is never automatic: it happens when someone presses a button
 * next to the command it is about to run, and every line of output is shown as it happens rather
 * than hidden behind a spinner. Piping a remote script to a shell deserves that much visibility.
 *
 * <p>The installer places the binary at {@code ~/.local/bin/claude}, which is already the first
 * place {@link ./claude-cli} looks — so a successful install is picked up by the same re-check
 * that a manual one would be.
 */

/** The commands Anthropic documents. Not built from parts, so there is nothing to inject into. */
const WINDOWS_COMMAND = 'irm https://claude.ai/install.ps1 | iex'
const UNIX_COMMAND = 'curl -fsSL https://claude.ai/install.sh | bash'

/** Shown in the UI before anything runs, so nobody is asked to trust an unnamed action. */
export function installCommand(): string {
  return process.platform === 'win32' ? WINDOWS_COMMAND : UNIX_COMMAND
}

export interface InstallResult {
  ok: boolean
  /** Why it failed, for the page to show. Empty on success. */
  detail: string
}

/**
 * Runs the installer, reporting output line by line.
 *
 * @param onOutput called for each chunk the installer writes, stdout and stderr alike — installers
 *                 use both, and separating them here would only reorder the story
 */
export function installClaude(onOutput: (line: string) => void): Promise<InstallResult> {
  const isWindows = process.platform === 'win32'
  const command = installCommand()

  // -NoProfile so a user's PowerShell profile cannot change what runs. ExecutionPolicy Bypass
  // because a restrictive machine policy would otherwise stop the official installer for reasons
  // that have nothing to do with this script.
  const [file, args] = isWindows
    ? ['powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command]]
    : ['/bin/bash', ['-lc', command]]

  log.info(`Installing Claude Code: ${command}`)
  onOutput(`> ${command}\n`)

  return new Promise((resolve) => {
    const child = spawn(file as string, args as string[], {
      // A login shell on Unix so the installer sees the PATH it expects; the environment is
      // otherwise inherited untouched.
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
      log.error('Could not start the Claude Code installer', err)
      resolve({ ok: false, detail: `Could not run the installer: ${err.message}` })
    })

    child.on('exit', (code) => {
      if (code === 0) {
        log.info('Claude Code installer finished.')
        resolve({ ok: true, detail: '' })
        return
      }
      log.warn(`Claude Code installer exited with ${code}.`)
      resolve({
        ok: false,
        // The installer's own last words are more useful than an exit code, so they are what the
        // user is shown.
        detail: tail.trim().split('\n').slice(-3).join(' ') || `The installer exited with code ${code}.`,
      })
    })
  })
}

/**
 * Opens a real terminal running the claude CLI, so the person signs in where the sign-in lives.
 *
 * The CLI's login is interactive by design — it walks you through an OAuth hop in the browser —
 * and no amount of piping can do it on someone's behalf. What the app CAN do is remove the step
 * where a person who has never opened a terminal is told to open one, find the freshly-installed
 * binary that is not on their PATH yet, and run it. So: after an install (and from the wizard's
 * sign-in button), a terminal opens already running the right binary by its full path; the CLI
 * takes it from there, and the wizard polls until the login appears.
 */
export async function openLoginTerminal(command: string): Promise<InstallResult> {
  log.info(`Opening a terminal for the claude sign-in: ${command}`)
  try {
    if (process.platform === 'win32') {
      if (command.toLowerCase().endsWith('.exe')) {
        // A console-subsystem exe spawned detached and unhidden gets its own console window —
        // no cmd/start quoting gymnastics, which matter because the path has the user's name in it.
        spawn(command, [], { detached: true, stdio: 'ignore', windowsHide: false }).unref()
      } else {
        // A .cmd/.bat shim needs a console host to run in.
        spawn('cmd.exe', ['/c', 'start', 'Claude sign-in', 'cmd', '/k', command],
          { detached: true, stdio: 'ignore', windowsHide: false }).unref()
      }
      return { ok: true, detail: '' }
    }
    if (process.platform === 'darwin') {
      spawn('open', ['-a', 'Terminal', command], { detached: true, stdio: 'ignore' }).unref()
      return { ok: true, detail: '' }
    }
    // Linux has no single terminal; try the conventional ones in order and keep the first that
    // starts. `-e` is the one flag they all understand.
    for (const terminal of ['x-terminal-emulator', 'gnome-terminal', 'konsole', 'xfce4-terminal', 'xterm']) {
      const started = await new Promise<boolean>((resolve) => {
        const child = spawn(terminal, ['-e', command], { detached: true, stdio: 'ignore' })
        child.on('error', () => resolve(false))
        // No error within a beat means the terminal is launching.
        setTimeout(() => resolve(child.exitCode === null || child.exitCode === 0), 400)
        child.unref()
      })
      if (started) return { ok: true, detail: '' }
    }
    return { ok: false, detail: 'No terminal emulator found — run the command by hand.' }
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err)
    log.warn(`Could not open a sign-in terminal: ${detail}`)
    return { ok: false, detail }
  }
}
