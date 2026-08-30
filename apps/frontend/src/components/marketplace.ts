import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { ApiNodeData, MarketplaceItem, MarketplaceKind, MarketplaceScope } from '../api/types.ts'

/**
 * What the Marketplace page computes without asking the server: the search, the filters, the
 * sort, the state chip, and the read-only view of a payload. Pure functions, so the tests can
 * assert on them without rendering a grid.
 */

export const KINDS: MarketplaceKind[] = ['mcp', 'agent', 'facade', 'skill', 'plugin', 'api', 'flow']

/** Short chip labels — the kind is a chip on every card, so a word is all it gets. */
export const KIND_LABEL: Record<MarketplaceKind, string> = {
  mcp: 'MCP',
  agent: 'Agent',
  facade: 'Facade',
  skill: 'Skill',
  plugin: 'Plugin',
  api: 'API',
  flow: 'Flow',
}

/** The glyph a card wears when its item has no icon of its own. */
export const KIND_GLYPH: Record<MarketplaceKind, string> = {
  mcp: '⚙',
  agent: '★',
  facade: '⚖',
  skill: '📘',
  plugin: '🧩',
  api: '⇄',
  flow: '⬡',
}

/**
 * The Resources tab that lists what an install of this kind created. Flows live in the Flows
 * view; an API item creates nothing — the API node reads it from its own inspector.
 */
export type ResourceTab = 'mcp' | 'agents' | 'facades' | 'skills' | 'plugins'
export const RESOURCE_TAB: Partial<Record<MarketplaceKind, ResourceTab>> = {
  mcp: 'mcp',
  agent: 'agents',
  facade: 'facades',
  skill: 'skills',
  plugin: 'plugins',
}

export const SCOPE_LABEL: Record<MarketplaceScope, string> = {
  organization: 'Organization',
  global: 'Global',
  /** The chip names the group instead when it can; this is what it says when it cannot. */
  group: 'Group',
}

/** What the state chip says about an item, for this organization. Null: nothing worth a chip. */
export type ItemState = 'installed' | 'update' | 'pending' | 'rejected'

export const STATE_LABEL: Record<ItemState, string> = {
  installed: 'Installed ✓',
  update: 'Update ↑',
  pending: 'Pending ⏳',
  rejected: 'Rejected',
}

export function stateOf(item: MarketplaceItem): ItemState | null {
  if (item.status === 'pending') return 'pending'
  if (item.status === 'rejected') return 'rejected'
  if (!item.installed) return null
  return item.installed.version < item.version ? 'update' : 'installed'
}

export type StateFilter = '' | ItemState | 'mine'
export type MarketplaceSort = 'installs' | 'newest' | 'name'

export interface Filters {
  query: string
  kind: '' | MarketplaceKind
  scope: '' | MarketplaceScope
  state: StateFilter
  /** Every selected tag must be on the item — chips narrow, they do not widen. */
  tags: string[]
  sort: MarketplaceSort
}

export const EMPTY_FILTERS: Filters = { query: '', kind: '', scope: '', state: '', tags: [], sort: 'installs' }

/** The search is instant and local: name, summary and tags, case-insensitive substrings. */
function matchesQuery(item: MarketplaceItem, needle: string): boolean {
  if (!needle) return true
  return (
    item.name.toLowerCase().includes(needle) ||
    item.summary.toLowerCase().includes(needle) ||
    item.tags.some((tag) => tag.toLowerCase().includes(needle))
  )
}

function matchesState(item: MarketplaceItem, state: StateFilter): boolean {
  if (state === '') return true
  if (state === 'mine') return item.mine
  return stateOf(item) === state
}

export function visibleItems(items: MarketplaceItem[], f: Filters): MarketplaceItem[] {
  const needle = f.query.trim().toLowerCase()
  const kept = items.filter(
    (item) =>
      matchesQuery(item, needle) &&
      (f.kind === '' || item.kind === f.kind) &&
      (f.scope === '' || item.scope === f.scope) &&
      matchesState(item, f.state) &&
      f.tags.every((tag) => item.tags.includes(tag)),
  )
  const byName = (a: MarketplaceItem, b: MarketplaceItem) => a.name.localeCompare(b.name)
  switch (f.sort) {
    case 'name':
      return kept.sort(byName)
    case 'newest':
      return kept.sort(
        (a, b) => (b.publishedAt ?? b.createdAt) - (a.publishedAt ?? a.createdAt) || byName(a, b),
      )
    default:
      return kept.sort((a, b) => b.installs - a.installs || byName(a, b))
  }
}

/** Every tag on the list, for the chip row when the server sent none. */
export function tagsOf(items: MarketplaceItem[]): string[] {
  return [...new Set(items.flatMap((item) => item.tags))].sort()
}

/* ---------------- the payload, read-only ---------------- */

/** One line of the payload inspector. `long` is for a prompt or a document, shown as a block. */
export interface PayloadRow {
  label: string
  value: string
  long?: boolean
}

const str = (v: unknown): string =>
  v === undefined || v === null ? '' : typeof v === 'string' ? v : JSON.stringify(v)
const list = (v: unknown): string => (Array.isArray(v) ? v.map(str).join(', ') : str(v))
const keysOf = (v: unknown): string => (v && typeof v === 'object' ? Object.keys(v as object).join(', ') : '')
const yesNo = (v: unknown): string => (v === undefined || v === null ? '' : v ? 'yes' : 'no')

function push(rows: PayloadRow[], label: string, value: string, long = false) {
  if (value !== '') rows.push({ label, value, long })
}

/**
 * The payload as the resource's own panel would show it — an MCP is a URL or a command, an
 * agent is a model and a prompt — without the fields that mean nothing before an install.
 */
export function payloadRows(kind: MarketplaceKind, p: Record<string, unknown>): PayloadRow[] {
  const rows: PayloadRow[] = []
  switch (kind) {
    case 'mcp':
      push(rows, 'URL', str(p.url))
      push(
        rows,
        'Command',
        [str(p.command), ...(Array.isArray(p.args) ? p.args.map(str) : [])].filter(Boolean).join(' '),
      )
      push(rows, 'Environment keys', keysOf(p.env))
      push(rows, 'Auth', str(p.auth))
      push(rows, 'Token header', str(p.authHeader))
      break
    case 'agent':
      push(rows, 'Model', str(p.model))
      push(rows, 'Effort', str(p.effort))
      push(rows, 'Max tokens', str(p.maxTokens))
      push(rows, 'Delegate when…', str(p.description), true)
      push(rows, 'System prompt', str(p.systemPrompt), true)
      break
    case 'facade':
      push(rows, 'Allowed tools', Array.isArray(p.tools) && p.tools.length === 0 ? 'all' : list(p.tools))
      push(rows, 'Read-only', yesNo(p.readOnly))
      push(rows, 'Dry run', yesNo(p.dryRun))
      push(rows, 'Also reads', list(p.readAlso))
      break
    case 'skill':
      push(rows, 'Description', str(p.description))
      push(
        rows,
        'Files',
        Array.isArray(p.files)
          ? p.files
              .map((f) =>
                f && typeof f === 'object'
                  ? str((f as { path?: unknown; name?: unknown }).path ?? (f as { name?: unknown }).name)
                  : str(f),
              )
              .join(', ')
          : '',
      )
      break
    case 'plugin':
      push(rows, 'Marketplace', str(p.marketplace))
      push(rows, 'Plugin', str(p.pluginId))
      break
    case 'api':
      push(rows, 'Base URL', str(p.baseUrl))
      push(rows, 'Spec URL', str(p.specUrl))
      push(rows, 'Spec', p.spec ? 'included' : '')
      push(rows, 'Description', str(p.description), true)
      break
    case 'flow': {
      const nodes = Array.isArray(p.nodes) ? (p.nodes as Array<{ type?: unknown }>) : []
      const counts = new Map<string, number>()
      for (const n of nodes) {
        const kind = str(n?.type) || '?'
        counts.set(kind, (counts.get(kind) ?? 0) + 1)
      }
      push(rows, 'Blocks', String(nodes.length))
      push(rows, 'Kinds', [...counts.entries()].map(([k, n]) => `${k} ×${n}`).join(', '))
      push(rows, 'Mode', str(p.mode))
      break
    }
  }
  return rows
}

/**
 * What an API item puts on an API node: the base URL becomes the node's URL, the spec (by URL
 * or pasted) its spec, and the sentence its description. With no spec at all the node is a
 * single endpoint, because that is what a bare URL is.
 */
export function apiNodeFieldsFrom(p: Record<string, unknown>): Partial<ApiNodeData> {
  const baseUrl = str(p.baseUrl)
  const specUrl = str(p.specUrl)
  const spec = str(p.spec)
  const out: Partial<ApiNodeData> = { mode: specUrl || spec ? 'spec' : 'endpoint' }
  if (str(p.name)) out.label = str(p.name)
  if (baseUrl) {
    out.baseUrl = baseUrl
    out.url = baseUrl
  }
  if (specUrl) out.specUrl = specUrl
  if (spec) out.specInline = spec
  if (str(p.description)) out.description = str(p.description)
  return out
}

/* ---------------- markdown, minimally ---------------- */

export type MdBlock =
  | { type: 'p'; text: string }
  | { type: 'h'; text: string }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }
  | { type: 'code'; text: string }

const HEADING = /^#{1,6}\s+(.*)$/
const UL = /^\s*[-*+]\s+(.*)$/
const OL = /^\s*\d+[.)]\s+(.*)$/

/**
 * Paragraphs, headings, the two list kinds and fenced code. No library: a description is a few
 * paragraphs and a list, and a renderer that understands tables and footnotes would be most of
 * the page's weight for the one field that uses it.
 */
export function parseMarkdown(text: string): MdBlock[] {
  const blocks: MdBlock[] = []
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (line.trim() === '') {
      i++
      continue
    }
    if (line.startsWith('```')) {
      const code: string[] = []
      i++
      while (i < lines.length && !lines[i].startsWith('```')) code.push(lines[i++])
      i++
      blocks.push({ type: 'code', text: code.join('\n') })
      continue
    }
    const heading = HEADING.exec(line)
    if (heading) {
      blocks.push({ type: 'h', text: heading[1].trim() })
      i++
      continue
    }
    if (UL.test(line) || OL.test(line)) {
      const re = OL.test(line) ? OL : UL
      const items: string[] = []
      while (i < lines.length && re.test(lines[i])) items.push((re.exec(lines[i++]) as RegExpExecArray)[1].trim())
      blocks.push({ type: re === OL ? 'ol' : 'ul', items })
      continue
    }
    const para: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !lines[i].startsWith('```') &&
      !HEADING.test(lines[i]) &&
      !UL.test(lines[i]) &&
      !OL.test(lines[i])
    ) {
      para.push(lines[i++].trim())
    }
    blocks.push({ type: 'p', text: para.join(' ') })
  }
  return blocks
}

export type MdInline = { kind: 'text' | 'bold' | 'code'; text: string }

/** `**bold**` and `` `code` `` inside a line; everything else is text. */
export function inlineParts(text: string): MdInline[] {
  const parts: MdInline[] = []
  const re = /(\*\*([^*]+)\*\*|`([^`]+)`)/g
  let last = 0
  for (let m = re.exec(text); m; m = re.exec(text)) {
    if (m.index > last) parts.push({ kind: 'text', text: text.slice(last, m.index) })
    if (m[2] !== undefined) parts.push({ kind: 'bold', text: m[2] })
    else parts.push({ kind: 'code', text: m[3] })
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push({ kind: 'text', text: text.slice(last) })
  return parts
}

/* ---------------- what a resource panel knows about its origin ---------------- */

/**
 * The marketplace items this organization installed, by the resource each became — so a panel
 * can say "Marketplace · v3" next to a record, and "update" when the item has moved on.
 *
 * Derived here rather than carried by the resource lists: one read of the marketplace per panel
 * mount, and a panel that cannot reach it simply shows nothing extra. The read is deferred to a
 * microtask so a test that mocks the client without this route sees a rejection, not a throw.
 */
export function useMarketplaceInstalls(): Map<string, MarketplaceItem> {
  const [byResource, setByResource] = useState<Map<string, MarketplaceItem>>(() => new Map())
  useEffect(() => {
    let alive = true
    Promise.resolve()
      .then(() => api.listMarketplaceItems())
      .then((list) => {
        if (!alive) return
        const next = new Map<string, MarketplaceItem>()
        for (const item of list.items) if (item.installed) next.set(item.installed.resourceId, item)
        setByResource(next)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])
  return byResource
}
