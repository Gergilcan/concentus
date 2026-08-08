import * as http from 'node:http'

/**
 * Calls the backend from the main process.
 *
 * <p>The first-run window could talk to the backend directly — it is a web page and the backend is
 * on loopback — but that would mean relaxing its content-security policy from "no network at all"
 * to "may reach this origin", for a page whose whole job is to be a fixed form. Routing the two
 * calls it needs through the main process keeps the page inert.
 */

export interface StorageConfig {
  mode: 'embedded' | 'external'
  url: string
  username: string
  hasPassword: boolean
  activeMode: 'embedded' | 'external'
  restartRequired?: boolean
}

export interface StorageDraft {
  mode: 'embedded' | 'external'
  url: string
  username: string
  password: string | null
}

function request<T>(port: number, path: string, method: string, body?: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? undefined : JSON.stringify(body)
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path,
        method,
        // Generous: a connection test against an unreachable host waits out its own timeout in the
        // backend, and cutting it short here would report a failure the backend never saw.
        timeout: 30_000,
        headers: payload
          ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }
          : {},
      },
      (res) => {
        let data = ''
        res.setEncoding('utf8')
        res.on('data', (chunk) => (data += chunk))
        res.on('end', () => {
          if (!res.statusCode || res.statusCode >= 400) {
            // The API reports a rejected value as {"error": "..."}; surface that rather than a
            // status code, because it is written to be read by the person who typed the value.
            let message = `HTTP ${res.statusCode}`
            try {
              const parsed = JSON.parse(data) as { error?: string }
              if (parsed.error) message = parsed.error
            } catch {
              /* keep the status code */
            }
            reject(new Error(message))
            return
          }
          try {
            resolve((data ? JSON.parse(data) : {}) as T)
          } catch (err) {
            reject(err instanceof Error ? err : new Error(String(err)))
          }
        })
      },
    )
    req.on('error', reject)
    req.on('timeout', () => {
      req.destroy()
      reject(new Error('The backend did not answer in time.'))
    })
    if (payload) req.write(payload)
    req.end()
  })
}

export const backendApi = {
  getStorage: (port: number) => request<StorageConfig>(port, '/api/storage', 'GET'),
  testStorage: (port: number, draft: StorageDraft) =>
    request<{ ok: boolean; detail: string }>(port, '/api/storage/test', 'POST', draft),
  saveStorage: (port: number, draft: StorageDraft) =>
    request<StorageConfig>(port, '/api/storage', 'PUT', draft),
}
