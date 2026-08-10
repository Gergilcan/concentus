import { execFile } from 'node:child_process'
import * as path from 'node:path'
import { dataDir } from './paths'
import { log } from './log'

/**
 * Killing what a previous launch left behind, before this one starts.
 *
 * The backend is a child of this shell and the embedded PostgreSQL a child of the backend — so a
 * shell that dies cleanly takes both with it. A shell (or backend) that dies HARD does not:
 * Windows has no process-group semantics to lean on, and a SIGKILLed java never gets to stop its
 * postgres. The leftovers then sabotage the next launch in two different ways: an orphaned
 * backend holds the preferred port (silently moving the app to an ephemeral one), and an orphaned
 * postgres holds the data directory, which PostgreSQL — correctly — refuses to share, surfacing
 * as "already running" on an app the user just started.
 *
 * The backend's own startup already clears a *stale* postmaster.pid (dead owner, reused PID); a
 * *live* orphan is the one case it deliberately leaves alone, because from inside the backend a
 * live postgres on the data directory is indistinguishable from a legitimate one. This shell has
 * the context the backend lacks: it holds the single-instance lock, so at this point in startup
 * there is no legitimate sibling — anything matching below is by definition debris.
 *
 * What qualifies, exactly:
 * - java processes whose command line names `concentus-backend.jar` — a leftover of this app or
 *   of a hand-launched jar. Deliberately NOT a dev backend under `mvn spring-boot:run`, whose
 *   command line carries a classpath, not the jar.
 * - postgres processes whose command line names OUR `pgdata` directory. Never any other
 *   PostgreSQL: a real server the user runs, or another app's embedded one, does not mention our
 *   data directory and is none of our business.
 */

interface ProcessInfo {
  pid: number
  name: string
  commandLine: string
}

/** Case- and slash-insensitive, because Windows command lines are both. */
function normalize(s: string): string {
  return s.toLowerCase().replace(/\\/g, '/')
}

/** The processes a fresh launch must clear away. Pure, so the policy is readable in one place. */
export function selectOrphans(processes: ProcessInfo[], appDataDir: string): ProcessInfo[] {
  const pgdata = normalize(path.join(appDataDir, 'pgdata'))
  return processes.filter((p) => {
    const cmd = normalize(p.commandLine ?? '')
    if (cmd.includes('concentus-backend.jar')) return true
    return cmd.includes('postgres') && cmd.includes(pgdata)
  })
}

function run(file: string, args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(file, args, { windowsHide: true, timeout: 15_000 }, (err, stdout) => {
      if (err) reject(err)
      else resolve(stdout)
    })
  })
}

/** Every java/postgres process with its command line, platform-appropriately. */
async function listCandidates(): Promise<ProcessInfo[]> {
  if (process.platform === 'win32') {
    // PowerShell rather than wmic: wmic is removed from current Windows 11 builds.
    const stdout = await run('powershell.exe', [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='postgres.exe'\" " +
        '| Select-Object ProcessId,Name,CommandLine | ConvertTo-Json -Compress',
    ])
    const text = stdout.trim()
    if (!text) return []
    const parsed: unknown = JSON.parse(text)
    // ConvertTo-Json unwraps a single result to a bare object.
    const rows = Array.isArray(parsed) ? parsed : [parsed]
    return rows.map((r) => {
      const row = r as { ProcessId: number; Name?: string; CommandLine?: string }
      return { pid: row.ProcessId, name: row.Name ?? '', commandLine: row.CommandLine ?? '' }
    })
  }

  const stdout = await run('ps', ['-axo', 'pid=,comm=,args='])
  return stdout
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const match = /^(\d+)\s+(\S+)\s+(.*)$/.exec(line)
      return match
        ? { pid: Number(match[1]), name: path.basename(match[2]), commandLine: match[3] }
        : null
    })
    .filter((p): p is ProcessInfo => p !== null && (p.name.includes('java') || p.name.includes('postgres')))
}

async function kill(p: ProcessInfo): Promise<void> {
  if (process.platform === 'win32') {
    // /T kills the tree: a leftover backend's own postgres child goes with it, instead of being
    // promoted to exactly the kind of orphan this sweep exists to remove.
    await run('taskkill', ['/PID', String(p.pid), '/T', '/F'])
  } else {
    process.kill(p.pid, 'SIGKILL')
  }
}

/**
 * Clears leftovers, then lets the OS settle before the caller binds ports and opens the data
 * directory. Failures are logged and swallowed: a sweep that cannot run must degrade to the
 * pre-sweep behaviour (the startup error names the real problem), not become a new way to fail.
 */
export async function killOrphans(): Promise<void> {
  let candidates: ProcessInfo[]
  try {
    candidates = await listCandidates()
  } catch (err) {
    log.warn(`Could not scan for leftover processes: ${err instanceof Error ? err.message : String(err)}`)
    return
  }

  const orphans = selectOrphans(candidates, dataDir())
  if (orphans.length === 0) return

  for (const p of orphans) {
    const what = p.commandLine.includes('concentus-backend.jar') ? 'backend' : 'embedded postgres'
    log.warn(`Killing a leftover ${what} from a previous run (pid ${p.pid}).`)
    try {
      await kill(p)
    } catch (err) {
      // Already gone, or not ours to kill: either way the startup that follows will tell.
      log.warn(`Could not kill pid ${p.pid}: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  // Give the OS a moment to release the port and the data-directory locks the dead processes
  // held; the backend's own stale-pid cleanup handles what remains on disk.
  await new Promise((r) => setTimeout(r, 500))
}
