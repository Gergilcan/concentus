/**
 * Minting a team license — the tier the website issues by itself, for money (the Stripe webhook)
 * or for fourteen days (the trial form). Both doors sign with TEAM_SIGNING_KEY, and both are
 * bounded by what the backend verifier will accept from that key: at most MAX_TEAM_SEATS seats,
 * always an expiry date. Nothing here can mint an enterprise license; that key is not in Vercel.
 */
import { signLicense } from './license-format.mjs'
import { MAX_TEAM_SEATS } from './pricing.mjs'

/** How long a trial runs, and the number on the button. */
export const TRIAL_DAYS = 14

/** Calendar dates are UTC throughout this directory: a serverless function has no local zone worth trusting. */
export function isoDate(date) {
  return date.toISOString().slice(0, 10)
}

/** Same month arithmetic as the enterprise CLI: Jan 31 + 1 month overflows into March, as JS does. */
export function addMonthsUtc(date, months) {
  const d = new Date(date)
  d.setUTCMonth(d.getUTCMonth() + months)
  return d
}

export function addDaysUtc(date, days) {
  const d = new Date(date)
  d.setUTCDate(d.getUTCDate() + days)
  return d
}

/** When a bought license ends: one month or twelve from today. Never null — a team license expires. */
export function expiryForTerm(now, term) {
  return isoDate(addMonthsUtc(now, term === 'annual' ? 12 : 1))
}

/**
 * Signs a team license. `seats` is trusted to be inside the ceiling by the time it gets here —
 * the callers parse it — but the check is repeated, because this is the last line before a key
 * touches it and the ceiling is the whole reason the key may live where it lives.
 */
export function mintTeamLicense({ licensee, email, seats, expires, trial = false }, signingKey, now = new Date()) {
  if (!Number.isInteger(seats) || seats < 1 || seats > MAX_TEAM_SEATS) {
    throw new Error(`a team license covers 1 to ${MAX_TEAM_SEATS} seats, not ${seats}`)
  }
  if (!expires) throw new Error('a team license must expire')
  return signLicense(
    {
      tier: 'team',
      licensee: String(licensee).trim(),
      email: String(email).trim(),
      seats,
      issued: isoDate(now),
      expires,
      // Only present when true: an ordinary team license carries no `trial` field at all, so
      // the payload of a bought license is exactly what it was before trials existed.
      ...(trial ? { trial: true } : {}),
    },
    signingKey,
  )
}

/** The email a buyer gets: the token, where it goes, and when it ends — the renewal is the same card again. */
export function purchaseEmail({ licensee, token, seats, term, expires }) {
  return {
    subject: 'Your Concentus team license',
    text:
      `Hi,\n\nYour Concentus team license for ${licensee} — ${seats} seat${seats === 1 ? '' : 's'}, `
      + `${term === 'annual' ? 'one year' : 'one month'}, until ${expires}:\n\n${token}\n\n`
      + 'Paste it in Resources → Settings → License to activate the shared database, team members '
      + 'and SSO. Every member of the team pastes the same token.\n\n'
      + `It keeps working for 14 days past ${expires}; to renew, buy again at `
      + 'https://www.concentus-ai.com/#license — the new token replaces this one.\n',
  }
}

/** The email a trial gets: the same token shape, with the date that matters up front. */
export function trialEmail({ licensee, token, seats, expires }) {
  return {
    subject: 'Your Concentus 14-day trial',
    text:
      `Hi,\n\nYour Concentus trial license for ${licensee} — ${seats} seat${seats === 1 ? '' : 's'}, `
      + `until ${expires}:\n\n${token}\n\n`
      + 'Paste it in Resources → Settings → License to try the shared database, team members and '
      + 'SSO. Every member of the team pastes the same token.\n\n'
      + `After ${expires} it keeps working for 14 more days, then the installation drops back to `
      + 'one seat — nothing is deleted. Team licenses (up to 10 seats) and enterprise are at '
      + 'https://www.concentus-ai.com/#license\n',
  }
}
