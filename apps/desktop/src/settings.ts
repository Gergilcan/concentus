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
}

export function loadSettings(): Settings {
  try {
    const file = settingsFile()
    if (!fs.existsSync(file)) return {}
    return JSON.parse(fs.readFileSync(file, 'utf8')) as Settings
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
