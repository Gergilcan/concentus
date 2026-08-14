import type { McpDef } from '../api/types.ts'

/**
 * One MCP server as mcp.json writes it: the object kept under the server's name, and nothing else.
 *
 * This replaces a whole-registry document. Editing every server at once answered a question nobody
 * had — the list beside it has already picked one — and it made a routine edit capable of deleting
 * the rest, because that document's contract was "what is not here is gone". Scoped to one server
 * there is nothing to lose by editing.
 *
 * What it still accepts is a README snippet pasted whole, wrapper and all, since that is the form
 * every server's docs hand out.
 */

/** The keys a server object may carry. The same set the backend's registry document accepts. */
const KNOWN_KEYS = new Set(['url', 'credentialId', 'authHeader', 'command', 'args', 'env', 'type', 'headers'])

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function str(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

/** The server object for a definition — the stdio form or the URL form, never a mix of both. */
export function serverObject(def: McpDef): Record<string, unknown> {
  if ((def.command ?? '').trim() !== '') {
    // `env` is written even when empty, unlike the registry document: this box is where a local
    // server's variables are filled in, and an absent key is a worse invitation than an empty one.
    return { command: def.command, args: def.args ?? [], env: def.env ?? {} }
  }
  const server: Record<string, unknown> = { url: def.url ?? '' }
  if (def.credentialId) server.credentialId = def.credentialId
  if (def.authHeader) server.authHeader = def.authHeader
  return server
}

/** `serverObject`, formatted the way the textarea shows it. */
export function formatServer(def: McpDef): string {
  return JSON.stringify(serverObject(def), null, 2)
}

export interface ParsedServer {
  /** The definition to save: `base`'s id, with everything else taken from the JSON. */
  def: McpDef
  /** Keys that were understood but not kept, worth saying out loud rather than dropping in silence. */
  warnings: string[]
}

/**
 * Reads what the box holds back into a definition.
 *
 * Throws with a sentence a person can act on — this is a text box, so a bad paste is the normal
 * case, not an exceptional one.
 */
export function parseServerJson(text: string, base: McpDef): ParsedServer {
  let doc: unknown
  try {
    doc = JSON.parse(text)
  } catch (e) {
    throw new Error(`Not valid JSON: ${e instanceof Error ? e.message : String(e)}`)
  }
  if (!isObject(doc)) {
    throw new Error('Expected an object — what mcp.json keeps under the server’s name.')
  }

  let name = base.name
  let server = doc
  // A snippet pasted whole: {"mcpServers": {"google-ads": {…}}}. Unwrapped rather than refused,
  // and its key wins over the Name field above — it is what the person pasting chose to call it.
  if (isObject(doc.mcpServers)) {
    const entries = Object.entries(doc.mcpServers)
    if (entries.length !== 1) {
      throw new Error(
        `This edits one server; that snippet has ${entries.length}. Paste them one at a time.`,
      )
    }
    if (!isObject(entries[0][1])) throw new Error(`"${entries[0][0]}" is not an object.`)
    name = entries[0][0].trim()
    server = entries[0][1]
  }

  if (!name.trim()) throw new Error('This server has no name yet — fill the Name field above.')

  const command = str(server.command)
  const url = str(server.url)
  if (!command && !url) {
    throw new Error('Needs a "url" (a remote server) or a "command" (one launched on this machine).')
  }

  const warnings: string[] = []
  if ('headers' in server) {
    warnings.push(
      '"headers" was ignored — store the token under Resources → Credentials and reference it as ' +
        'credentialId, or as an env value credential:<id>.',
    )
  }
  for (const key of Object.keys(server)) {
    if (!KNOWN_KEYS.has(key)) warnings.push(`"${key}" was ignored.`)
  }

  // The two transports are exclusive: whichever one the JSON describes, the other's fields are
  // cleared. A definition holding both a command and a URL is one whose behaviour depends on which
  // half the reader looks at.
  const def: McpDef = command
    ? {
        ...base,
        name,
        url: '',
        credentialId: '',
        authHeader: '',
        command,
        args: Array.isArray(server.args) ? server.args.map((a) => String(a)) : [],
        env: isObject(server.env)
          ? Object.fromEntries(Object.entries(server.env).map(([k, v]) => [k, String(v ?? '')]))
          : {},
      }
    : {
        ...base,
        name,
        url,
        credentialId: str(server.credentialId),
        authHeader: str(server.authHeader),
        command: '',
        args: [],
        env: {},
      }

  return { def, warnings }
}
