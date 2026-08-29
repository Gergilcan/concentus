import * as fs from 'node:fs'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeDir, scratchDir } from './helpers'

/**
 * settings.ts: the handful of things the shell remembers, in a file a person may hand-edit.
 */

const mocks = vi.hoisted(() => ({
  file: '',
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('../src/paths', () => ({ settingsFile: () => mocks.file }))
vi.mock('../src/log', () => ({ log: mocks.log }))

import { loadSettings, saveSettings } from '../src/settings'

let dir: string

beforeEach(() => {
  dir = scratchDir('settings')
  mocks.file = path.join(dir, 'desktop-settings.json')
})

afterEach(() => removeDir(dir))

describe('loadSettings', () => {
  it('is empty on a first run, and creates nothing', () => {
    expect(loadSettings()).toEqual({})
    expect(fs.existsSync(mocks.file)).toBe(false)
  })

  it('round-trips the port and the first-run flags', () => {
    saveSettings({ port: 8734, skipClaudeCheck: true, wizardCompleted: true, claudeCommand: '/opt/claude' })

    expect(loadSettings()).toEqual({
      port: 8734,
      skipClaudeCheck: true,
      wizardCompleted: true,
      claudeCommand: '/opt/claude',
    })
  })

  it('tolerates the BOM Notepad puts on a hand-edited file', () => {
    fs.writeFileSync(mocks.file, '﻿{ "port": 51234 }', 'utf8')

    expect(loadSettings()).toEqual({ port: 51234 })
  })

  it('treats a corrupt file as defaults, with a warning, and leaves it in place', () => {
    fs.writeFileSync(mocks.file, '{ "port": 87', 'utf8')

    expect(loadSettings()).toEqual({})
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('Could not read settings'))
    expect(fs.readFileSync(mocks.file, 'utf8')).toBe('{ "port": 87')
  })
})

describe('saveSettings', () => {
  it('writes the whole object, pretty-printed for the person who opens it', () => {
    saveSettings({ port: 8734, runInBackground: true })

    expect(fs.readFileSync(mocks.file, 'utf8')).toBe('{\n  "port": 8734,\n  "runInBackground": true\n}')
  })

  it('replaces rather than merges — saving without a port forgets the port', () => {
    saveSettings({ port: 51234, skipClaudeCheck: true })
    saveSettings({ skipClaudeCheck: true })

    expect(loadSettings()).toEqual({ skipClaudeCheck: true })
  })

  it('a file that cannot be written is a warning, not a crash', () => {
    mocks.file = path.join(dir, 'missing-parent', 'desktop-settings.json')

    expect(() => saveSettings({ port: 8734 })).not.toThrow()
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('Could not save settings'))
  })
})
