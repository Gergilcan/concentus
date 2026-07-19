import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, RunEvent } from './types.ts'
import { api, openRunSocket } from './client.ts'

/** Minimal stand-in for the `fetch` Response object, only implementing what `req()` reads. */
function okResponse(body: unknown, status = 200, statusText = 'OK') {
  return {
    ok: true,
    status,
    statusText,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as Response
}

function noBodyResponse(status = 204, statusText = 'No Content') {
  const text = vi.fn(async () => '')
  return { ok: true, status, statusText, text } as unknown as Response & { text: typeof text }
}

function errorResponse(status: number, statusText: string, jsonImpl: () => Promise<unknown>) {
  return { ok: false, status, statusText, json: jsonImpl } as Response
}

// Every `api.*` call funnels through the shared `req()` helper in client.ts. These tests
// exercise that helper indirectly through the public `api` surface (req itself isn't exported).
describe('api request construction', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('GETs are made against /api<path> with a JSON content-type header and no method/body', async () => {
    fetchMock.mockResolvedValue(okResponse([]))
    await api.listFlows()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/flows')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(init.method).toBeUndefined()
    expect(init.body).toBeUndefined()
    expect(init.signal).toBeInstanceOf(AbortSignal)
  })

  it('interpolates path params, e.g. getFlow(id) -> /api/flows/:id', async () => {
    fetchMock.mockResolvedValue(okResponse({ id: 'abc', name: 'f', mode: 'local', nodes: [], edges: [] }))
    await api.getFlow('abc')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/flows/abc')
  })

  it('POSTs send the method and a JSON-stringified body', async () => {
    const flow: BackendFlow = { name: 'My Flow', mode: 'local', nodes: [], edges: [] }
    fetchMock.mockResolvedValue(okResponse(flow))
    await api.saveFlow(flow)

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/flows')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify(flow))
    // Content-Type must survive even though `init` (method/body) is spread over the defaults.
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
  })

  it('DELETEs use the DELETE method with no body', async () => {
    fetchMock.mockResolvedValue(noBodyResponse())
    await api.deleteFlow('flow-1')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/flows/flow-1')
    expect(init.method).toBe('DELETE')
    expect(init.body).toBeUndefined()
  })
})

// A prior story replaced a silent `.catch(() => [])` with real error propagation. These tests
// guard that regression: callers must see fetch/network failures, not a swallowed empty result.
describe('api error propagation', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('propagates a network failure (fetch rejects) instead of swallowing it into a default value', async () => {
    const networkError = new TypeError('Failed to fetch')
    fetchMock.mockRejectedValue(networkError)

    await expect(api.listFlows()).rejects.toBe(networkError)
  })

  it('propagates a network failure for other verbs too (POST)', async () => {
    const networkError = new TypeError('Failed to fetch')
    fetchMock.mockRejectedValue(networkError)
    const flow: BackendFlow = { name: 'f', mode: 'local', nodes: [], edges: [] }

    await expect(api.saveFlow(flow)).rejects.toBe(networkError)
  })
})

// Non-2xx handling: prefer a backend-supplied `{ error }` message, fall back to
// "<status> <statusText>" when the body isn't JSON (or has no `error` field).
describe('api non-2xx handling', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('throws the backend-supplied message when the error body has an `error` field', async () => {
    fetchMock.mockResolvedValue(errorResponse(400, 'Bad Request', async () => ({ error: 'flow name is required' })))
    await expect(api.listFlows()).rejects.toThrow('flow name is required')
  })

  it('falls back to "<status> <statusText>" when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue(
      errorResponse(500, 'Internal Server Error', async () => {
        throw new SyntaxError('Unexpected token o in JSON')
      }),
    )
    await expect(api.listFlows()).rejects.toThrow('500 Internal Server Error')
  })

  it('falls back to "<status> <statusText>" when the JSON body has no `error` field', async () => {
    fetchMock.mockResolvedValue(errorResponse(404, 'Not Found', async () => ({ message: 'nope' })))
    await expect(api.listFlows()).rejects.toThrow('404 Not Found')
  })
})

// Success-path body handling: 204/empty bodies resolve to undefined without parsing,
// and a normal JSON body round-trips through res.text() -> JSON.parse.
describe('api response body handling', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('resolves to undefined for a 204 response without reading the body', async () => {
    const res = noBodyResponse()
    fetchMock.mockResolvedValue(res)

    await expect(api.deleteFlow('id')).resolves.toBeUndefined()
    expect(res.text).not.toHaveBeenCalled()
  })

  it('resolves to undefined for a 200 response with an empty text body', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 200, statusText: 'OK', text: async () => '' })
    await expect(api.deleteFlow('id')).resolves.toBeUndefined()
  })

  it('parses a normal JSON 200 body', async () => {
    const flows: BackendFlow[] = [{ id: '1', name: 'f', mode: 'local', nodes: [], edges: [] }]
    fetchMock.mockResolvedValue({ ok: true, status: 200, statusText: 'OK', text: async () => JSON.stringify(flows) })

    await expect(api.listFlows()).resolves.toEqual(flows)
  })
})

// Timeout/abort behavior: `req()` races fetch against a 30s AbortController timer. On abort
// it must surface a descriptive timeout error (not a raw AbortError), and must always clear
// the timer so nothing keeps the process alive after the call settles.
describe('api request timeout / abort', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.useFakeTimers()
    fetchMock = vi.fn(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted', 'AbortError'))
          })
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('aborts and rejects with a descriptive timeout error once the default 30s elapses', async () => {
    const promise = api.listFlows()
    const assertion = expect(promise).rejects.toThrow('Request to /flows timed out after 30000ms')
    await vi.advanceTimersByTimeAsync(30_000)
    await assertion
  })

  it('does not fire the abort before the timeout elapses', async () => {
    const promise = api.listFlows()
    // Attach the rejection handler up front so the eventual (expected) rejection is never
    // briefly "unhandled" between the abort firing and this assertion awaiting it.
    const assertion = expect(promise).rejects.toThrow(/timed out/)
    await vi.advanceTimersByTimeAsync(29_999)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    await assertion
  })

  it('leaves no pending timer once a response resolves before the timeout', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okResponse([])))
    await api.listFlows()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('leaves no pending timer once a request rejects with a non-abort error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(api.listFlows()).rejects.toThrow('Failed to fetch')
    expect(vi.getTimerCount()).toBe(0)
  })
})

// openRunSocket: reconnect-with-backoff logic around a live run's WebSocket stream.
// Verified against a minimal in-memory WebSocket stand-in (jsdom doesn't implement one).
describe('openRunSocket', () => {
  class MockWebSocket {
    static instances: MockWebSocket[] = []
    url: string
    onopen: (() => void) | null = null
    onmessage: ((e: { data: string }) => void) | null = null
    onerror: (() => void) | null = null
    onclose: (() => void) | null = null
    closeCalls = 0

    constructor(url: string) {
      this.url = url
      MockWebSocket.instances.push(this)
    }

    close() {
      this.closeCalls += 1
      this.onclose?.()
    }
  }

  beforeEach(() => {
    vi.useFakeTimers()
    MockWebSocket.instances = []
    vi.stubGlobal('WebSocket', MockWebSocket)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('opens a ws:// URL scoped to the run id and reports "connecting" first', () => {
    const statuses: string[] = []
    const handle = openRunSocket('run-1', () => {}, (s) => statuses.push(s))

    expect(MockWebSocket.instances).toHaveLength(1)
    expect(MockWebSocket.instances[0].url.startsWith('ws://')).toBe(true)
    expect(MockWebSocket.instances[0].url).toContain('runId=run-1')
    expect(statuses).toEqual(['connecting'])

    handle.close()
  })

  it('delivers parsed events to onEvent and silently drops malformed frames', () => {
    const events: RunEvent[] = []
    const handle = openRunSocket('run-1', (e) => events.push(e))
    const ws = MockWebSocket.instances[0]

    ws.onmessage?.({ data: 'not-json{{{' })
    ws.onmessage?.({ data: JSON.stringify({ type: 'system', text: 'hello', ts: 1 }) })

    expect(events).toEqual([{ type: 'system', text: 'hello', ts: 1 }])
    handle.close()
  })

  it('reconnects with capped exponential backoff after an unexpected close', async () => {
    const statuses: string[] = []
    const handle = openRunSocket('run-1', () => {}, (s) => statuses.push(s))

    MockWebSocket.instances[0].onclose?.()
    expect(statuses).toEqual(['connecting', 'reconnecting'])
    expect(MockWebSocket.instances).toHaveLength(1) // no reconnect attempt yet, only scheduled

    // first backoff is 1000ms +/-25% jitter; 1250ms covers the worst case
    await vi.advanceTimersByTimeAsync(1250)
    expect(MockWebSocket.instances).toHaveLength(2)
    expect(statuses.at(-1)).toBe('reconnecting')

    handle.close()
  })

  it('resets the backoff attempt counter once a reconnection succeeds (onopen)', async () => {
    const statuses: string[] = []
    const handle = openRunSocket('run-1', () => {}, (s) => statuses.push(s))

    MockWebSocket.instances[0].onclose?.()
    await vi.advanceTimersByTimeAsync(1250)
    MockWebSocket.instances[1].onopen?.()
    expect(statuses.at(-1)).toBe('open')

    // drop again: since attempt was reset to 0 by onopen, the next backoff is base (1000ms +/-25%)
    MockWebSocket.instances[1].onclose?.()
    await vi.advanceTimersByTimeAsync(1250)
    expect(MockWebSocket.instances).toHaveLength(3)

    handle.close()
  })

  it('stops reconnecting once a "status: terminated" event is observed', async () => {
    const statuses: string[] = []
    const handle = openRunSocket('run-1', () => {}, (s) => statuses.push(s))
    const ws = MockWebSocket.instances[0]

    ws.onmessage?.({ data: JSON.stringify({ type: 'status', text: 'terminated', ts: 1 }) })
    ws.onclose?.()

    expect(statuses.at(-1)).toBe('disconnected')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(MockWebSocket.instances).toHaveLength(1)

    handle.close()
  })

  it('treats a reported "error" event as terminal too (no follow-up "terminated" required)', async () => {
    const handle = openRunSocket('run-1', () => {})
    const ws = MockWebSocket.instances[0]

    ws.onmessage?.({ data: JSON.stringify({ type: 'error', text: 'boom', ts: 1 }) })
    ws.onclose?.()

    await vi.advanceTimersByTimeAsync(30_000)
    expect(MockWebSocket.instances).toHaveLength(1)

    handle.close()
  })

  it('close() suppresses further reconnects and cancels a pending reconnect timer', async () => {
    const handle = openRunSocket('run-1', () => {})
    MockWebSocket.instances[0].onclose?.() // schedules a reconnect

    handle.close()
    await vi.advanceTimersByTimeAsync(5_000)

    expect(MockWebSocket.instances).toHaveLength(1) // reconnect never fired
  })
})
