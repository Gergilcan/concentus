import { describe, expect, it, vi } from 'vitest'
import { callbackUnreachable } from './McpOAuthConnect.tsx'

vi.mock('../api/client.ts', () => ({ api: {} }))

// The callback is handled by the BACKEND, so origin equality is not the test. The one setup that
// genuinely dies is a loopback callback used from another machine: the provider sends that
// browser to its own localhost, where nothing listens.
describe('callbackUnreachable', () => {
  const page = (origin: string) => ({ origin, hostname: new URL(origin).hostname })

  it('accepts a same-origin callback', () => {
    expect(callbackUnreachable('http://127.0.0.1:8734/api/mcp/oauth/callback', page('http://127.0.0.1:8734'))).toBe(false)
  })

  it('accepts a loopback callback from a loopback page — the dev setup (Vite + backend)', () => {
    expect(callbackUnreachable('http://127.0.0.1:8734/api/mcp/oauth/callback', page('http://localhost:5173'))).toBe(false)
  })

  it('rejects a loopback callback used from another machine', () => {
    expect(callbackUnreachable('http://127.0.0.1:8734/api/mcp/oauth/callback', page('https://concentus.acme.com'))).toBe(true)
  })

  it('accepts a public callback host, wherever the page is', () => {
    expect(callbackUnreachable('https://api.acme.com/api/mcp/oauth/callback', page('https://concentus.acme.com'))).toBe(false)
  })

  it('lets a malformed redirect fail loudly at the provider instead of guessing here', () => {
    expect(callbackUnreachable('not a url', page('https://concentus.acme.com'))).toBe(false)
  })
})
