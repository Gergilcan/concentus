import * as fs from 'node:fs'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeDir, scratchDir } from './helpers'

/**
 * runner.ts: the server URL and the token written together or not at all — the checks behind
 * the wizard's Connect and Disconnect, against the real settings file and the real token store.
 */

const mocks = vi.hoisted(() => ({
  dir: '',
  safeStorage: {
    isEncryptionAvailable: vi.fn(() => true),
    encryptString: vi.fn((value: string) => Buffer.from(`enc(${value})`)),
    decryptString: vi.fn((buffer: Buffer) => buffer.toString('utf8').replace(/^enc\((.*)\)$/, '$1')),
  },
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('electron', () => ({ safeStorage: mocks.safeStorage }))
vi.mock('../src/paths', async () => {
  const { join } = await import('node:path')
  return {
    dataDir: () => mocks.dir,
    settingsFile: () => join(mocks.dir, 'desktop-settings.json'),
  }
})
vi.mock('../src/log', () => ({ log: mocks.log }))

import { clearRunner, runnerState, saveRunner, validateRunnerUrl } from '../src/runner'
import { loadRunnerToken } from '../src/runner-token'
import { loadSettings, saveSettings } from '../src/settings'

const TOKEN = 'crn_' + 'a1b2c3d4e5'.repeat(4)
const tokenFile = () => path.join(mocks.dir, 'runner.token')

beforeEach(() => {
  mocks.dir = scratchDir('runner')
  mocks.safeStorage.isEncryptionAvailable.mockReturnValue(true)
})

afterEach(() => removeDir(mocks.dir))

describe('validateRunnerUrl', () => {
  it('takes http and https, and drops the trailing slash the backend would double', () => {
    expect(validateRunnerUrl(' https://hub.example.com/ ')).toEqual({ ok: true, url: 'https://hub.example.com' })
    expect(validateRunnerUrl('http://10.0.0.5:8080')).toEqual({ ok: true, url: 'http://10.0.0.5:8080' })
    expect(validateRunnerUrl('https://example.com/concentus/')).toEqual({ ok: true, url: 'https://example.com/concentus' })
  })

  it('refuses an empty address, a bare host name and any other scheme, each with its own sentence', () => {
    expect(validateRunnerUrl('')).toMatchObject({ ok: false, detail: expect.stringContaining('Enter the server\'s address') })
    expect(validateRunnerUrl(undefined)).toMatchObject({ ok: false })
    expect(validateRunnerUrl('hub.example.com')).toMatchObject({ ok: false, detail: expect.stringContaining('is not a URL') })
    expect(validateRunnerUrl('wss://hub.example.com')).toMatchObject({ ok: false, detail: expect.stringContaining('not wss://') })
    expect(validateRunnerUrl('file:///etc/passwd')).toMatchObject({ ok: false })
  })
})

describe('saveRunner', () => {
  it('a refused URL writes nothing — no token, no server', () => {
    const result = saveRunner({ url: 'hub.example.com', token: TOKEN })

    expect(result.ok).toBe(false)
    expect(fs.existsSync(tokenFile())).toBe(false)
    expect(loadSettings()).toEqual({})
  })

  it('a refused token records no server', () => {
    const result = saveRunner({ url: 'https://hub.example.com', token: 'crn_short' })

    expect(result.ok).toBe(false)
    expect(result.detail).toContain('begin with "crn_"')
    expect(fs.existsSync(tokenFile())).toBe(false)
    expect(loadSettings()).toEqual({})
  })

  it('writes both, keeps the other settings, and leaves the name out when it is blank', () => {
    saveSettings({ port: 8734, wizardCompleted: true })

    const result = saveRunner({ url: 'https://hub.example.com/', token: `${TOKEN}\n`, name: '  ' })

    expect(result.ok).toBe(true)
    expect(result.detail).toContain('keyring')
    expect(result.detail).toContain('register with https://hub.example.com')
    expect(loadSettings()).toEqual({ port: 8734, wizardCompleted: true, runner: { url: 'https://hub.example.com' } })
    expect(loadRunnerToken()).toBe(TOKEN)
    expect(runnerState()).toEqual({ url: 'https://hub.example.com', name: null, hasToken: true })
  })

  it('keeps the name when there is one', () => {
    saveRunner({ url: 'https://hub.example.com', token: TOKEN, name: ' office-pc ' })

    expect(loadSettings().runner).toEqual({ url: 'https://hub.example.com', name: 'office-pc' })
    expect(runnerState()).toEqual({ url: 'https://hub.example.com', name: 'office-pc', hasToken: true })
  })

  it('tolerates a draft with nothing in it', () => {
    expect(saveRunner({}).ok).toBe(false)
    expect(fs.existsSync(tokenFile())).toBe(false)
  })
})

describe('clearRunner', () => {
  it('removes the token and forgets the server, and the rest of the settings stay', () => {
    saveSettings({ runInBackground: true })
    saveRunner({ url: 'https://hub.example.com', token: TOKEN, name: 'office-pc' })

    const result = clearRunner()

    expect(result.ok).toBe(true)
    expect(fs.existsSync(tokenFile())).toBe(false)
    expect(loadSettings()).toEqual({ runInBackground: true })
    expect(runnerState()).toEqual({ url: null, name: null, hasToken: false })
  })

  it('is fine when nothing was configured', () => {
    expect(clearRunner().ok).toBe(true)
    expect(loadSettings()).toEqual({})
  })
})

describe('runnerState', () => {
  it('reports a URL whose token is gone as half configured, so the wizard shows the form again', () => {
    saveSettings({ runner: { url: 'https://hub.example.com' } })

    expect(runnerState()).toEqual({ url: 'https://hub.example.com', name: null, hasToken: false })
  })
})
