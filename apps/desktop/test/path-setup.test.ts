import * as fs from 'node:fs'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeDir, restorePlatform, scratchDir, setPlatform } from './helpers'

/**
 * path-setup.ts: putting the freshly-installed CLI on the user's PATH, once.
 *
 * The Unix half writes real files into a scratch home directory, because the guard against
 * appending twice is the behaviour worth pinning and a fake filesystem would only pin the fake.
 * The Windows half cannot touch the registry from a test, so PowerShell is faked and what the
 * script asks it to do is inspected instead.
 */

const mocks = vi.hoisted(() => ({
  home: '',
  execFile: vi.fn(),
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('node:child_process', () => ({ execFile: mocks.execFile }))
vi.mock('node:os', async (importOriginal) => ({
  ...(await importOriginal<typeof import('node:os')>()),
  homedir: () => mocks.home,
}))
vi.mock('../src/log', () => ({ log: mocks.log }))

import { ensureOnPath } from '../src/path-setup'

const MARKER = '# added by Concentus — Claude Code CLI'
const DIR = '/opt/concentus/claude/bin'
const originalPath = process.env.PATH

function powershellPrints(stdout: string | Error): void {
  mocks.execFile.mockImplementation(
    (_file: string, _args: string[], _options: unknown, callback: (err: Error | null, out?: { stdout: string; stderr: string }) => void) => {
      if (stdout instanceof Error) callback(stdout)
      else callback(null, { stdout, stderr: '' })
    },
  )
}

const count = (haystack: string, needle: string) => haystack.split(needle).length - 1
const read = (name: string) => fs.readFileSync(path.join(mocks.home, name), 'utf8')

beforeEach(() => {
  mocks.home = scratchDir('path-setup')
  process.env.PATH = ['/usr/bin', '/bin'].join(path.delimiter)
  setPlatform('linux')
})

afterEach(() => {
  restorePlatform()
  process.env.PATH = originalPath
  removeDir(mocks.home)
})

describe('this process\'s own PATH', () => {
  it('gets the directory first, so every terminal and backend launched from now on inherits it', async () => {
    await ensureOnPath(DIR)

    expect(process.env.PATH!.split(path.delimiter)[0]).toBe(DIR)
    expect(mocks.log.info).toHaveBeenCalledWith(`Added ${DIR} to this process's PATH.`)
  })

  it('is not given the same directory twice, whatever the case or trailing slash', async () => {
    await ensureOnPath(DIR)
    await ensureOnPath(DIR + '/')
    await ensureOnPath(DIR.toUpperCase())

    expect(process.env.PATH!.split(path.delimiter).filter((e) => e.toLowerCase() === DIR)).toHaveLength(1)
  })
})

describe('on Unix, a line in the shell startup files', () => {
  it('is appended to every rc file that exists, and says to open a new terminal', async () => {
    fs.writeFileSync(path.join(mocks.home, '.bashrc'), 'alias ll="ls -l"\n')
    fs.writeFileSync(path.join(mocks.home, '.zshrc'), 'export FOO=1') // no trailing newline

    const result = await ensureOnPath(DIR)

    expect(result).toEqual({
      ok: true,
      changed: true,
      detail: `Added ${DIR} to your PATH in .bashrc, .zshrc. Open a new terminal to pick it up.`,
    })
    expect(read('.bashrc')).toBe(`alias ll="ls -l"\n\n${MARKER}\nexport PATH="${DIR}:$PATH"\n`)
    // A file that did not end in a newline gets one first, so the marker starts its own line.
    expect(read('.zshrc')).toBe(`export FOO=1\n\n${MARKER}\nexport PATH="${DIR}:$PATH"\n`)
    expect(fs.existsSync(path.join(mocks.home, '.profile'))).toBe(false)
  })

  it('a second install adds no second entry — the marker guards every file', async () => {
    fs.writeFileSync(path.join(mocks.home, '.bashrc'), '')
    fs.writeFileSync(path.join(mocks.home, '.zshrc'), '')

    await ensureOnPath(DIR)
    const again = await ensureOnPath(DIR)

    expect(again).toEqual({ ok: true, changed: false, detail: `${DIR} was already on your PATH.` })
    expect(count(read('.bashrc'), MARKER)).toBe(1)
    expect(count(read('.zshrc'), MARKER)).toBe(1)
  })

  it('creates .profile when the account has no startup file at all', async () => {
    const result = await ensureOnPath(DIR)

    expect(result.changed).toBe(true)
    expect(result.detail).toContain('.profile')
    expect(read('.profile')).toBe(`\n${MARKER}\nexport PATH="${DIR}:$PATH"\n`)
  })

  it('never touches PowerShell', async () => {
    await ensureOnPath(DIR)
    expect(mocks.execFile).not.toHaveBeenCalled()
  })
})

describe('on Windows, the per-user Path in the registry', () => {
  beforeEach(() => setPlatform('win32'))

  it('is written through PowerShell, raw and with its own kind — never setx', async () => {
    powershellPrints('added\n')

    const result = await ensureOnPath('C:\\Users\\tester\\.local\\bin')

    expect(result).toEqual({ ok: true, changed: true, detail: 'Added C:\\Users\\tester\\.local\\bin to your PATH.' })
    const [file, args] = mocks.execFile.mock.calls[0] as [string, string[]]
    expect(file).toBe('powershell.exe')
    expect(args.slice(0, 4)).toEqual(['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command'])
    const script = args[4]
    // Read unexpanded, so %USERPROFILE% entries survive the round trip.
    expect(script).toContain('DoNotExpandEnvironmentNames')
    expect(script).toContain("GetValueKind('Path')")
    // setx truncates PATH at 1024 characters, silently and permanently.
    expect(script.toLowerCase()).not.toContain('setx')
    // The directory travels as an argument, not spliced into the script.
    expect(args[5]).toBe('C:\\Users\\tester\\.local\\bin')
  })

  it('broadcasts the change so it applies now, not at the next logon', async () => {
    powershellPrints('added\n')

    await ensureOnPath('C:\\tools\\claude')

    const script = (mocks.execFile.mock.calls[0] as [string, string[]])[1][4]
    // WM_SETTINGCHANGE (0x1A) to every top-level window, with 'Environment' as the section.
    expect(script).toContain('SendMessageTimeout')
    expect(script).toContain('0x1A')
    expect(script).toContain("'Environment'")
  })

  it('reports an entry that was already there as unchanged', async () => {
    powershellPrints('already\n')

    const result = await ensureOnPath('C:\\tools\\claude')

    expect(result).toEqual({ ok: true, changed: false, detail: 'C:\\tools\\claude was already on your PATH.' })
  })

  it('leaves the rc files alone', async () => {
    powershellPrints('added\n')
    await ensureOnPath('C:\\tools\\claude')
    expect(fs.readdirSync(mocks.home)).toEqual([])
  })
})

describe('when persisting fails', () => {
  it('degrades to a warning: the app still works, the user is told, nothing throws', async () => {
    setPlatform('win32')
    powershellPrints(new Error('Requested registry access is not allowed.'))

    const result = await ensureOnPath('C:\\tools\\claude')

    expect(result.ok).toBe(false)
    expect(result.changed).toBe(false)
    expect(result.detail).toBe(
      'Concentus can use the CLI, but could not add it to your PATH: Requested registry access is not allowed.',
    )
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('Could not persist C:\\tools\\claude onto the PATH'))
    // The in-process half happened first and unconditionally, so this launch can still run it.
    expect(process.env.PATH!.split(path.delimiter)[0]).toBe('C:\\tools\\claude')
  })
})
