import * as fs from 'node:fs'
import { settingsFile } from './paths'
import { log } from './log'

/** The few things the shell itself remembers. Everything else is the backend's own data. */
export interface Settings {
  /**
   * The port to prefer on the next launch.
   *
   * Stability is not cosmetic: an MCP OAuth redirect URI is registered with the authorization
   * server as `http://127.0.0.1:<port>/...`, so a port that moves between launches invalidates
   * every MCP sign-in the user has already done.
   */
  port?: number
  /** An explicit path to the claude CLI, for the machine where discovery fails. */
  claudeCommand?: string
  /**
   * Set when the user ticks "Don't check on future launches" on the first-run page.
   *
   * Worth honouring rather than re-prompting: someone running only local models, or driving the
   * cloud API, has no use for a Claude Code login and should not be told about it at every launch.
   */
  skipClaudeCheck?: boolean
  /**
   * Set once the first-run wizard has been seen through to the end.
   *
   * <p>Separate from {@link skipClaudeCheck}: that one is the user declining a recurring check,
   * this one is "the database question has been asked". Without it the wizard would either never
   * ask about storage, or ask on every launch.
   */
  wizardCompleted?: boolean
  /**
   * Closing the window hides to the tray instead of quitting, so cron, mail and webhook triggers
   * keep firing. Opt-in: an app that keeps running after being closed is a background service the
   * user must have asked for, not a surprise found in Task Manager.
   */
  runInBackground?: boolean
  /** Launch (hidden, into the tray) when the user signs in to the OS. */
  startWithSystem?: boolean
  /** The one-time balloon explaining that closing hid the app rather than quit it. */
  trayTipShown?: boolean
}

export function loadSettings(): Settings {
  try {
    const file = settingsFile()
    if (!fs.existsSync(file)) return {}
    // BOM-tolerant: Notepad saves UTF-8 with a BOM, and a user hand-editing this file should not
    // have their settings silently discarded over an invisible character.
    return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, '')) as Settings
  } catch (err) {
    // Corrupt settings should not stop the app; defaults are all recoverable.
    log.warn(`Could not read settings: ${err instanceof Error ? err.message : String(err)}`)
    return {}
  }
}

export function saveSettings(settings: Settings): void {
  try {
    fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2), 'utf8')
  } catch (err) {
    log.warn(`Could not save settings: ${err instanceof Error ? err.message : String(err)}`)
  }
}
