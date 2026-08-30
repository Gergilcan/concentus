import type { PERMISSION_MODE_ORDER } from '../utils/permissionCeiling.ts'

/**
 * What each permission mode lets an agent do, for the ceiling pickers — the coordinator's own
 * wording. In a file of its own because two panels read it: the organization's policy and a
 * group's, and a component file may export only components if fast refresh is to keep working.
 */
export const MODE_LABEL: Record<(typeof PERMISSION_MODE_ORDER)[number], string> = {
  plan: 'Plan only — proposes, changes nothing',
  default: 'Ask — prompts before each sensitive action',
  acceptEdits: 'Auto-accept file edits, ask for the rest',
  bypassPermissions: 'Bypass all checks',
}
