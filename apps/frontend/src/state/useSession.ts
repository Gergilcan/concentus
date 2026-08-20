import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { SessionInfo, SignedInUser } from '../api/types.ts'

/**
 * Whether this browser may use the API.
 *
 * Asks the backend rather than assuming, because whether sign-in is required at all is a
 * deployment decision: development installs run with it switched off, and those must keep working
 * without a login screen appearing.
 *
 * While the answer is unknown the app renders nothing — showing the workspace first and a sign-in
 * form a moment later would flash data the user may not be entitled to.
 */
export function useSession() {
  const [session, setSession] = useState<SessionInfo | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setSession(await api.session())
    } catch {
      // The endpoint is reachable without a session, so a failure here means the backend is down
      // or misconfigured. Treat it as "sign in": failing closed is the safe direction, and it is
      // deliberately NOT the setup screen — offering to create the first account because the
      // backend was briefly unreachable would be offering to claim an installation that is not
      // empty.
      setSession({ storeAvailable: false, signedIn: false })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const onSignedIn = useCallback((user: SignedInUser) => {
    setSession({
      storeAvailable: true,
      signedIn: true,
      userId: user.userId,
      email: user.email,
      organizationId: user.organizationId,
      role: user.role,
    })
  }, [])

  const signOut = useCallback(async () => {
    try {
      await api.signOut()
    } finally {
      // Reload rather than clearing state in place: every hook holding data fetched under the old
      // session has to be discarded, and a reload is the only way to be sure none is missed.
      window.location.reload()
    }
  }, [])

  return { session, loading, onSignedIn, signOut, refresh }
}
