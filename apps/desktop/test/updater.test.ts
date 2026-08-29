import type { AppUpdater } from 'electron-updater'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { restorePlatform, setPlatform } from './helpers'

/**
 * updater.ts: when a run may update itself, which releases it follows, and what the Updates
 * panel gets to see. electron-updater itself is replaced by a fake handed in through the loader
 * seam, so the tests pin what the shell asks of it — not what it does.
 */

const mocks = vi.hoisted(() => ({
  app: { isPackaged: false, getVersion: vi.fn(() => '1.0.0') },
  shown: [] as Array<{ title: string; body: string }>,
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('electron', () => ({
  app: mocks.app,
  Notification: class {
    constructor(private readonly options: { title: string; body: string }) {}
    show() { mocks.shown.push(this.options) }
  },
}))
vi.mock('../src/log', () => ({ log: mocks.log }))

type Updater = typeof import('../src/updater')

interface FakeUpdater {
  channel: string | null | undefined
  allowPrerelease: boolean | undefined
  autoInstallOnAppQuit: boolean
  logger: unknown
  on: ReturnType<typeof vi.fn>
  checkForUpdates: ReturnType<typeof vi.fn>
  quitAndInstall: ReturnType<typeof vi.fn>
  emit: (event: string, arg?: unknown) => void
}

function fakeUpdater(): FakeUpdater {
  const handlers = new Map<string, (arg: unknown) => void>()
  const fake: FakeUpdater = {
    channel: undefined,
    allowPrerelease: undefined,
    autoInstallOnAppQuit: false,
    logger: null,
    on: vi.fn((event: string, handler: (arg: unknown) => void) => { handlers.set(event, handler); return fake }),
    checkForUpdates: vi.fn(() => Promise.resolve(undefined)),
    quitAndInstall: vi.fn(),
    emit: (event, arg) => handlers.get(event)?.(arg),
  }
  return fake
}

const asLoader = (fake: FakeUpdater) => () => fake as unknown as AppUpdater

/** A packaged Windows build of the given version — the common case for everything below. */
function installed(version: string): void {
  mocks.app.isPackaged = true
  mocks.app.getVersion.mockReturnValue(version)
  setPlatform('win32')
}

let updater: Updater
const originalAppImage = process.env.APPIMAGE

beforeEach(async () => {
  // Module state (the phase, the updater) must not leak between tests.
  vi.resetModules()
  updater = await import('../src/updater')
  mocks.app.isPackaged = false
  mocks.app.getVersion.mockReturnValue('1.0.0')
  mocks.shown.length = 0
  delete process.env.APPIMAGE
})

afterEach(() => {
  restorePlatform()
  vi.useRealTimers()
  if (originalAppImage === undefined) delete process.env.APPIMAGE
  else process.env.APPIMAGE = originalAppImage
})

describe('isPrerelease — the same rule release.yml marks a prerelease with', () => {
  it('is a dash anywhere in the version, dotted or glued', () => {
    expect(updater.isPrerelease('0.1.3-beta.1')).toBe(true)
    expect(updater.isPrerelease('0.1.0-rc15')).toBe(true)
    expect(updater.isPrerelease('0.1.3')).toBe(false)
    expect(updater.isPrerelease('1.0.0')).toBe(false)
  })
})

describe('updateSupport — who may update themselves', () => {
  it('a development run has nothing to replace', () => {
    expect(updater.updateSupport({ packaged: false, platform: 'win32', appImage: false })).toEqual({
      supported: false,
      reason: 'Development run — only the installed app can update itself.',
    })
  })

  it('a .deb belongs to the package manager', () => {
    expect(updater.updateSupport({ packaged: true, platform: 'linux', appImage: false })).toEqual({
      supported: false,
      reason: 'This install updates through the system package manager, not the app.',
    })
  })

  it('NSIS on Windows and the AppImage on Linux update themselves', () => {
    expect(updater.updateSupport({ packaged: true, platform: 'win32', appImage: false })).toEqual({ supported: true })
    expect(updater.updateSupport({ packaged: true, platform: 'linux', appImage: true })).toEqual({ supported: true })
  })
})

describe('startAutoUpdates standing down', () => {
  it('in a development run: no updater is loaded, the status says why, the version is honest', () => {
    const load = vi.fn(asLoader(fakeUpdater()))

    updater.startAutoUpdates(load)

    expect(load).not.toHaveBeenCalled()
    expect(updater.updateStatus()).toEqual({
      supported: false,
      phase: 'idle',
      reason: 'Development run — only the installed app can update itself.',
      // Not package.json's placeholder 1.0.0, which would look like a real release and be wrong.
      version: 'development build',
    })
  })

  it('for a .deb install: the package manager owns updates', () => {
    mocks.app.isPackaged = true
    setPlatform('linux')
    const load = vi.fn(asLoader(fakeUpdater()))

    updater.startAutoUpdates(load)

    expect(load).not.toHaveBeenCalled()
    expect(updater.updateStatus().reason).toBe('This install updates through the system package manager, not the app.')
    expect(updater.updateStatus().version).toBe('1.0.0')
  })

  it('but not for an AppImage, which electron-updater can replace', () => {
    mocks.app.isPackaged = true
    setPlatform('linux')
    process.env.APPIMAGE = '/home/tester/Applications/Concentus.AppImage'
    const load = vi.fn(asLoader(fakeUpdater()))

    updater.startAutoUpdates(load)

    expect(load).toHaveBeenCalledTimes(1)
    expect(updater.updateStatus()).toMatchObject({ supported: true, phase: 'idle' })
  })
})

describe('which releases a build follows', () => {
  it('a prerelease build follows betas and finals; no channel is ever pinned', () => {
    installed('0.1.3-beta.1')
    const fake = fakeUpdater()

    updater.startAutoUpdates(asLoader(fake))

    expect(fake.allowPrerelease).toBe(true)
    // Pinning `channel` is what once hid every stable release from prerelease installs: only a
    // null/alpha/beta channel is allowed to see a final, and the version string already says
    // which train this build is on.
    expect(fake.channel).toBeUndefined()
  })

  it('a final build follows finals only — nobody asked to be moved onto a prerelease', () => {
    installed('0.1.3')
    const fake = fakeUpdater()

    updater.startAutoUpdates(asLoader(fake))

    expect(fake.allowPrerelease).toBe(false)
    expect(fake.channel).toBeUndefined()
  })

  it('installs on quit, checks at once, and again every four hours for tray sessions', () => {
    vi.useFakeTimers()
    installed('0.1.3')
    const fake = fakeUpdater()

    updater.startAutoUpdates(asLoader(fake))

    expect(fake.autoInstallOnAppQuit).toBe(true)
    expect(fake.checkForUpdates).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(4 * 60 * 60 * 1000)
    expect(fake.checkForUpdates).toHaveBeenCalledTimes(2)
  })

  it('routes the updater\'s own logging into the shell log', () => {
    installed('0.1.3')
    const fake = fakeUpdater()

    updater.startAutoUpdates(asLoader(fake))
    ;(fake.logger as { warn: (m: unknown) => void }).warn('rate limited')

    expect(mocks.log.warn).toHaveBeenCalledWith('Auto-update: rate limited')
  })
})

describe('the phases the Updates panel sees', () => {
  let fake: FakeUpdater

  beforeEach(() => {
    installed('0.1.3')
    fake = fakeUpdater()
    updater.startAutoUpdates(asLoader(fake))
  })

  it('finding an update starts the download, and progress is rounded', () => {
    fake.emit('checking-for-update')
    expect(updater.updateStatus()).toMatchObject({ phase: 'checking' })

    fake.emit('update-available', { version: '0.2.0' })
    expect(updater.updateStatus()).toMatchObject({ phase: 'downloading', available: '0.2.0', progressPercent: 0 })

    fake.emit('download-progress', { percent: 42.6 })
    expect(updater.updateStatus()).toMatchObject({ phase: 'downloading', progressPercent: 43 })
  })

  it('a downloaded update is announced once, and waits for the quit', () => {
    fake.emit('update-downloaded', { version: '0.2.0' })

    expect(updater.updateStatus()).toMatchObject({ phase: 'downloaded', available: '0.2.0', progressPercent: 100 })
    expect(updater.updateStatus().checkedAt).toBeTypeOf('number')
    expect(mocks.shown).toEqual([
      { title: 'Update ready', body: 'Concentus 0.2.0 is downloaded and will install the next time you quit.' },
    ])
    expect(fake.quitAndInstall).not.toHaveBeenCalled()
  })

  it('nothing upstream is "up to date", with the time of the check', () => {
    fake.emit('update-available', { version: '0.2.0' })
    fake.emit('update-not-available')

    expect(updater.updateStatus()).toMatchObject({ phase: 'up-to-date', available: undefined })
    expect(updater.updateStatus().checkedAt).toBeTypeOf('number')
  })

  it('an error is kept, in words, and cleared by the next check', () => {
    fake.emit('error', new Error('net::ERR_INTERNET_DISCONNECTED'))
    expect(updater.updateStatus()).toMatchObject({ phase: 'error', error: 'net::ERR_INTERNET_DISCONNECTED' })

    fake.emit('checking-for-update')
    expect(updater.updateStatus().error).toBeUndefined()
  })
})

describe('checkForUpdatesNow', () => {
  it('before the updater exists, just reports the state', async () => {
    expect(await updater.checkForUpdatesNow()).toMatchObject({ supported: false, phase: 'idle' })
  })

  it('surfaces a failed check in the returned state, not only in the log', async () => {
    installed('0.1.3')
    const fake = fakeUpdater()
    updater.startAutoUpdates(asLoader(fake))
    fake.checkForUpdates.mockRejectedValueOnce(new Error('HTTP 403'))

    const result = await updater.checkForUpdatesNow()

    expect(result).toMatchObject({ phase: 'error', error: 'HTTP 403' })
  })
})

describe('installUpdateNow', () => {
  it('refuses when this run cannot update, with the reason once one is known', async () => {
    expect(await updater.installUpdateNow()).toEqual({ ok: false, error: 'This run cannot update itself.' })

    updater.startAutoUpdates(asLoader(fakeUpdater()))

    expect(await updater.installUpdateNow()).toEqual({
      ok: false,
      error: 'Development run — only the installed app can update itself.',
    })
  })

  it('refuses until something has been downloaded', async () => {
    installed('0.1.3')
    const fake = fakeUpdater()
    updater.startAutoUpdates(asLoader(fake))

    expect(await updater.installUpdateNow()).toEqual({
      ok: false,
      error: 'No downloaded update to install yet — check for updates first.',
    })
    expect(fake.quitAndInstall).not.toHaveBeenCalled()
  })

  it('closes everything first, then installs silently and relaunches', async () => {
    installed('0.1.3')
    const fake = fakeUpdater()
    updater.startAutoUpdates(asLoader(fake))
    fake.emit('update-downloaded', { version: '0.2.0' })
    const order: string[] = []
    updater.onBeforeInstall(async () => { order.push('closed') })
    fake.quitAndInstall.mockImplementation(() => order.push('installed'))

    expect(await updater.installUpdateNow()).toEqual({ ok: true })

    // The backend holds the files the installer overwrites; it must be gone before NSIS starts.
    expect(order).toEqual(['closed', 'installed'])
    expect(fake.quitAndInstall).toHaveBeenCalledWith(true, true)
  })

  it('still installs when the shutdown step complains — the installer waits for exit anyway', async () => {
    installed('0.1.3')
    const fake = fakeUpdater()
    updater.startAutoUpdates(asLoader(fake))
    fake.emit('update-downloaded', { version: '0.2.0' })
    updater.onBeforeInstall(async () => { throw new Error('taskkill failed') })

    expect(await updater.installUpdateNow()).toEqual({ ok: true })

    expect(fake.quitAndInstall).toHaveBeenCalledTimes(1)
    expect(mocks.log.warn).toHaveBeenCalledWith('Auto-update: shutting down before install: taskkill failed')
  })
})
