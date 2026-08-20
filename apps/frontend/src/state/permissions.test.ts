import { describe, expect, it } from 'vitest'
import { deniedReason, permissionsFor } from './permissions.tsx'

/**
 * What the interface is allowed to offer.
 *
 * <p>Every rule here is enforced on the backend too, by method and path. This exists so a button
 * that would answer 403 is never offered: a Save that fails after the work is done teaches the
 * user that the app is broken, when the truth is that their account was never meant to save.
 */
describe('permissions', () => {
  it('lets a viewer read and nothing else', () => {
    const p = permissionsFor('VIEWER', true)
    expect(p).toMatchObject({ canRun: false, canEdit: false, canAdminister: false })
  })

  // The split that matters in an organization: the person who runs the daily cycle is rarely the
  // person who should be free to rewrite what it does to the ad account.
  it('lets an operator run without letting it edit', () => {
    const p = permissionsFor('OPERATOR', true)
    expect(p).toMatchObject({ canRun: true, canEdit: false, canAdminister: false })
  })

  it('lets a member edit, and an admin administer', () => {
    expect(permissionsFor('MEMBER', true)).toMatchObject({ canRun: true, canEdit: true, canAdminister: false })
    expect(permissionsFor('ADMIN', true)).toMatchObject({ canRun: true, canEdit: true, canAdminister: true })
  })

  // The desktop install has no accounts at all; a locked interface there would be a bug, not a
  // policy.
  it('allows everything where sign-in is switched off', () => {
    expect(permissionsFor(null, false)).toMatchObject({ canRun: true, canEdit: true, canAdminister: true })
    expect(permissionsFor('VIEWER', false).canEdit).toBe(true)
  })

  it('treats an unknown role as the least privileged, rather than as an admin', () => {
    expect(permissionsFor('editor', true)).toMatchObject({ canRun: false, canEdit: false })
    expect(permissionsFor(undefined, true).canRun).toBe(false)
  })

  it('is not case-sensitive about a role that came back from the backend', () => {
    expect(permissionsFor('operator', true).canRun).toBe(true)
  })

  // "Denied" answers nothing. The message names the role and where it is changed.
  it('says which role is refusing and who can change it', () => {
    const reason = deniedReason(permissionsFor('VIEWER', true), 'run')
    expect(reason).toContain('viewer')
    expect(reason).toContain('Members')
  })
})
