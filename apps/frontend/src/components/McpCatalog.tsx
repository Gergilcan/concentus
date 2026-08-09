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
  /**
   * Header the token goes in, when it is not `Authorization: Bearer`. Carried as data rather
   * than decided by comparing the name — that comparison broke silently the moment the entries
   * were capitalised, which is exactly the kind of bug a name check invites.
   */
  header?: string
}

const CATALOG: CatalogEntry[] = [
  // Development
  // GitHub is the ecosystem's known exception: its MCP server does not support dynamic client
  // registration, so the OAuth sign-in the other entries use fails with "Incompatible auth
  // server". A fine-grained PAT over the Authorization header is the supported route.
  { name: 'GitHub', url: 'https://api.githubcopilot.com/mcp/', auth: 'token',
    note: 'Issues, PRs, repositories. Needs a fine-grained personal access token stored as a credential — its OAuth rejects automated sign-in.' },
  { name: 'GitLab', url: 'https://gitlab.com/api/v4/mcp', auth: 'token', header: 'PRIVATE-TOKEN',
    note: 'Issues, merge requests, pipelines. Needs a personal access token stored as a credential (PRIVATE-TOKEN header).' },
  { name: 'Sentry', url: 'https://mcp.sentry.dev/mcp', auth: 'oauth',
    note: 'Errors, issues and performance data. Sign in from the node after adding.' },

  // Planning and docs
  { name: 'Linear', url: 'https://mcp.linear.app/mcp', auth: 'oauth',
    note: 'Issues, projects and cycles. Sign in from the node after adding.' },
  { name: 'Notion', url: 'https://mcp.notion.com/mcp', auth: 'oauth',
    note: 'Pages and databases. Sign in from the node after adding.' },
  { name: 'Atlassian', url: 'https://mcp.atlassian.com/v1/sse', auth: 'oauth',
    note: 'Jira issues and Confluence pages. Sign in from the node after adding.' },
  { name: 'Asana', url: 'https://mcp.asana.com/sse', auth: 'oauth',
    note: 'Tasks, projects and portfolios. Sign in from the node after adding.' },
  { name: 'Intercom', url: 'https://mcp.intercom.com/mcp', auth: 'oauth',
    note: 'Conversations and contacts from your support inbox.' },

  // Business
  { name: 'Stripe', url: 'https://mcp.stripe.com', auth: 'oauth',
    note: 'Customers, invoices and payments.' },
  { name: 'PayPal', url: 'https://mcp.paypal.com/mcp', auth: 'oauth',
    note: 'Invoices, orders and transactions.' },
  { name: 'Square', url: 'https://mcp.squareup.com/sse', auth: 'oauth',
    note: 'Payments, catalogue and customers.' },
  { name: 'HubSpot', url: 'https://mcp.hubspot.com/anthropic', auth: 'oauth',
    note: 'CRM contacts, companies and deals.' },

  // Data and content
  { name: 'Cloudflare', url: 'https://observability.mcp.cloudflare.com/sse', auth: 'oauth',
    note: 'Workers observability: logs and analytics.' },
  { name: 'Figma', url: 'http://127.0.0.1:3845/mcp', auth: 'none',
    note: 'Local only: needs Figma desktop running with its MCP server enabled in preferences.' },
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
        authHeader: entry.header ?? '',
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
          <button
            key={entry.name}
            className={styles.catalogItem}
            title={entry.note}
            onClick={() => void add(entry)}
          >
            <span className={styles.catalogName}>{entry.name}</span>
            <span className={styles.catalogNote}>{entry.auth === 'oauth' ? 'OAuth sign-in' : entry.auth === 'token' ? 'needs a token' : 'no auth'}</span>
          </button>
        ))}
      </div>
      {note && <p className={panels.hint}>{note}</p>}
    </div>
  )
}
