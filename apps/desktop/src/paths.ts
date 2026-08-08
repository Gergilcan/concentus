import { app } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'

/**
 * Where everything the shell needs lives, in both of the layouts it has to work in: the repo
 * checkout during development, and the unpacked installer once shipped.
 */

/** True in a packaged build; false under `electron .` from the repo. */
export const isPackaged = (): boolean => app.isPackaged

/**
 * Per-user application data — flows, agent library, MCP definitions, credentials and logs.
 *
 * Electron's `userData` is already the right folder on each platform (`%APPDATA%\Concentus`,
 * `~/Library/Application Support/Concentus`, `~/.config/Concentus`), and it is the folder the OS
 * expects an uninstaller to remove, so there is nothing to gain from computing our own.
 */
export function dataDir(): string {
  const dir = app.getPath('userData')
  fs.mkdirSync(dir, { recursive: true })
  return dir
}

/** The backend's log file. Its path is fixed by the desktop Spring profile; this must agree. */
export function backendLogFile(): string {
  return path.join(dataDir(), 'logs', 'backend.log')
}

/** The shell's own log, kept separate: a backend that never starts writes nothing to its own. */
export function shellLogFile(): string {
  return path.join(dataDir(), 'logs', 'desktop.log')
}

/** Small JSON file for the handful of things the shell itself remembers between launches. */
export function settingsFile(): string {
  return path.join(dataDir(), 'desktop-settings.json')
}

/** Root of the bundled payload: `resources/` in an installed app, `apps/desktop` in the repo. */
function payloadRoot(): string {
  // process.resourcesPath is where electron-builder puts `extraResources`. In the repo there is no
  // such folder, so the same files are looked for under the package's own directory — which is
  // what the build scripts populate before `electron .` is ever run.
  return isPackaged() ? process.resourcesPath : path.join(__dirname, '..')
}

/**
 * The backend jar.
 *
 * In the repo it is read straight out of Maven's `target/`, so a `mvn package` is immediately
 * picked up by the next launch with no copy step in between.
 */
export function backendJar(): string {
  if (!isPackaged()) {
    return path.join(__dirname, '..', '..', 'backend', 'target', 'concentus-backend.jar')
  }
  return path.join(payloadRoot(), 'backend', 'concentus-backend.jar')
}

/**
 * The Java executable to run it with.
 *
 * A packaged build carries its own trimmed runtime, so the user never installs Java. In the repo
 * we fall back to whatever `java` is on PATH — a developer here has a JDK by definition, and
 * requiring them to build a jlink image before they can launch the app would be pure friction.
 */
export function javaBinary(): string {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java'
  const bundled = path.join(payloadRoot(), 'jre', 'bin', exe)
  if (fs.existsSync(bundled)) return bundled
  if (isPackaged()) {
    // Not a fall-through to PATH: a packaged app finding no bundled runtime is a broken build, and
    // silently borrowing the user's Java would hide that until it breaks on a machine without one.
    throw new Error(`Bundled Java runtime missing at ${bundled}. The installation is incomplete.`)
  }
  return 'java'
}
