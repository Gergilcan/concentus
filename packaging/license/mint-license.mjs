#!/usr/bin/env node
/**
 * The enterprise sale is one command and a ledger line. Signs an enterprise Concentus license
 * with the private key that never leaves Gerard's machine, prints the token, appends a JSONL
 * ledger entry next to the key, and optionally emails it via Resend.
 *
 *   node packaging/license/mint-license.mjs --licensee "ACME S.L." --email ops@acme.com \
 *     --seats 20 --months 1 [--send]
 *
 * Env: CONCENTUS_KEYS_DIR (default ~/concentus-keys) holds enterprise.key.pem and the ledger.
 * --send additionally needs RESEND_API_KEY and LICENSE_FROM.
 *
 * `mint()` below is the pure, testable core: sign + ledger, no network. The CLI wrapper (the
 * import.meta.url check at the bottom) is the only thing that touches argv, stdout, and fetch.
 */
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { signLicense } from '../../apps/website/api/_lib/license-format.mjs'

function isoDate(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function positiveInt(name, value) {
  const n = Number(value)
  if (!Number.isInteger(n) || n <= 0) throw new Error(`--${name} must be a positive integer, got: ${value}`)
  return n
}

/**
 * opts: { licensee, email, seats, months?, years? } — exactly one of months/years.
 * env: { keysDir, now } — now defaults to the real clock; tests inject a fixed Date.
 * Returns { token, id, licensee, email, seats, expires, mintedAt, ledgerPath }.
 */
export function mint(opts, { keysDir, now = new Date() }) {
  const { licensee, email, seats, months, years } = opts ?? {}
  if (!licensee) throw new Error('--licensee is required')
  if (!email) throw new Error('--email is required')
  const seatCount = positiveInt('seats', seats)

  const hasMonths = months !== undefined && months !== null
  const hasYears = years !== undefined && years !== null
  if (hasMonths === hasYears) throw new Error('exactly one of --months or --years is required')
  const totalMonths = hasMonths ? positiveInt('months', months) : positiveInt('years', years) * 12

  const keyPath = path.join(keysDir, 'enterprise.key.pem')
  if (!fs.existsSync(keyPath)) {
    throw new Error(
      `Enterprise signing key not found at ${keyPath}. ` +
        'Run "node packaging/license/generate-keys.mjs <directory-outside-any-repo>" once to create it.',
    )
  }
  const privateKeyPem = fs.readFileSync(keyPath, 'utf8')

  const expiresDate = new Date(now)
  expiresDate.setMonth(expiresDate.getMonth() + totalMonths)

  const token = signLicense(
    {
      tier: 'enterprise',
      licensee,
      email,
      seats: seatCount,
      issued: isoDate(now),
      expires: isoDate(expiresDate),
    },
    privateKeyPem,
  )

  const [, segment] = token.split('.')
  const { id } = JSON.parse(Buffer.from(segment, 'base64url').toString())
  const mintedAt = now.toISOString()
  const expires = isoDate(expiresDate)

  const ledgerPath = path.join(keysDir, 'issued-licenses.jsonl')
  fs.appendFileSync(ledgerPath, JSON.stringify({ id, licensee, email, seats: seatCount, expires, mintedAt }) + '\n')

  return { token, id, licensee, email, seats: seatCount, expires, mintedAt, ledgerPath }
}

// ---- CLI wrapper: argv parsing, printing, and the --send path ----
// sendEmail() itself is exported and network-free-testable: it takes an injectable fetchImpl
// (defaulting to globalThis.fetch) and never throws — ANY failure, transport-level (DNS,
// connection refused, TLS, timeout) or an HTTP error response, comes back as { ok: false, ... }
// so the caller always gets a chance to print the token and exit 1 instead of dying uncaught.

function parseArgs(argv) {
  const opts = { send: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    switch (arg) {
      case '--licensee': opts.licensee = argv[++i]; break
      case '--email': opts.email = argv[++i]; break
      case '--seats': opts.seats = argv[++i]; break
      case '--months': opts.months = argv[++i]; break
      case '--years': opts.years = argv[++i]; break
      case '--send': opts.send = true; break
      default: throw new Error(`unknown argument: ${arg}`)
    }
  }
  return opts
}

export async function sendEmail({ token, licensee }, { email, fetchImpl = globalThis.fetch }) {
  try {
    const res = await fetchImpl('https://api.resend.com/emails', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${process.env.RESEND_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: process.env.LICENSE_FROM,
        to: [email],
        subject: 'Your Concentus enterprise license',
        text:
          `Hi,\n\nYour Concentus enterprise license for ${licensee}:\n\n${token}\n\n` +
          'Paste it in Resources -> Settings -> License to activate it.\n',
      }),
    })
    if (res.ok) return { ok: true }
    const body = await res.text().catch(() => '')
    return { ok: false, status: res.status, body }
  } catch (err) {
    // Transport-level failure: DNS, connection refused, TLS, timeout — fetch() rejects rather
    // than resolving with a non-ok response. Treat it the same as an HTTP error: never throw.
    return { ok: false, status: null, body: err.message }
  }
}

async function main() {
  const keysDir = process.env.CONCENTUS_KEYS_DIR || path.join(os.homedir(), 'concentus-keys')

  let opts
  try {
    opts = parseArgs(process.argv.slice(2))
  } catch (err) {
    console.error(err.message)
    process.exitCode = 1
    return
  }

  let result
  try {
    result = mint(opts, { keysDir })
  } catch (err) {
    console.error(err.message)
    process.exitCode = 1
    return
  }

  console.log(result.token)

  if (opts.send) {
    const sent = await sendEmail(result, opts)
    if (!sent.ok) {
      console.error(`Send failed${sent.status ? ` (${sent.status})` : ''}: ${sent.body}`)
      console.error('The token above was NOT emailed — copy it manually and send it yourself.')
      process.exitCode = 1
      return
    }
    console.error(`Emailed to ${opts.email}`)
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main()
}
