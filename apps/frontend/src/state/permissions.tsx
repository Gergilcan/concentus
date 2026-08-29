import type { ReactNode } from 'react'
import { PermissionsContext, permissionsFor } from './permissionRules.ts'

/**
 * Hands the signed-in account's permissions to everything under it. What those are, and why the
 * interface asks at all, is in permissionRules.ts — this file exports the component alone so fast
 * refresh can reload it in place.
 */
export function PermissionsProvider({
  role,
  children,
}: {
  role: string | null | undefined
  children: ReactNode
}) {
  return (
    <PermissionsContext.Provider value={permissionsFor(role)}>
      {children}
    </PermissionsContext.Provider>
  )
}
