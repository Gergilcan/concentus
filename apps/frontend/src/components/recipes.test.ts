import { describe, expect, it } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { applyRecipe, fieldKey, fieldsOf, FLOW, RECIPES } from './recipes.ts'

const inbox = RECIPES.find((r) => r.id === 'inbox-triage')!

function sample(): BackendFlow {
  return {
    id: 'mailbox-assistant',
    name: 'Mailbox assistant (IMAP)',
    mode: 'local',
    enabled: false,
    folder: 'Samples',
    nodes: [
      {
        id: 'in-1',
        type: 'input',
        role: null,
        data: { mode: 'mail', mailHost: '', mailUsername: '', mailFolder: 'INBOX', mailPollSeconds: 120 },
      },
      { id: 'agent-1', type: 'agent', role: 'coordinator', data: { name: 'Mail Triage' } },
    ],
    edges: [{ id: 'e-in', source: 'in-1', target: 'agent-1' }],
  } as unknown as BackendFlow
}

describe('applyRecipe', () => {
  it('writes each answer onto the node and field it belongs to', () => {
    const flow = applyRecipe(sample(), inbox, {
      'in-1.mailHost': 'imap.gmail.com',
      'in-1.mailUsername': 'me@example.com',
    }, false)

    const input = flow.nodes.find((n) => n.id === 'in-1')!.data as Record<string, unknown>
    expect(input.mailHost).toBe('imap.gmail.com')
    expect(input.mailUsername).toBe('me@example.com')
    // Untouched fields survive: the recipe fills holes, it does not rebuild the node.
    expect(input.mailPollSeconds).toBe(120)
    expect(flow.nodes.find((n) => n.id === 'agent-1')!.data).toEqual({ name: 'Mail Triage' })
  })

  it('leaves the sample’s own default alone when an answer is blank', () => {
    // The difference between "INBOX" and no folder at all — an empty string would be an answer,
    // and a blank field is not one.
    const flow = applyRecipe(sample(), inbox, { 'in-1.mailFolder': '   ' }, false)

    expect((flow.nodes[0].data as Record<string, unknown>).mailFolder).toBe('INBOX')
  })

  it('trims what the user typed', () => {
    const flow = applyRecipe(sample(), inbox, { 'in-1.mailHost': '  imap.gmail.com  ' }, false)

    expect((flow.nodes[0].data as Record<string, unknown>).mailHost).toBe('imap.gmail.com')
  })

  it('saves as a NEW flow in the user’s own list, never over the sample', () => {
    const flow = applyRecipe(sample(), inbox, {}, false)

    // No id: saving creates. The sample stays where it is, which is the point of a sample.
    expect(flow.id).toBeUndefined()
    expect(flow.folder).toBe('')
    expect(flow.name).toBe(inbox.title)
  })

  it('is paused unless the user asked for it to start', () => {
    // Every recipe has a trigger, so saving one enabled would start it spending on a schedule
    // nobody has looked at yet.
    expect(applyRecipe(sample(), inbox, {}, false).enabled).toBe(false)
    expect(applyRecipe(sample(), inbox, {}, true).enabled).toBe(true)
  })
})

describe('the recipe manifests', () => {
  it('asks few enough questions to be worth calling a recipe', () => {
    for (const recipe of RECIPES) {
      expect(recipe.questions.length, recipe.id).toBeLessThanOrEqual(3)
      expect(recipe.questions.length, recipe.id).toBeGreaterThan(0)
    }
  })

  it('gives every field a unique key, so two nodes can share a field name', () => {
    for (const recipe of RECIPES) {
      const keys = fieldsOf(recipe).map(fieldKey)
      expect(new Set(keys).size, recipe.id).toBe(keys.length)
    }
  })

  it('points every recipe at a bundled sample, and every field at something that exists', () => {
    // The sample ids are the bundled library flows; a typo here would only surface as a 404 when
    // someone actually picked the recipe.
    const bundled = [
      'mailbox-assistant',
      'daily-briefing',
      'webhook-issue-triage',
      'pr-review-crew',
      'docs-from-code',
      'outlook-quote-to-holded',
      'support-triage-if-else',
      'report-with-fallback',
    ]
    // The node ids the samples actually use, plus FLOW for an answer about the flow itself. A
    // field pointing anywhere else is a question whose answer lands nowhere — which looks like the
    // recipe worked and produces a flow missing the one thing you were asked for.
    const targets = [FLOW, 'in-1', 'agent-1', 'repo-1', 'if-1']
    for (const recipe of RECIPES) {
      expect(bundled, recipe.id).toContain(recipe.sampleId)
      for (const field of fieldsOf(recipe)) {
        expect(targets, `${recipe.id}/${field.field}`).toContain(field.nodeId)
      }
    }
  })

  // Answers about the flow reach the flow. Before this they were dropped on the floor: applyRecipe
  // only ever patched node data, so a recipe could ask where to send failures and then quietly
  // produce a flow that sends them nowhere.
  it('applies an answer that belongs to the flow rather than to a node', () => {
    const recipe = RECIPES.find((r) => fieldsOf(r).some((f) => f.nodeId === FLOW))
    expect(recipe, 'no recipe asks anything about the flow').toBeDefined()
    const field = fieldsOf(recipe!).find((f) => f.nodeId === FLOW)!

    const built = applyRecipe(
      { id: 'sample', name: 'Sample', mode: 'local', nodes: [], edges: [] },
      recipe!,
      { [fieldKey(field)]: 'https://hooks.example.com/x' },
      false,
    )

    expect(built[field.field as keyof typeof built]).toBe('https://hooks.example.com/x')
  })
})
