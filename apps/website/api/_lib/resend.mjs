/**
 * The one door every license leaves through. Never throws: a Resend outage, a bad key, a
 * transport failure (DNS, connection refused, TLS, timeout — fetch() rejects rather than
 * resolving) all come back as { ok: false }, logged once here, so each caller decides what that
 * means for ITS answer — the free issuer says its generic line regardless, the Stripe webhook
 * asks Stripe to try again later.
 */
export async function sendEmail({ to, subject, text }) {
  try {
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${process.env.RESEND_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ from: process.env.LICENSE_FROM, to: [to], subject, text }),
    })
    if (res.ok) return { ok: true }
    const body = await res.text().catch(() => '')
    console.error('resend answered', res.status, body)
    return { ok: false, status: res.status, body }
  } catch (err) {
    console.error('resend send failed', err)
    return { ok: false, status: null, body: err?.message ?? String(err) }
  }
}
