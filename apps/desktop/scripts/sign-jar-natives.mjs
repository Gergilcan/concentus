#!/usr/bin/env node
/**
 * Signs the macOS native libraries INSIDE the staged backend jars.
 *
 * Apple's notary service unpacks every jar in the app and treats each Mach-O it finds as a binary
 * of the app: unsigned, or signed without a secure timestamp, is a critical issue and the whole
 * submission comes back "Invalid". That is not hypothetical — the first signed run failed on
 * exactly four jars: the backend jar itself (pgvector's vector.dylib rides in it as a resource),
 * jna, onnxruntime and tokenizers. electron-builder's signer walks the bundle but never opens a
 * jar, so these have to be signed before it packs the app.
 *
 * Runs on the staged payload (resources/backend), between prepare-payload and electron-builder.
 * .dylib and .jnilib name exactly the macOS natives; the Linux .so and Windows .dll that share
 * these jars are not Mach-O and the notary ignores them.
 *
 * A signing failure fails the build on purpose: the alternative is discovering it fifteen
 * minutes later in a notarization report that this script exists to prevent.
 *
 * usage: node scripts/sign-jar-natives.mjs "Developer ID Application: Name (TEAMID)"
 * (macOS only; the identity must already be in an unlocked keychain codesign can search.)
 */
import { execFileSync } from 'node:child_process'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'

const identity = process.argv[2]
if (!identity) {
  console.error('usage: sign-jar-natives.mjs "<codesign identity>"')
  process.exit(1)
}
if (process.platform !== 'darwin') {
  console.error('sign-jar-natives.mjs only makes sense on macOS — codesign lives there.')
  process.exit(1)
}

const here = path.dirname(fileURLToPath(import.meta.url))
const backend = path.join(here, '..', 'resources', 'backend')
if (!fs.existsSync(backend)) throw new Error(`${backend} is not staged — run prepare-payload first.`)

const jarTool = path.join(process.env.JAVA_HOME ?? '', 'bin', 'jar')
if (!fs.existsSync(jarTool)) throw new Error(`jar tool not found at ${jarTool} — is JAVA_HOME set?`)

/** Every jar in the staged payload: the thin backend jar at the root, its dependencies in lib/. */
const jars = []
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(p)
    else if (entry.name.endsWith('.jar')) jars.push(p)
  }
}
walk(backend)

let signedTotal = 0
for (const jarFile of jars) {
  const entries = execFileSync(jarTool, ['--list', '--file', jarFile], { encoding: 'utf8' })
    .split('\n')
    .map((l) => l.trim())
    .filter((e) => /\.(dylib|jnilib)$/.test(e))
  if (entries.length === 0) continue

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'jar-sign-'))
  execFileSync(jarTool, ['--extract', '--file', jarFile, ...entries], { cwd: tmp })
  for (const entry of entries) {
    // --options runtime and --timestamp are both notarization requirements, --force because the
    // library may carry an ad-hoc or expired signature from whoever built it.
    execFileSync('codesign', [
      '--force', '--timestamp', '--options', 'runtime', '--sign', identity,
      path.join(tmp, entry),
    ], { stdio: 'inherit' })
  }
  // Plain deflate update is fine here: unlike Spring Boot NESTED jars (which must be stored, see
  // prepare-payload), these jars are read by ordinary classloaders and resource lookups.
  execFileSync(jarTool, ['--update', '--file', jarFile, ...entries], { cwd: tmp })
  fs.rmSync(tmp, { recursive: true, force: true })
  console.log(`Signed ${entries.length} native librar${entries.length === 1 ? 'y' : 'ies'} in ${path.basename(jarFile)}`)
  signedTotal += entries.length
}

if (signedTotal === 0) {
  // Not an error by itself — but the four jars named above all carry mac natives today, so zero
  // almost certainly means a renamed dependency, and notarization will name it in its own time.
  console.warn('WARNING: no macOS natives found in any staged jar — expected some in the backend jar, jna, onnxruntime and tokenizers.')
} else {
  console.log(`Signed ${signedTotal} jar-embedded natives as: ${identity}`)
}
