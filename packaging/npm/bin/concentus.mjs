#!/usr/bin/env node
/**
 * `npx concentus` — the desktop app, reached from the tool people already have open.
 *
 * The package deliberately contains no application: 300 MB inside an npm tarball would be a copy
 * the registry keeps forever, per version, and npm is the wrong CDN for it. Instead this fetches
 * the right installer for the platform from the same GitHub release every other channel uses,
 * verifies nothing less than HTTPS + the published size, and runs it. One artifact per release,
 * however many doors lead to it.
 *
 * Honest limits, stated rather than discovered: on macOS the dmg is downloaded and opened, but
 * dragging Concentus into Applications is the user's move — a CLI has no business installing an
 * app bundle. On Linux the AppImage is downloaded and made executable, but sandbox quirks belong
 * to the distro.
 */
import { createWriteStream, existsSync, mkdirSync, chmodSync, statSync } from 'node:fs'
import { get } from 'node:https'
import { arch, homedir, platform } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'

const REPO = 'Gergilcan/concentus'

function fail(message) {
  console.error('\n' + message)
  process.exit(1)
}

/** GitHub API GET with redirects, small and dependency-free. */
function getJson(url) {
  return new Promise((resolve, reject) => {
    const req = get(url, { headers: { 'user-agent': 'concentus-npm-launcher', accept: 'application/vnd.github+json' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return resolve(getJson(res.headers.location))
      }
      let body = ''
      res.on('data', (c) => (body += c))
      res.on('end', () => {
        if (res.statusCode !== 200) return reject(new Error('GitHub answered HTTP ' + res.statusCode))
        try { resolve(JSON.parse(body)) } catch (e) { reject(e) }
      })
    })
    req.on('error', reject)
  })
}

function download(url, to, expectedSize) {
  return new Promise((resolve, reject) => {
    const req = get(url, { headers: { 'user-agent': 'concentus-npm-launcher' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return resolve(download(res.headers.location, to, expectedSize))
      }
      if (res.statusCode !== 200) return reject(new Error('download answered HTTP ' + res.statusCode))
      const out = createWriteStream(to)
      let got = 0
      let lastPct = -10
      res.on('data', (c) => {
        got += c.length
        const pct = Math.floor((got / expectedSize) * 100)
        if (pct >= lastPct + 10) {
          lastPct = pct
          process.stdout.write('\rDownloading… ' + pct + '%   ')
        }
      })
      res.pipe(out)
      out.on('finish', () => {
        process.stdout.write('\rDownloading… done   \n')
        // The size is the published one or the file is not the published file. Not a signature,
        // and not claimed to be one — but it catches a truncated or substituted download.
        const actual = statSync(to).size
        if (expectedSize && actual !== expectedSize) {
          return reject(new Error('size mismatch: expected ' + expectedSize + ', got ' + actual))
        }
        resolve()
      })
      out.on('error', reject)
    })
    req.on('error', reject)
  })
}

const os = platform()
if (os !== 'win32' && os !== 'linux' && os !== 'darwin') {
  fail('Unsupported platform: ' + os)
}

console.log('Concentus — fetching the latest release…')
const release = await getJson('https://api.github.com/repos/' + REPO + '/releases/latest').catch((e) =>
  fail('Could not reach GitHub Releases: ' + e.message),
)

// macOS dmgs are per-architecture, named by the same convention the brew cask reads:
// Concentus-<version>-<arm64|x64>.dmg.
const wanted =
  os === 'win32' ? /^Concentus-Setup-.*\.exe$/
  : os === 'darwin' ? (arch() === 'arm64' ? /^Concentus-.*-arm64\.dmg$/ : /^Concentus-.*-x64\.dmg$/)
  : /^Concentus-.*\.AppImage$/
const asset = release.assets.find((a) => wanted.test(a.name))
if (!asset) fail('The latest release (' + release.tag_name + ') has no installer for this platform.')

const cacheDir = join(
  os === 'win32' ? join(homedir(), 'AppData', 'Local')
  : os === 'darwin' ? join(homedir(), 'Library', 'Caches')
  : join(homedir(), '.cache'),
  'concentus-launcher',
)
mkdirSync(cacheDir, { recursive: true })
const target = join(cacheDir, asset.name)

if (existsSync(target) && statSync(target).size === asset.size) {
  console.log('Already downloaded (' + asset.name + ').')
} else {
  console.log(asset.name + ' (' + Math.round(asset.size / 1024 / 1024) + ' MB) from ' + release.tag_name)
  await download(asset.browser_download_url, target, asset.size).catch((e) => fail('Download failed: ' + e.message))
}

if (os === 'win32') {
  console.log('Starting the installer — it asks where to install and takes it from there.')
  spawn(target, [], { detached: true, stdio: 'ignore' }).unref()
} else if (os === 'darwin') {
  console.log('Opening the disk image — drag Concentus into Applications to install it.')
  spawn('open', [target], { detached: true, stdio: 'ignore' }).unref()
} else {
  chmodSync(target, 0o755)
  console.log('Starting ' + asset.name + ' — it runs in place; move it wherever you keep AppImages.')
  spawn(target, [], { detached: true, stdio: 'ignore' }).unref()
}
