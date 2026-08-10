import type { BackendFlow, BackendFlowNode } from '../api/types.ts'

/**
 * What "Copy as template JSON" removes before a flow leaves this machine.
 *
 * The rule: a template shares the flow's SHAPE — nodes, edges, prompts, queries, allowed
 * operations — and never the author's identity or infrastructure. Everything here either
 * references a credential, names an account, or points at something only the author has
 * (a local path, a JDBC host, a private repository). Public service endpoints stay: an MCP
 * node's URL or an API node's OpenAPI spec URL is usually the whole point of the template.
 *
 * Values are DELETED rather than blanked, so the template JSON does not even show which
 * private fields were set.
 */
const STRIP_EVERYWHERE = [
  // Credential references. Never the secret itself (that never enters a flow), but an id that
  // is meaningless — and mildly leaky — outside the machine that minted it.
  'credentialId',
  'mailCredentialId',
  // The webhook trigger's shared secret: the one field on a flow that IS a secret.
  'secret',
  // Account and tenant identity.
  'mailUsername',
  'mailTenantId',
  'mailClientId',
  'username',
  // Infrastructure identity: a mail host or JDBC URL names the author's servers. The public
  // hosts (imap.gmail.com) are re-entered in seconds; the private ones must not leak.
  'mailHost',
  'jdbcUrl',
  // Private base-URL overrides (self-hosted GitLab, an API's internal deployment).
  'baseUrl',
  // Local machine paths and locally-minted ids that mean nothing to an importer.
  'contextFolders',
  'claudeMdPath',
  'skillIds',
  'facadeProfileId',
]

const STRIP_BY_TYPE: Record<string, string[]> = {
  // A repository node names the author's repos; the importer wires their own. `url` stays on
  // MCP and API nodes, where it is typically a public service endpoint.
  repo: ['url', 'group', 'only'],
  // A knowledge base id references a collection that exists only in the author's database.
  knowledge: ['baseId'],
}

function templateNode(node: BackendFlowNode): BackendFlowNode {
  const data = { ...node.data }
  for (const key of STRIP_EVERYWHERE) delete data[key]
  for (const key of STRIP_BY_TYPE[node.type] ?? []) delete data[key]
  return { ...node, data }
}

/**
 * The shareable shape of a flow. Also drops the flow-level fields that are the author's own
 * business: id, favourite, paused state, failure webhook, budget. Tags stay — they say what the
 * flow is for, which is exactly what a gallery filters on.
 */
export function toTemplate(flow: BackendFlow): BackendFlow {
  return {
    name: flow.name,
    mode: flow.mode,
    ...(flow.tags?.length ? { tags: flow.tags } : {}),
    nodes: flow.nodes.map(templateNode),
    edges: flow.edges,
  }
}

export function templateJson(flow: BackendFlow): string {
  return JSON.stringify(toTemplate(flow), null, 2)
}
