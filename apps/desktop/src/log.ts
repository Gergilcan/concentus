import * as fs from 'node:fs'
import * as path from 'node:path'
import { shellLogFile } from './paths'

/**
 * The shell's log.
 *
 * A packaged app has no terminal attached, so anything not written here is simply lost — including
 * every reason the backend might fail to start, which is the one thing a user will need to report.
 */

let stream: fs.WriteStream | null = null

function out(): fs.WriteStream {
  if (!stream) {
    const file = shellLogFile()
    fs.mkdirSync(path.dirname(file), { recursive: true })
    // Truncated per launch rather than appended: the interesting log is always the current run's,
    // and an unbounded file on a machine nobody administers eventually becomes the problem.
    stream = fs.createWriteStream(file, { flags: 'w' })
  }
  return stream
}

function write(level: string, message: string): void {
  const line = `${new Date().toISOString()} ${level} ${message}\n`
  out().write(line)
  // Still useful when launched from a terminal during development.
  process.stdout.write(line)
}

export const log = {
  info: (message: string) => write('INFO ', message),
  warn: (message: string) => write('WARN ', message),
  error: (message: string, err?: unknown) =>
    write('ERROR', err ? `${message}: ${err instanceof Error ? (err.stack ?? err.message) : String(err)}` : message),
}
