import type { BackendFlow, FacadeProfile } from '../api/types.ts'

/**
 * Turning a flow into a sandbox copy: the same flow, arranged so a run of it proposes instead of
 * acting.
 *
 * Two independent mechanisms, because there are two ways a flow touches the world and neither one
 * covers the other:
 *
 * - **Plan mode**, set on the coordinator, governs the CLI's own tools. Edits, writes and commands
 *   are proposed rather than made. This is what makes the copy safe for a coordinator that works
 *   on files.
 * - **Dry-run facades** govern what independent workers reach over MCP: write-shaped tools answer
 *   "DRY RUN" instead of executing, enforced by the backend on every call.
 *
 * What neither covers is stated where the user can see it, not just here: an MCP server a
 * coordinator reaches DIRECTLY — not through a worker's facade — is not simulated by either. The
 * facade is the enforcement point, and a single-session coordinator does not go through one.
 */

/** The suffix that makes a sandbox recognisable in a list of flows. */
export const SANDBOX_SUFFIX = '(sandbox)'
export const SANDBOX_TAG = 'sandbox'

/** What a sandboxed copy of a facade profile is called. Stable, so re-sandboxing reuses it. */
export function sandboxProfileName(name: string): string {
  return `${name} ${SANDBOX_SUFFIX}`
}

/** The facade profiles this flow's agents actually reference, by id. */
export function referencedProfileIds(flow: BackendFlow): string[] {
  const ids = new Set<string>()
  for (const node of flow.nodes) {
    const id = (node.data as { facadeProfileId?: unknown }).facadeProfileId
    if (typeof id === 'string' && id.trim() !== '') ids.add(id.trim())
  }
  return [...ids]
}

/**
 * Whether a node is the flow's coordinator. Checked in both places it can be recorded: flows
 * saved from the canvas carry the role in the node's data, bundled samples carry it on the node.
 */
function isCoordinator(node: BackendFlow['nodes'][number]): boolean {
  return node.role === 'coordinator' || (node.data as { role?: unknown }).role === 'coordinator'
}

/** A dry-run twin of a profile: same tools, writes simulated rather than blocked. */
export function sandboxProfileOf(profile: FacadeProfile): FacadeProfile {
  return {
    ...profile,
    id: undefined,
    name: sandboxProfileName(profile.name),
    description: `Dry-run twin of "${profile.name}" — write-shaped tools answer DRY RUN.`,
    // Not read-only: a sandbox is for watching the flow DO its work, and a blocked call teaches
    // less than a simulated one. Dry-run is the mode that still exercises the path.
    readOnly: false,
    dryRun: true,
  }
}

/**
 * The sandbox copy of a flow.
 *
 * Saved paused, whatever the original was: a sandbox is something you run on purpose to watch,
 * and a copy that quietly inherited a cron would start firing twice as often as its owner asked
 * for. The id is dropped so saving creates rather than overwrites.
 */
export function sandboxFlow(flow: BackendFlow, profileIds: Record<string, string>): BackendFlow {
  const nodes = flow.nodes.map((node) => {
    const data = { ...(node.data as Record<string, unknown>) }
    const facade = data.facadeProfileId
    if (typeof facade === 'string' && profileIds[facade]) {
      data.facadeProfileId = profileIds[facade]
    }
    // Plan mode goes on BOTH the coordinator and the trigger, and that is not belt-and-braces.
    // The coordinator's value wins when both are set, so writing only the trigger would be
    // silently overridden by any flow that has one on its coordinator — a sandbox that quietly
    // still acts. And the backend identifies the coordinator by its node DATA, which a flow
    // imported from a bundled sample may not carry; the trigger is what it falls back to. Setting
    // both can only ever make the copy more restrictive, which is the safe direction to be wrong.
    if (node.type === 'input') data.permissionMode = 'plan'
    if (node.type === 'agent' && isCoordinator(node)) data.permissionMode = 'plan'
    return { ...node, data }
  })

  const tags = flow.tags ?? []
  return {
    ...flow,
    id: undefined,
    name: `${flow.name} ${SANDBOX_SUFFIX}`,
    tags: tags.includes(SANDBOX_TAG) ? tags : [...tags, SANDBOX_TAG],
    enabled: false,
    favorite: false,
    // A sandbox has no golden reference of its own, and inheriting "re-run the golden check on
    // every save" would spend money on a copy made to avoid spending anything.
    goldenAutoRun: false,
    nodes,
  }
}

/**
 * What the copy does and does not simulate, said before it is made. Shown in the confirmation
 * dialog because a "sandbox" that quietly does not cover something is worse than no sandbox: it
 * buys confidence it has not earned.
 */
export const SANDBOX_WARNING =
  'The copy runs in plan mode, so file edits and shell commands are proposed instead of made, and ' +
  'every facade its workers use is replaced by a dry-run twin, so MCP writes answer "DRY RUN". ' +
  'NOT covered: an MCP server a coordinator reaches directly, without a worker facade — nothing ' +
  'simulates those. Check what your coordinator is wired to before trusting the copy.'
