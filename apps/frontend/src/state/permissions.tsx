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

// Kept for the components that still import these from here; new code imports permissionRules.ts
// directly. Fast refresh wants a provider file to export only components, hence the exception —
// it goes when the last importer moves.
// eslint-disable-next-line react-refresh/only-export-components
export { deniedReason, permissionsFor, usePermissions, type Permissions } from './permissionRules.ts'
