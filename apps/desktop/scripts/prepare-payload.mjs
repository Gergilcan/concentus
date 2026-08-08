#!/usr/bin/env node
/**
 * Stages everything the installer has to carry into `resources/`:
 *
 *   resources/jre/                       a Java runtime built with jlink
 *   resources/backend/concentus-backend.jar
 *
 * Both are what `paths.ts` looks for in a packaged build, and both are gitignored — this script is
 * how they come to exist. Run it before `electron-builder`, on the platform being built for: a
 * runtime image and the PostgreSQL binaries inside the jar are both native, so there is no
 * cross-building here. A Windows installer is built on Windows, a Linux one on Linux.
 */
import { execFileSync } from 'node:child_process'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const desktopDir = path.join(here, '..')
const repoRoot = path.join(desktopDir, '..', '..')
const resources = path.join(desktopDir, 'resources')

const isWindows = process.platform === 'win32'
const exe = (name) => (isWindows ? `${name}.exe` : name)

const force = process.argv.includes('--force')

/** The JDK to build the runtime from. Must be the same major version the backend targets (25). */
function jdkHome() {
  if (process.env.JAVA_HOME && fs.existsSync(process.env.JAVA_HOME)) return process.env.JAVA_HOME
  throw new Error('JAVA_HOME is not set. Point it at a JDK 25 installation and re-run.')
}

function run(command, args) {
  console.log(`> ${command} ${args.join(' ')}`)
  execFileSync(command, args, { stdio: 'inherit' })
}

function buildRuntime() {
  const target = path.join(resources, 'jre')
  if (fs.existsSync(target) && !force) {
    console.log(`Runtime already staged at ${target} (pass --force to rebuild).`)
    return
  }
  fs.rmSync(target, { recursive: true, force: true })

  const home = jdkHome()
  const jlink = path.join(home, 'bin', exe('jlink'))
  if (!fs.existsSync(jlink)) throw new Error(`jlink not found at ${jlink}`)

  // ALL-MODULE-PATH rather than a hand-picked module list.
  //
  // The tempting alternative is to run jdeps and include only what it reports, which produces a
  // meaningfully smaller image. It is also unsound here: jdeps reads bytecode references, and this
  // application finds most of its classes reflectively — Spring's component scan, JDBC driver
  // registration, JNA's native binding in tess4j, the crypto providers TLS selects at runtime.
  // A module missed that way does not fail the build; it fails months later on a user's machine,
  // in whichever feature happened to need it.
  //
  // The cost of being safe is roughly 30-40MB in an installer already measured in hundreds. If
  // that ever matters, trim with a curated list and test every feature — not with jdeps output.
  // ALL-MODULE-PATH resolves against --module-path, not against the running JDK, so the JDK's own
  // jmods directory has to be named explicitly.
  //
  // Not every distribution ships it — some Temurin builds do not, which is how this first showed
  // up: a release failing on a runner after the jar had already been built. Rather than stop, fall
  // back to shipping the JDK as it is. The result is a working app in a larger installer, which is
  // a far better outcome than no installer at all, and the warning says what happened.
  const jmods = path.join(home, 'jmods')
  if (!fs.existsSync(jmods)) {
    console.warn(
      `\nWARNING: ${jmods} does not exist, so jlink cannot trim a runtime image.\n` +
      `Copying the whole JDK instead — the app will work, but the installer will be tens of\n` +
      `megabytes larger. Use a distribution that ships jmods (Zulu, Liberica, Oracle) to avoid this.\n`,
    )
    copyDirectory(home, target)
    const staged = path.join(target, 'bin', exe('java'))
    if (!fs.existsSync(staged)) throw new Error(`Copied ${home} but ${staged} is missing.`)
    console.log(`Staged the full JDK from ${home}`)
    return
  }

  run(jlink, [
    '--module-path', jmods,
    '--add-modules', 'ALL-MODULE-PATH',
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress', 'zip-9',
    '--output', target,
  ])

  // Prove the staged runtime actually runs before an installer is built around it.
  const staged = path.join(target, 'bin', exe('java'))
  // `--version` (two dashes) writes to stdout; the older `-version` writes to stderr and would
  // leave this looking like it produced nothing.
  const version = execFileSync(staged, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] })
  console.log(`Staged runtime: ${version.split('\n')[0].trim()}`)
}

/**
 * Recursive copy, for the fallback above.
 *
 * <p>fs.cpSync rather than a shell command so it behaves the same on both platforms; the JDK is
 * tens of thousands of small files and a per-file loop here would be noticeably slower.
 */
function copyDirectory(from, to) {
  fs.mkdirSync(to, { recursive: true })
  fs.cpSync(from, to, { recursive: true, dereference: true })
}

function stageJar() {
  const jar = path.join(repoRoot, 'apps', 'backend', 'target', 'concentus-backend.jar')
  if (!fs.existsSync(jar)) {
    throw new Error(`Backend jar not found at ${jar}. Build it first: pnpm desktop:build`)
  }
  const target = path.join(resources, 'backend')
  fs.mkdirSync(target, { recursive: true })
  fs.copyFileSync(jar, path.join(target, 'concentus-backend.jar'))
  const mb = (fs.statSync(jar).size / 1024 / 1024).toFixed(1)
  console.log(`Staged backend jar (${mb} MB)`)
}

function report() {
  const size = (dir) => {
    let total = 0
    for (const entry of fs.readdirSync(dir, { withFileTypes: true, recursive: true })) {
      if (entry.isFile()) total += fs.statSync(path.join(entry.parentPath ?? entry.path, entry.name)).size
    }
    return (total / 1024 / 1024).toFixed(1)
  }
  console.log(`\nPayload staged in ${resources} — ${size(resources)} MB total.`)
}

fs.mkdirSync(resources, { recursive: true })
buildRuntime()
stageJar()
report()
