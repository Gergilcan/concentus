/**
 * The request-reading helpers every function in this directory needs and none should re-derive.
 */

/**
 * Vercel parses req.body for us when the request arrives as application/json, but a form posted
 * with the wrong (or missing) content-type header hands back the raw string instead — or nothing
 * at all. A body we cannot make sense of is just another way to end up with no fields, not a
 * reason to 500: every caller already has an answer for "nothing useful was sent".
 */
export function readBody(req) {
  try {
    const body = req.body
    if (body == null) return {}
    if (typeof body === 'string') return body.trim() ? JSON.parse(body) : {}
    return body
  } catch {
    return {}
  }
}

/**
 * The bytes exactly as they arrived — what a webhook signature was computed over. Only usable
 * from a function that has switched Vercel's body parser off (`export const config = { api: {
 * bodyParser: false } }`): re-serializing a parsed object would change whitespace and key order,
 * and the signature would be right about the body and wrong about ours.
 */
export async function readRawBody(req) {
  if (typeof req.body === 'string') return req.body
  if (Buffer.isBuffer(req.body)) return req.body.toString('utf8')
  const chunks = []
  for await (const chunk of req) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
  return Buffer.concat(chunks).toString('utf8')
}

/** Shaped like an address, nothing more — delivery is the real check, for every issuer here. */
export function looksLikeEmail(value) {
  return typeof value === 'string' && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value)
}

/** The first hop of the forwarded chain, which is what Vercel puts the client's address in. */
export function clientIp(req) {
  return req.headers?.['x-forwarded-for']?.split(',')[0]?.trim() || 'unknown'
}

/**
 * The names in `names` that the environment does not carry (unset or blank) — so an endpoint can
 * answer "not configured: X, Y" instead of stack-tracing on the first undefined it touches.
 */
export function missingEnv(names, env = process.env) {
  return names.filter((name) => env[name] == null || String(env[name]).trim() === '')
}
