import { describe, expect, it } from 'vitest'
import { mktItem } from '../test/marketplace.ts'
import {
  EMPTY_FILTERS,
  apiNodeFieldsFrom,
  inlineParts,
  parseMarkdown,
  payloadRows,
  stateOf,
  tagsOf,
  visibleItems,
} from './marketplace.ts'

const linear = mktItem({ id: 'a', name: 'Linear', summary: 'Issues and cycles', tags: ['planning', 'mcp'], installs: 142, publishedAt: 100 })
const techLead = mktItem({
  id: 'b',
  kind: 'agent',
  name: 'Tech Lead',
  summary: 'Plans and delegates',
  scope: 'organization',
  tags: ['review'],
  installs: 3,
  publishedAt: 300,
  installed: { resourceId: 'agent_1', version: 1, installedAt: 1 },
  mine: true,
})
const reviewer = mktItem({
  id: 'c',
  kind: 'facade',
  name: 'Reviewer',
  summary: 'Read-only facade',
  version: 2,
  installs: 20,
  publishedAt: 200,
  installed: { resourceId: 'facade_1', version: 1, installedAt: 1 },
})
const pending = mktItem({ id: 'd', kind: 'flow', name: 'PR review', summary: 'A template', status: 'pending', installs: 0, publishedAt: null, createdAt: 400 })

// The list endpoint decides visibility; everything here is what the page does with what it got.
describe('marketplace helpers', () => {
  it('reads the state chip from status, then from the installed version', () => {
    expect(stateOf(linear)).toBeNull()
    expect(stateOf(techLead)).toBe('installed')
    expect(stateOf(reviewer)).toBe('update')
    expect(stateOf(pending)).toBe('pending')
    expect(stateOf(mktItem({ status: 'rejected', installed: { resourceId: 'x', version: 1, installedAt: 1 } }))).toBe('rejected')
  })

  it('searches name, summary and tags, case-insensitively', () => {
    const all = [linear, techLead, reviewer, pending]
    const names = (q: string) => visibleItems(all, { ...EMPTY_FILTERS, query: q }).map((i) => i.name)
    expect(names('LINEAR')).toEqual(['Linear'])
    expect(names('delegates')).toEqual(['Tech Lead'])
    expect(names('review')).toEqual(['Reviewer', 'Tech Lead', 'PR review'])
    expect(names('  ')).toHaveLength(4)
  })

  it('filters by kind, scope, state and every selected tag', () => {
    const all = [linear, techLead, reviewer, pending]
    const names = (f: Partial<typeof EMPTY_FILTERS>) => visibleItems(all, { ...EMPTY_FILTERS, ...f }).map((i) => i.name)
    expect(names({ kind: 'agent' })).toEqual(['Tech Lead'])
    expect(names({ scope: 'organization' })).toEqual(['Tech Lead'])
    expect(names({ state: 'update' })).toEqual(['Reviewer'])
    expect(names({ state: 'installed' })).toEqual(['Tech Lead'])
    expect(names({ state: 'pending' })).toEqual(['PR review'])
    expect(names({ state: 'mine' })).toEqual(['Tech Lead'])
    expect(names({ tags: ['planning'] })).toEqual(['Linear'])
    expect(names({ tags: ['planning', 'review'] })).toEqual([])
  })

  it('sorts by installs, by newest publication, or by name', () => {
    const all = [pending, techLead, linear, reviewer]
    const names = (sort: typeof EMPTY_FILTERS.sort) => visibleItems(all, { ...EMPTY_FILTERS, sort }).map((i) => i.name)
    expect(names('installs')).toEqual(['Linear', 'Reviewer', 'Tech Lead', 'PR review'])
    // An unpublished item counts from its creation.
    expect(names('newest')).toEqual(['PR review', 'Tech Lead', 'Reviewer', 'Linear'])
    expect(names('name')).toEqual(['Linear', 'PR review', 'Reviewer', 'Tech Lead'])
  })

  it('collects the tags of a list, once each, sorted', () => {
    expect(tagsOf([linear, techLead, mktItem({ tags: ['mcp'] })])).toEqual(['mcp', 'planning', 'review'])
  })

  it('shows a payload the way its own panel would, skipping what is absent', () => {
    expect(payloadRows('mcp', { url: 'https://x', auth: 'oauth' })).toEqual([
      { label: 'URL', value: 'https://x', long: false },
      { label: 'Auth', value: 'oauth', long: false },
    ])
    expect(payloadRows('mcp', { command: 'npx', args: ['-y', 'server'], env: { TOKEN: '' } })).toEqual([
      { label: 'Command', value: 'npx -y server', long: false },
      { label: 'Environment keys', value: 'TOKEN', long: false },
    ])
    const agent = payloadRows('agent', { model: 'opus', effort: 'high', maxTokens: 8000, systemPrompt: 'You lead.' })
    expect(agent.map((r) => r.label)).toEqual(['Model', 'Effort', 'Max tokens', 'System prompt'])
    expect(agent[3]).toEqual({ label: 'System prompt', value: 'You lead.', long: true })
    expect(payloadRows('facade', { tools: [], readOnly: true, dryRun: false })).toEqual([
      { label: 'Allowed tools', value: 'all', long: false },
      { label: 'Read-only', value: 'yes', long: false },
      { label: 'Dry run', value: 'no', long: false },
    ])
    expect(payloadRows('flow', { nodes: [{ type: 'agent' }, { type: 'agent' }, { type: 'mcp' }], mode: 'local' })).toEqual([
      { label: 'Blocks', value: '3', long: false },
      { label: 'Kinds', value: 'agent ×2, mcp ×1', long: false },
      { label: 'Mode', value: 'local', long: false },
    ])
    expect(payloadRows('plugin', { marketplace: 'anthropics', pluginId: 'code-review' }).map((r) => r.value)).toEqual(['anthropics', 'code-review'])
  })

  it('turns an API payload into the API node’s fields — a spec when there is one, an endpoint otherwise', () => {
    expect(apiNodeFieldsFrom({ name: 'Petstore', baseUrl: 'https://p', specUrl: 'https://p/openapi.json', description: 'Pets' })).toEqual({
      mode: 'spec',
      label: 'Petstore',
      baseUrl: 'https://p',
      url: 'https://p',
      specUrl: 'https://p/openapi.json',
      description: 'Pets',
    })
    expect(apiNodeFieldsFrom({ baseUrl: 'https://hook', description: 'Posts' })).toEqual({
      mode: 'endpoint',
      baseUrl: 'https://hook',
      url: 'https://hook',
      description: 'Posts',
    })
    // A pasted document travels as a string, whatever shape it was stored in.
    expect(apiNodeFieldsFrom({ spec: { openapi: '3.0.0' } }).specInline).toBe('{"openapi":"3.0.0"}')
  })

  it('parses paragraphs, headings, lists and fenced code; bold and code inline', () => {
    expect(parseMarkdown('# Title\n\nOne line\nsame paragraph\n\n- a\n- b\n\n1. x\n2) y\n\n```\ncode\n```')).toEqual([
      { type: 'h', text: 'Title' },
      { type: 'p', text: 'One line same paragraph' },
      { type: 'ul', items: ['a', 'b'] },
      { type: 'ol', items: ['x', 'y'] },
      { type: 'code', text: 'code' },
    ])
    expect(inlineParts('say **hi** to `code` now')).toEqual([
      { kind: 'text', text: 'say ' },
      { kind: 'bold', text: 'hi' },
      { kind: 'text', text: ' to ' },
      { kind: 'code', text: 'code' },
      { kind: 'text', text: ' now' },
    ])
  })
})
