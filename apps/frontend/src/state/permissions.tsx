import { createContext, useContext, type ReactNode } from 'react'

/**
 * What the signed-in account may do, in the words the interface needs.
 *
 * <p>The backend is the authority — every rule here is enforced there too, by method and path, so
 * a hand-written request gets the same answer as a click. This exists so the interface does not
 * offer a button that answers 403: a Save that fails after the work is done teaches the user that
 * the app is broken, when the truth is that their account was never meant to save.
 *
 * <p>The ladder mirrors com.concentus.auth.Accounts: VIEWER reads, OPERATOR also runs, MEMBER also
 * edits, ADMIN also administers.
 */
const ROLES = ['VIEWER', 'OPERATOR', 'MEMBER', 'ADMIN'] as const

function rank(role: string | null | undefined): number {
  if (!role) return -1
  return ROLES.indexOf(role.trim().toUpperCase() as (typeof ROLES)[number])
}

export interface Permissions {
  /** Start a run, answer it, stop it, re-run a block. */
  canRun: boolean
  /** Change what the work IS: flows, agents, servers, credentials, knowledge. */
  canEdit: boolean
  /** Manage the organization's members and their roles. */
  canAdminister: boolean
  /** The role's own name, for the one place that should say it out loud. */
  role: string | null
}

/**
 * Everything is allowed where sign-in is switched off — the desktop install and development
 * builds, which have no accounts at all and where a locked-down interface would be a bug rather
 * than a policy.
 */
export function permissionsFor(role: string | null | undefined, authEnabled: boolean): Permissions {
  if (!authEnabled) return { canRun: true, canEdit: true, canAdminister: true, role: null }
  const level = rank(role)
  return {
    canRun: level >= rank('OPERATOR'),
    canEdit: level >= rank('MEMBER'),
    canAdminister: level >= rank('ADMIN'),
    role: role ?? null,
  }
}

/** What to say on a control the role cannot use. Names the role, because "denied" answers nothing. */
export function deniedReason(permissions: Permissions, needed: 'run' | 'edit'): string {
  const role = permissions.role ? permissions.role.toLowerCase() : 'this account'
  return needed === 'run'
    ? `Your role (${role}) can read this flow but not run it. An admin can change that under Resources → Members.`
    : `Your role (${role}) cannot change flows. An admin can change that under Resources → Members.`
}

const PermissionsContext = createContext<Permissions>({
  canRun: true,
  canEdit: true,
  canAdminister: true,
  role: null,
})

export function PermissionsProvider({
  role,
  authEnabled,
  children,
}: {
  role: string | null | undefined
  authEnabled: boolean
  children: ReactNode
}) {
  return (
    <PermissionsContext.Provider value={permissionsFor(role, authEnabled)}>
      {children}
    </PermissionsContext.Provider>
  )
}

export function usePermissions(): Permissions {
  return useContext(PermissionsContext)
}
