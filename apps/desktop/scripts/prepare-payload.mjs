#!/usr/bin/env node
/**
 * Stages everything the installer has to carry into `resources/`:
 *
 *   resources/jre/                       a Java runtime built with jlink
 *   resources/backend/concentus-backend.jar   the backend, CDS-extracted (thin jar + lib/)
 *   resources/backend/lib/               its dependencies, unpacked from the fat jar
 *
 * All of it is what `paths.ts` looks for in a packaged build, and all of it is gitignored — this
 * script is how it comes to exist. Run it before `electron-builder`, on the platform being built
 * for: a runtime image and the PostgreSQL binaries inside the jar are both native, so there is no
 * cross-building here. A Windows installer is built on Windows, a Linux one on Linux.
 *
 * Why extracted rather than the fat jar: Spring Boot's nested-jar loader reads classes through
 * the outer zip on every launch, and — the part that actually pays — CDS cannot archive classes
 * it loads that way. Extracting once at build time makes every launch skip the nested-jar tax and
 * makes a class archive possible at all.
 *
 * The archive itself is NOT built here, and that is a correction rather than an omission. It used
 * to be, and it never once loaded in an installed build: CDS records each class path entry's
 * timestamp at dump time, packaging rewrites the jar's timestamp afterwards, and the mismatch made
 * every launch reject 60 MB of shipped archive out loud. The shell trains it on the user's machine
 * instead, where the timestamp is settled — see trainCdsArchive in apps/desktop/src/backend.ts.
 */
import { execFileSync } from 'node:child_process'
import * as fs from 'node:fs'
import * as os from 'node:os'
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
    // A jlink image ships with NO class-data-sharing archive unless asked — unlike a stock JDK —
    // so every backend start was re-parsing the JDK's own classes. This bakes the base archive
    // in, and it is also what -XX:ArchiveClassesAtExit requires: the application archive the
    // training run dumps is a delta on top of this one.
    '--generate-cds-archive',
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
  // Always from scratch: a stale lib/ from a previous staging would otherwise shadow renamed or
  // removed dependencies, which is exactly the class of bug extraction must not introduce.
  fs.rmSync(target, { recursive: true, force: true })

  // Prune first, in a scratch copy, so the extracted lib/ below inherits the pruned jars.
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'stage-'))
  const pruned = path.join(work, 'concentus-backend.jar')
  fs.copyFileSync(jar, pruned)
  const mb = (fs.statSync(jar).size / 1024 / 1024).toFixed(1)
  console.log(`Staged backend jar (${mb} MB)`)
  pruneForeignNatives(pruned)

  try {
    // Extraction only. The class archive is NOT trained here and is not shipped: CDS validates
    // every class path entry against the timestamp recorded at dump time, and packaging rewrites
    // this jar's timestamp afterwards — so an archive built now is rejected on every installed
    // launch ("timestamp has changed"), costing 60 MB of installer to carry a file that can never
    // load. The shell trains it once on the user's own machine instead, where the timestamp is
    // final; see trainCdsArchive in apps/desktop/src/backend.ts.
    extractForCds(pruned, target)
  } catch (err) {
    // The fat jar ran for every release before this existed; losing CDS is a slower start, not a
    // broken build.
    console.warn(`WARNING: CDS extraction failed (${err.message}); staging the fat jar instead.`)
    fs.rmSync(target, { recursive: true, force: true })
    fs.mkdirSync(target, { recursive: true })
    fs.copyFileSync(pruned, path.join(target, 'concentus-backend.jar'))
  } finally {
    fs.rmSync(work, { recursive: true, force: true })
  }
}

/**
 * Unpacks the fat jar into the layout Spring Boot's tools mode produces for CDS: a thin
 * `concentus-backend.jar` whose manifest lists `lib/` on its Class-Path. The jar keeps its name —
 * the shell's orphan sweep identifies leftover backends by that name in a java command line.
 */
function extractForCds(fatJar, target) {
  const java = stagedJava()
  run(java, ['-Djarmode=tools', '-jar', fatJar, 'extract', '--destination', target, '--force'])
  const thin = path.join(target, 'concentus-backend.jar')
  if (!fs.existsSync(thin)) throw new Error(`extraction produced no ${thin}`)
}

/** The java of the staged runtime — the one the installer ships and the CDS archive must match. */
function stagedJava() {
  const java = path.join(resources, 'jre', 'bin', exe('java'))
  if (!fs.existsSync(java)) throw new Error(`Staged runtime missing at ${java} — buildRuntime() runs first.`)
  return java
}

/**
 * Strips what this platform's installer cannot use from the ONNX and tokenizer jars.
 *
 * Two kinds of dead weight ride inside them. Debug symbols: onnxruntime ships a 290 MB
 * onnxruntime.pdb next to an 11 MB dll — Windows debug symbols, useless at runtime, carried in
 * every installer on every OS. And foreign platforms: the Windows installer has no use for the
 * macOS and Linux natives, nor Linux for Windows', yet both jars bundle all of them.
 *
 * Uses the JDK's own `jar` tool — extract the nested jar, delete on disk, re-create, and update
 * the outer jar with `-0` (stored), which Spring Boot's loader requires for nested jars. Every
 * step is inside a try/catch that restores the pristine jar on any failure: pruning is an
 * optimization, and a fatter installer beats no installer.
 */
function pruneForeignNatives(stagedJar) {
  const original = fs.readFileSync(stagedJar)
  try {
    const jarTool = path.join(jdkHome(), 'bin', exe('jar'))
    if (!fs.existsSync(jarTool)) throw new Error(`jar tool not found at ${jarTool}`)

    // x64 only, matching what electron-builder produces today. An arm64 build would need its own
    // keep-set — and would fail loudly at model load, not silently, if this went stale.
    const keep = isWindows ? ['win-x64', 'win-x86_64'] : ['linux-x64', 'linux-x86_64']
    const inner = list(jarTool, stagedJar).filter(
      (e) => /BOOT-INF\/lib\/(onnxruntime|tokenizers)-[^/]+\.jar$/.test(e),
    )
    if (inner.length === 0) {
      console.log('No native-bearing jars found to prune (dependency renamed?) — skipping.')
      return
    }

    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'prune-'))
    execFileSync(jarTool, ['--extract', '--file', stagedJar, ...inner], { cwd: tmp })

    for (const entry of inner) {
      const innerJar = path.join(tmp, entry)
      const before = fs.statSync(innerJar).size
      const tree = innerJar + '.d'
      fs.mkdirSync(tree, { recursive: true })
      execFileSync(jarTool, ['--extract', '--file', innerJar], { cwd: tree })

      prune(tree, (p) => {
        if (/\.pdb$/i.test(p) || /\.dSYM/.test(p)) return true
        // By path segment, not by "the directory after native/": the first version of this
        // matched positionally, and regex backtracking let it capture the "lib" in
        // native/lib/tokenizers.properties — deleting the version file the tokenizer refuses to
        // load without. Platform directories are unmistakable by name.
        return p.split(/[\\/]/).some((seg) => /^(win|linux|osx)-/.test(seg) && !keep.includes(seg))
      })

      // -M keeps the extracted MANIFEST.MF as-is instead of jar minting a fresh one.
      fs.rmSync(innerJar)
      execFileSync(jarTool, ['--create', '-M', '--file', innerJar, '-C', tree, '.'])
      const after = fs.statSync(innerJar).size
      console.log(
        `Pruned ${path.basename(entry)}: ${(before / 1048576).toFixed(1)} MB -> ${(after / 1048576).toFixed(1)} MB`,
      )
    }

    // -0 stores the updated entries uncompressed — Spring Boot's loader rejects nested jars that
    // arrive deflated, so this flag is load-bearing, not an optimization knob.
    execFileSync(jarTool, ['--update', '-0', '--file', stagedJar, ...inner], { cwd: tmp })
    fs.rmSync(tmp, { recursive: true, force: true })
    const mb = (fs.statSync(stagedJar).size / 1048576).toFixed(1)
    console.log(`Backend jar after pruning: ${mb} MB`)
  } catch (err) {
    console.warn(`WARNING: native pruning failed (${err.message}); shipping the full jar.`)
    fs.writeFileSync(stagedJar, original)
  }
}

/** Entries of a jar, one per line. */
function list(jarTool, jarFile) {
  return execFileSync(jarTool, ['--list', '--file', jarFile], { encoding: 'utf8' })
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
}

/** Deletes every file under `root` the predicate marks, then any directories left empty. */
function prune(root, doomed) {
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name)
      if (e.isDirectory()) {
        walk(p)
        if (fs.readdirSync(p).length === 0) fs.rmdirSync(p)
      } else if (doomed(path.relative(root, p))) {
        fs.rmSync(p)
      }
    }
  }
  walk(root)
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
