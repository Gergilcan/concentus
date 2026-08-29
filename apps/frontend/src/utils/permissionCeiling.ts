/**
 * The order the CLI's permission modes stand in, least to most permissive — the same list the
 * backend clamps against (`PermissionCeiling.ORDER`), so what the inspector disables is exactly
 * what a run would have clamped.
 */
export const PERMISSION_MODE_ORDER = ['plan', 'default', 'acceptEdits', 'bypassPermissions'] as const

/** Whether `mode` lets an agent do more than `ceiling` allows. A blank ceiling caps nothing. */
export function aboveCeiling(mode: string | null | undefined, ceiling: string | null | undefined): boolean {
  const limit = PERMISSION_MODE_ORDER.indexOf((ceiling ?? '').trim() as (typeof PERMISSION_MODE_ORDER)[number])
  if (limit < 0) return false
  return PERMISSION_MODE_ORDER.indexOf((mode ?? '').trim() as (typeof PERMISSION_MODE_ORDER)[number]) > limit
}
