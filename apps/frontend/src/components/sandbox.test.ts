import { describe, expect, it } from 'vitest'
import type { BackendFlow, FacadeProfile } from '../api/types.ts'
import {
  referencedProfileIds,
  sandboxFlow,
  sandboxProfileName,
  sandboxProfileOf,
  SANDBOX_TAG,
  SANDBOX_WARNING,
} from './sandbox.ts'

function flow(over: Partial<BackendFlow> = {}): BackendFlow {
  return {
    id: 'f1',
    name: 'Invoice runner',
    enabled: true,
    favorite: true,
    tags: ['ops'],
    nodes: [
      { id: 'in-1', type: 'input', role: null, data: { mode: 'cron', cron: '0 9 * * *' } },
      { id: 'a-1', type: 'agent', role: 'coordinator', data: { name: 'Lead' } },
      { id: 'a-2', type: 'agent', role: 'subagent', data: { name: 'Worker', facadeProfileId: 'fp_1' } },
    ],
    edges: [],
    ...over,
  } as unknown as BackendFlow
}

const profile: FacadeProfile = {
  id: 'fp_1',
  name: 'Holded writer',
  tools: ['invoice', 'contact'],
  readOnly: false,
  dryRun: false,
}

describe('sandboxFlow', () => {
  it('forces plan mode on the coordinator AND the trigger', () => {
    // The coordinator's value wins when both are set, so writing only the trigger would be
    // overridden by any flow that has one on its coordinator. And the backend identifies the
    // coordinator from node DATA, which a flow imported from a bundled sample may not carry — the
    // trigger is the fallback. Both can only make the copy more restrictive.
    const copy = sandboxFlow(flow(), {})

    expect((copy.nodes[0].data as Record<string, unknown>).permissionMode).toBe('plan')
    expect((copy.nodes[1].data as Record<string, unknown>).permissionMode).toBe('plan')
  })

  it('recognises the coordinator whether the role is on the node or in its data', () => {
    const fromSample = flow({
      nodes: [
        { id: 'in-1', type: 'input', role: null, data: { mode: 'manual' } },
        // A bundled sample: role on the node, nothing in data.
        { id: 'a-1', type: 'agent', role: 'coordinator', data: { name: 'Lead' } },
      ],
    } as unknown as Partial<BackendFlow>)

    expect((sandboxFlow(fromSample, {}).nodes[1].data as Record<string, unknown>).permissionMode)
      .toBe('plan')
  })

  it('overrides a coordinator that was set to act without asking', () => {
    const permissive = flow({
      nodes: [
        { id: 'in-1', type: 'input', role: null, data: { mode: 'cron', permissionMode: 'plan' } },
        {
          id: 'a-1',
          type: 'agent',
          role: 'coordinator',
          data: { name: 'Lead', role: 'coordinator', permissionMode: 'bypassPermissions' },
        },
      ],
    } as unknown as Partial<BackendFlow>)

    const copy = sandboxFlow(permissive, {})

    expect((copy.nodes[1].data as Record<string, unknown>).permissionMode).toBe('plan')
  })

  it('repoints each worker at the dry-run twin of its facade', () => {
    const copy = sandboxFlow(flow(), { fp_1: 'fp_sandbox' })

    expect((copy.nodes[2].data as Record<string, unknown>).facadeProfileId).toBe('fp_sandbox')
  })

  it('leaves a reference alone when there is no twin for it', () => {
    // A profile that no longer exists: repointing at nothing would silently take a worker's MCP
    // tools away, which is a different flow, not a sandboxed one.
    const copy = sandboxFlow(flow(), {})

    expect((copy.nodes[2].data as Record<string, unknown>).facadeProfileId).toBe('fp_1')
  })

  it('is a NEW flow, paused, tagged, and not pinned', () => {
    const copy = sandboxFlow(flow(), {})

    expect(copy.id).toBeUndefined()
    expect(copy.name).toBe('Invoice runner (sandbox)')
    // Paused whatever the original was: a copy that inherited a cron would start firing twice as
    // often as its owner asked for.
    expect(copy.enabled).toBe(false)
    expect(copy.favorite).toBe(false)
    expect(copy.tags).toEqual(['ops', SANDBOX_TAG])
  })

  it('never inherits the automatic golden check', () => {
    // Spending money on every save of a copy made to avoid spending anything.
    const copy = sandboxFlow(flow({ goldenAutoRun: true } as Partial<BackendFlow>), {})

    expect(copy.goldenAutoRun).toBe(false)
  })

  it('does not tag twice when sandboxing a sandbox', () => {
    const copy = sandboxFlow(flow({ tags: ['ops', SANDBOX_TAG] }), {})

    expect(copy.tags).toEqual(['ops', SANDBOX_TAG])
  })

  it('leaves the original untouched', () => {
    const original = flow()
    sandboxFlow(original, { fp_1: 'fp_sandbox' })

    expect(original.id).toBe('f1')
    expect((original.nodes[0].data as Record<string, unknown>).permissionMode).toBeUndefined()
    expect((original.nodes[1].data as Record<string, unknown>).permissionMode).toBeUndefined()
    expect((original.nodes[2].data as Record<string, unknown>).facadeProfileId).toBe('fp_1')
  })
})

describe('sandboxProfileOf', () => {
  it('simulates writes rather than blocking them', () => {
    // A sandbox is for watching the flow DO its work; a blocked call teaches less than a
    // simulated one, and dry-run is the mode that still exercises the path.
    const twin = sandboxProfileOf(profile)

    expect(twin.dryRun).toBe(true)
    expect(twin.readOnly).toBe(false)
    expect(twin.tools).toEqual(['invoice', 'contact'])
    expect(twin.id).toBeUndefined()
    expect(twin.name).toBe(sandboxProfileName('Holded writer'))
  })
})

describe('referencedProfileIds', () => {
  it('collects each facade the flow actually uses, once', () => {
    const two = flow({
      nodes: [
        { id: 'a-1', type: 'agent', role: 'subagent', data: { facadeProfileId: 'fp_1' } },
        { id: 'a-2', type: 'agent', role: 'subagent', data: { facadeProfileId: 'fp_1' } },
        { id: 'a-3', type: 'agent', role: 'subagent', data: { facadeProfileId: '  ' } },
        { id: 'a-4', type: 'agent', role: 'coordinator', data: { name: 'Lead' } },
      ],
    } as unknown as Partial<BackendFlow>)

    expect(referencedProfileIds(two)).toEqual(['fp_1'])
  })
})

describe('the warning', () => {
  it('names what is NOT simulated, not just what is', () => {
    // A sandbox that quietly does not cover something is worse than no sandbox: it buys
    // confidence it has not earned.
    expect(SANDBOX_WARNING).toContain('NOT covered')
    expect(SANDBOX_WARNING).toContain('directly')
  })
})
