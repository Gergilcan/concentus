import { useState } from 'react'
import { api } from '../api/client.ts'
import type { McpDef } from '../api/types.ts'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/**
 * Curated MCP servers, added with one click.
 *
 * This is how the integrations gap closes without building connectors: the MCP ecosystem is the
 * catalog, and what users needed was not more servers but not having to hunt each one's URL and
 * auth story out of its docs. Each entry states how it authenticates, because that is the question
 * that actually stops people: OAuth servers sign in from the node after adding; token servers need
 * a credential created first.
 *
 * Kept deliberately short and first-party-ish. A hundred entries would recreate the hunt this
 * removes; these are the ones flows reach for constantly.
 */
interface CatalogEntry {
  name: string
  url: string
  auth: 'oauth' | 'token' | 'none'
  note: string
}

const CATALOG: CatalogEntry[] = [
  // GitHub is the ecosystem's known exception: its MCP server does not support dynamic client
  // registration, so the OAuth sign-in the other entries use fails with "Incompatible auth
  // server". A fine-grained PAT over the Authorization header is the supported route.
  { name: 'github', url: 'https://api.githubcopilot.com/mcp/', auth: 'token',
    note: 'Needs a fine-grained personal access token stored as a credential — its OAuth rejects automated sign-in.' },
  { name: 'linear', url: 'https://mcp.linear.app/mcp', auth: 'oauth',
    note: 'Issues and projects. Sign in from the node after adding.' },
  { name: 'notion', url: 'https://mcp.notion.com/mcp', auth: 'oauth',
    note: 'Pages and databases. Sign in from the node after adding.' },
  { name: 'sentry', url: 'https://mcp.sentry.dev/mcp', auth: 'oauth',
    note: 'Errors and performance issues.' },
  { name: 'stripe', url: 'https://mcp.stripe.com', auth: 'oauth',
    note: 'Customers, invoices, payments.' },
  { name: 'gitlab', url: 'https://gitlab.com/api/v4/mcp', auth: 'token',
    note: 'Needs a personal access token stored as a credential (PRIVATE-TOKEN header).' },
]

export function McpCatalog({ onAdded }: { onAdded: () => void }) {
  const [note, setNote] = useState<string | null>(null)

  const add = async (entry: CatalogEntry) => {
    setNote(null)
    try {
      await api.saveMcpDef({
        name: entry.name,
        url: entry.url,
        credentialId: '',
        authHeader: entry.auth === 'token' && entry.name === 'gitlab' ? 'PRIVATE-TOKEN' : '',
      } as McpDef)
      setNote(
        entry.auth === 'oauth'
          ? `${entry.name} added. Drop an MCP node on the canvas, pick it, and press "Sign in to this server".`
          : entry.auth === 'token'
            ? `${entry.name} added. Create the token under Resources → Credentials and set its id on the definition.`
            : `${entry.name} added.`,
      )
      onAdded()
    } catch (e) {
      setNote(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div className={styles.catalog}>
      <h4 className={styles.h4}>Catalog — one click to add</h4>
      <div className={styles.catalogGrid}>
        {CATALOG.map((entry) => (
          <button key={entry.name} className={styles.catalogItem} onClick={() => void add(entry)}>
            <span className={styles.catalogName}>{entry.name}</span>
            <span className={styles.catalogNote}>{entry.note}</span>
          </button>
        ))}
      </div>
      {note && <p className={panels.hint}>{note}</p>}
      <p className={panels.hint}>
        URLs current as of this build — a provider moving its endpoint means editing the definition
        below, not reinstalling. Anything not listed here can be added by hand with its URL.
      </p>
    </div>
  )
}
