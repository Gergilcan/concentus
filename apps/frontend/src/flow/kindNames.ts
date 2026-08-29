import type { AppNodeData, NodeKind } from '../api/types.ts'

/**
 * What each kind of block is called when a person is told about it — the palette's words, so
 * the name in a search result is the name on the button that made the block.
 */
export const KIND_LABEL: Record<NodeKind, string> = {
  input: 'Input / trigger',
  coordinator: 'Coordinator',
  agent: 'Agent',
  verifier: 'Verifier',
  merge: 'Merge',
  mcp: 'MCP server',
  repo: 'Repository',
  sql: 'SQL source',
  api: 'API / endpoint',
  flow: 'Run another flow',
  knowledge: 'Knowledge base',
  condition: 'Condition',
  foreach: 'For each',
  note: 'Note',
  group: 'Group',
  mail: 'Send mail',
}

/**
 * The block's own name, or an empty string when its kind has none. Agents carry `name` and most
 * other kinds `label`; a note is named by its first line, because that is what someone scanning
 * for "the note about the budget" remembers of it.
 */
export function blockNameOf(data: AppNodeData): string {
  if ('name' in data && data.name) return data.name
  if ('label' in data && data.label) return data.label
  if (data.kind === 'note') {
    const line = data.text.split('\n').find((l) => l.trim())?.trim() ?? ''
    return line.length > 40 ? `${line.slice(0, 40)}…` : line
  }
  return ''
}
