import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

const realPlatform = process.platform

/**
 * Pretend to be another OS for one test. Every module under test reads `process.platform` at
 * call time rather than at import time, which is what makes this enough — `path` itself stays
 * the host's, so expectations about joined paths must be built with `path.join` too.
 */
export function setPlatform(platform: NodeJS.Platform): void {
  Object.defineProperty(process, 'platform', { value: platform, configurable: true })
}

export function restorePlatform(): void {
  Object.defineProperty(process, 'platform', { value: realPlatform, configurable: true })
}

/** A scratch directory of the caller's own; remove it in afterEach. */
export function scratchDir(prefix: string): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), `concentus-${prefix}-`))
}

export function removeDir(dir: string): void {
  fs.rmSync(dir, { recursive: true, force: true })
}
