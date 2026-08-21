import { execFile } from 'node:child_process'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { promisify } from 'node:util'
import { log } from './log'

const run = promisify(execFile)

/**
 * Putting the freshly-installed CLI on the user's PATH, and keeping it there.
 *
 * <p>Installing Claude Code from inside the app leaves it in a place the app knows and the person
 * does not. Concentus itself is fine — it resolves the binary by absolute path — but the moment
 * anybody opens a terminal and types <code>claude</code>, it is not there. That is a strange thing
 * for an application to do to somebody's machine: install a command line tool and not give them
 * the command.
 *
 * <p>Two halves, and both are needed. <b>Persisting</b> it so tomorrow's terminal has it, and
 * <b>this process's own environment</b> so the terminal opened five seconds from now has it —
 * every child inherits our block, and ours was read when the app started.
 *
 * <p><b>Never {@code setx}.</b> It is the obvious tool and it truncates PATH at 1024 characters,
 * silently, permanently. A developer's PATH is routinely longer than that, and the failure is
 * discovered days later when half their tooling has vanished. The registry is written directly
 * instead, preserving the value's kind so an entry written as {@code %USERPROFILE%\\...} is not
 * baked into an absolute path along the way.
 */

/** Whether a directory is already in a PATH-shaped string, ignoring case and trailing slashes. */
function alreadyListed(pathValue: string, dir: string): boolean {
  const wanted = dir.replace(/[\\/]+$/, '').toLowerCase()
  return pathValue
    .split(path.delimiter)
    .map((entry) => entry.trim().replace(/[\\/]+$/, '').toLowerCase())
    .some((entry) => entry === wanted)
}

export interface PathResult {
  /** True when the directory is on the persisted PATH — whether this call put it there or not. */
  ok: boolean
  /** True when this call changed something, so the caller can say so rather than guess. */
  changed: boolean
  /** What went wrong, or where it was written. Shown in the log and, on failure, to the user. */
  detail: string
}

/**
 * Windows: the per-user Path in the registry, read raw and written back with its own kind.
 *
 * <p>Read with {@code DoNotExpandEnvironmentNames} because a user Path routinely contains
 * {@code %USERPROFILE%} and friends; reading it expanded and writing it back would replace those
 * with today's absolute values, which is a quiet way to break a roaming profile.
 *
 * <p>The broadcast at the end is what makes Explorer re-read the environment, so a terminal opened
 * afterwards has the new PATH without signing out. Without it the change is real but invisible
 * until the next logon, which to the person looks exactly like it did not work.
 */
async function ensureOnWindowsPath(dir: string): Promise<PathResult> {
  const script = `
$ErrorActionPreference = 'Stop'
$dir = $args[0]
$key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
if ($null -eq $key) { throw 'Could not open HKCU\\Environment' }
try {
  $raw = $key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
  $kind = if ($null -eq $key.GetValue('Path')) { [Microsoft.Win32.RegistryValueKind]::ExpandString } else { $key.GetValueKind('Path') }
  $parts = @($raw -split ';' | Where-Object { $_.Trim() -ne '' })
  $already = $false
  foreach ($p in $parts) {
    if ($p.TrimEnd('\\','/').ToLower() -eq $dir.TrimEnd('\\','/').ToLower()) { $already = $true }
  }
  if ($already) { Write-Output 'already'; exit 0 }
  $new = (@($parts) + $dir) -join ';'
  $key.SetValue('Path', $new, $kind)
} finally { $key.Close() }

# Tell everybody the environment changed, so Explorer re-reads it and the next terminal has it.
Add-Type -Namespace Win32 -Name Env -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@
$out = [UIntPtr]::Zero
[void][Win32.Env]::SendMessageTimeout([IntPtr]0xffff, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 3000, [ref]$out)
Write-Output 'added'
`
  const { stdout } = await run(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', script, dir],
    { timeout: 20_000 },
  )
  const changed = stdout.includes('added')
  return {
    ok: true,
    changed,
    detail: changed ? `Added ${dir} to your PATH.` : `${dir} was already on your PATH.`,
  }
}

/**
 * Unix: a line in the shell's own startup file, written once.
 *
 * <p>Every file that exists gets it, rather than guessing which shell somebody uses — a person
 * with both bash and zsh installed uses whichever the terminal opens, and getting it wrong looks
 * identical to not having done it at all. Guarded by a marker so running this twice does not
 * append twice: a startup file that grows a line per install is a mess somebody else has to clean.
 */
async function ensureOnUnixPath(dir: string): Promise<PathResult> {
  const home = os.homedir()
  const marker = '# added by Concentus — Claude Code CLI'
  const line = `${marker}\nexport PATH="${dir}:$PATH"\n`

  const candidates = ['.bashrc', '.zshrc', '.profile']
    .map((f) => path.join(home, f))
    .filter((f) => fs.existsSync(f))
  // Nothing to append to at all: a container, or a very fresh account. .profile is the file every
  // POSIX login shell reads, so it is the one to create.
  const targets = candidates.length ? candidates : [path.join(home, '.profile')]

  let changed = false
  const written: string[] = []
  for (const file of targets) {
    const existing = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''
    if (existing.includes(marker) || alreadyListed(existing, dir)) continue
    fs.appendFileSync(file, (existing.endsWith('\n') || !existing ? '' : '\n') + '\n' + line)
    written.push(path.basename(file))
    changed = true
  }
  return {
    ok: true,
    changed,
    detail: changed
      ? `Added ${dir} to your PATH in ${written.join(', ')}. Open a new terminal to pick it up.`
      : `${dir} was already on your PATH.`,
  }
}

/**
 * Makes {@code dir} reachable as a command, now and next time.
 *
 * <p>Failing to persist is reported but never thrown: the app resolves the CLI by absolute path
 * and works either way, so a locked-down machine that refuses the registry write should get a
 * working Concentus and a sentence explaining what it did not manage, not a failed install.
 */
export async function ensureOnPath(dir: string): Promise<PathResult> {
  // This process first, and unconditionally: every terminal and every backend we launch inherits
  // our environment block, and ours was read when the app started — before the CLI existed.
  const current = process.env.PATH ?? ''
  if (!alreadyListed(current, dir)) {
    process.env.PATH = current ? `${dir}${path.delimiter}${current}` : dir
    log.info(`Added ${dir} to this process's PATH.`)
  }

  try {
    const result = process.platform === 'win32'
      ? await ensureOnWindowsPath(dir)
      : await ensureOnUnixPath(dir)
    log.info(`PATH: ${result.detail}`)
    return result
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err)
    log.warn(`Could not persist ${dir} onto the PATH: ${detail}`)
    return {
      ok: false,
      changed: false,
      detail: `Concentus can use the CLI, but could not add it to your PATH: ${detail}`,
    }
  }
}
