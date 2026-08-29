import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { restorePlatform, setPlatform } from './helpers'

/**
 * claude-cli.ts: finding the CLI from a process that did not inherit the user's shell PATH.
 *
 * Every process here is faked — the login shell, `which`, `where` — and the filesystem answers
 * only for the paths a test says exist, so each test pins one decision and nothing else.
 */

const mocks = vi.hoisted(() => ({
  HOME: '/home/tester',
  execFile: vi.fn(),
  existsSync: vi.fn(),
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('node:child_process', () => ({ execFile: mocks.execFile }))
vi.mock('node:fs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('node:fs')>()),
  existsSync: mocks.existsSync,
}))
vi.mock('node:os', async (importOriginal) => ({
  ...(await importOriginal<typeof import('node:os')>()),
  homedir: () => mocks.HOME,
}))
vi.mock('../src/log', () => ({ log: mocks.log }))

import { resolveClaudeCli } from '../src/claude-cli'

const HOME = mocks.HOME
type Answer = string | Error

/** Script the child processes: what each command prints, or the error it fails with. */
function commands(handler: (file: string, args: string[]) => Answer): void {
  mocks.execFile.mockImplementation(
    (file: string, args: string[], _options: unknown, callback: (err: Error | null, out?: { stdout: string; stderr: string }) => void) => {
      const answer = handler(file, args)
      if (answer instanceof Error) callback(answer)
      else callback(null, { stdout: answer, stderr: '' })
    },
  )
}

function existing(...paths: string[]): void {
  const set = new Set(paths)
  mocks.existsSync.mockImplementation((p: string) => set.has(p))
}

const callsTo = (file: string) => mocks.execFile.mock.calls.filter((c) => c[0] === file)

const originalPath = process.env.PATH
const originalShell = process.env.SHELL
const originalAppData = process.env.APPDATA

beforeEach(() => {
  setPlatform('linux')
  process.env.PATH = ['/usr/bin', '/bin'].join(path.delimiter)
  process.env.SHELL = '/bin/zsh'
  existing()
  commands(() => new Error('not found'))
})

afterEach(() => {
  restorePlatform()
  process.env.PATH = originalPath
  process.env.SHELL = originalShell
  if (originalAppData === undefined) delete process.env.APPDATA
  else process.env.APPDATA = originalAppData
})

describe('the PATH a desktop launcher does not inherit', () => {
  it('is rebuilt by asking the login shell, interactively, and handed to the backend', async () => {
    const loginPath = ['/home/tester/.nvm/versions/node/v22/bin', '/usr/bin'].join(path.delimiter)
    commands((file, args) => (file === '/bin/zsh' && args[0] === '-ilc' ? loginPath : new Error('no')))

    const cli = await resolveClaudeCli()

    expect(cli.path).toBe(loginPath)
    const [file, args] = callsTo('/bin/zsh')[0]
    expect(file).toBe('/bin/zsh')
    // -i reads the rc files where version managers put themselves; -l covers the profile.
    expect(args).toEqual(['-ilc', 'printf %s "$PATH"'])
  })

  it('keeps the inherited PATH, with a warning and no error, when the shell refuses -i', async () => {
    commands(() => new Error('zsh: can not open tty'))

    const cli = await resolveClaudeCli()

    expect(cli.path).toBe(process.env.PATH)
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('Could not read PATH from /bin/zsh'))
  })

  it('keeps the inherited PATH when the shell prints nothing', async () => {
    commands((file) => (file === '/bin/zsh' ? '   ' : new Error('no')))

    const cli = await resolveClaudeCli()

    expect(cli.path).toBe(process.env.PATH)
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('empty PATH'))
  })

  it('defaults to /bin/bash when SHELL is unset', async () => {
    delete process.env.SHELL
    commands((file) => (file === '/bin/bash' ? '/from/bash' : new Error('no')))

    expect((await resolveClaudeCli()).path).toBe('/from/bash')
  })

  it('never asks a shell on Windows — Explorer hands the environment block down properly', async () => {
    setPlatform('win32')

    const cli = await resolveClaudeCli()

    expect(cli.path).toBe(process.env.PATH)
    expect(callsTo('/bin/zsh')).toHaveLength(0)
  })
})

describe('where the CLI is looked for', () => {
  it('an explicit setting wins over every kind of discovery', async () => {
    existing(path.join(HOME, '.local', 'bin', 'claude'))

    const cli = await resolveClaudeCli('  /somewhere/odd/claude  ')

    expect(cli.command).toBe('/somewhere/odd/claude')
    expect(callsTo('/bin/sh')).toHaveLength(0)
  })

  it('checks the known install directories before consulting PATH at all', async () => {
    const installed = path.join(HOME, '.local', 'bin', 'claude')
    existing(installed)

    const cli = await resolveClaudeCli()

    expect(cli.command).toBe(installed)
    expect(callsTo('/bin/sh')).toHaveLength(0)
    expect(mocks.log.info).toHaveBeenCalledWith(`Found claude CLI at ${installed}`)
  })

  it('knows the CLI\'s own local dir and the Homebrew and /usr/local prefixes', async () => {
    existing('/opt/homebrew/bin/claude')
    expect((await resolveClaudeCli()).command).toBe('/opt/homebrew/bin/claude')

    existing(path.join(HOME, '.claude', 'local', 'claude'))
    expect((await resolveClaudeCli()).command).toBe(path.join(HOME, '.claude', 'local', 'claude'))

    existing('/usr/local/bin/claude')
    expect((await resolveClaudeCli()).command).toBe('/usr/local/bin/claude')
  })

  it('then asks the rebuilt PATH the way the user\'s shell would', async () => {
    const loginPath = ['/home/tester/.local/share/pnpm', '/usr/bin'].join(path.delimiter)
    commands((file, args) => {
      if (file === '/bin/zsh') return loginPath
      if (file === '/bin/sh' && args[1] === 'command -v claude') return '/home/tester/.local/share/pnpm/claude\n'
      return new Error('no')
    })

    const cli = await resolveClaudeCli()

    expect(cli.command).toBe('/home/tester/.local/share/pnpm/claude')
    const [, , options] = callsTo('/bin/sh')[0] as [string, string[], { env: NodeJS.ProcessEnv }]
    // The lookup runs with the login shell's PATH, not the launcher's — that is the whole point.
    expect(options.env.PATH).toBe(loginPath)
  })

  it('reports null, with a warning, when nothing finds it — and still returns the PATH', async () => {
    commands((file) => (file === '/bin/zsh' ? '/login/path' : new Error('exit 1')))

    const cli = await resolveClaudeCli()

    expect(cli.command).toBeNull()
    expect(cli.path).toBe('/login/path')
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('claude CLI not found'))
  })
})

describe('on Windows', () => {
  beforeEach(() => {
    setPlatform('win32')
    process.env.APPDATA = 'C:\\Users\\tester\\AppData\\Roaming'
  })

  it('looks for claude.exe in the install dirs and the npm shim claude.cmd under %APPDATA%', async () => {
    const shim = path.join('C:\\Users\\tester\\AppData\\Roaming', 'npm', 'claude.cmd')
    existing(shim)

    const cli = await resolveClaudeCli()

    expect(cli.command).toBe(shim)
    const probed = mocks.existsSync.mock.calls.map((c) => c[0] as string)
    expect(probed).toContain(path.join(HOME, '.local', 'bin', 'claude.exe'))
    expect(probed).toContain(path.join(HOME, '.claude', 'local', 'claude.exe'))
    expect(probed).not.toContain('/usr/local/bin/claude')
  })

  it('falls back to the user profile when APPDATA is unset', async () => {
    delete process.env.APPDATA
    const shim = path.join(HOME, 'AppData', 'Roaming', 'npm', 'claude.cmd')
    existing(shim)

    expect((await resolveClaudeCli()).command).toBe(shim)
  })

  it('asks `where` and takes the first match — the one that would actually run', async () => {
    commands((file, args) =>
      file === 'where' && args[0] === 'claude'
        ? 'C:\\Users\\tester\\AppData\\Roaming\\npm\\claude.cmd\r\nC:\\Program Files\\nodejs\\claude.cmd\r\n'
        : new Error('no'),
    )

    expect((await resolveClaudeCli()).command).toBe('C:\\Users\\tester\\AppData\\Roaming\\npm\\claude.cmd')
  })

  it('treats a non-zero exit from `where` as not found', async () => {
    commands(() => new Error('INFO: Could not find files for the given pattern(s).'))

    expect((await resolveClaudeCli()).command).toBeNull()
  })
})

describe('whether Claude Code is signed in', () => {
  it('is true when ~/.claude.json exists', async () => {
    existing(path.join(HOME, '.claude.json'))
    expect((await resolveClaudeCli()).loggedIn).toBe(true)
  })

  it('is true when the ~/.claude directory exists', async () => {
    existing(path.join(HOME, '.claude'))
    expect((await resolveClaudeCli()).loggedIn).toBe(true)
  })

  it('is false with neither — an override for the command does not imply a login', async () => {
    expect((await resolveClaudeCli('/x/claude')).loggedIn).toBe(false)
  })
})
