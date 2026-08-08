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
