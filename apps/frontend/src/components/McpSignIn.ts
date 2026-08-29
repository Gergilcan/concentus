import { api } from '../api/client.ts'

/**
 * Starting the app's own OAuth sign-in for an MCP server. Shared by the inspector's sign-in
 * button and the tool picker's dialog, which offers this sign-in when a server answers 401 —
 * and kept out of the component file so that file keeps fast refresh.
 */

const isLoopback = (host: string) =>
  host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1'

/**
 * Whether the browser will NOT be able to reach the sign-in's callback.
 *
 * The callback is handled by the BACKEND, not by this page, so origin equality is not the test —
 * a dev UI on localhost:5173 with the backend on 127.0.0.1:8734 works fine, and so does a
 * backend on its own api. host. The one setup that genuinely dies is a loopback callback used
 * from another machine: the provider sends the browser to ITS OWN localhost, where nothing
 * listens, after the code has been issued and spent. That is the only case flagged.
 */
export function callbackUnreachable(redirectUri: string | undefined, page: { origin: string; hostname: string }): boolean {
  if (!redirectUri || redirectUri.startsWith(page.origin)) return false
  try {
    return isLoopback(new URL(redirectUri).hostname) && !isLoopback(page.hostname)
  } catch {
    // A malformed redirect URI will fail loudly at the provider; guessing here adds nothing.
    return false
  }
}

/**
 * How a helper outside any component says a sentence in the user's language: the caller lends it
 * a translate function. The default speaks English, so non-component callers (and old tests)
 * still get the message verbatim.
 */
type Translate = (key: string, options?: Record<string, unknown>) => string

const plainTranslate: Translate = (key, options) =>
  key.replace(/\{\{(\w+)\}\}/g, (match, name) =>
    options && name in options ? String(options[name]) : match,
  )

/**
 * Starts the app's own OAuth sign-in for an MCP server and opens the approval tab.
 * Returns an error message, or null when the tab is open and approval is now up to the user.
 */
export async function beginMcpSignIn(url: string, t: Translate = plainTranslate): Promise<string | null> {
  const started = await api.startMcpOAuth(url)
  if (!started.ok || !started.authorizationUrl) {
    return started.error ?? t('This server could not be signed in to.')
  }
  if (callbackUnreachable(started.redirectUri, window.location)) {
    return t(
      "This sign-in would return to {{redirectUri}}, which only exists on the backend's own machine — you are using Concentus from {{origin}}. Set MCP_OAUTH_REDIRECT_BASE={{origin}} and restart the backend.",
      { redirectUri: started.redirectUri, origin: window.location.origin },
    )
  }
  // A new tab, not a redirect: the canvas has unsaved state.
  window.open(started.authorizationUrl, '_blank', 'noopener')
  return null
}
