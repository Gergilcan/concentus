import { useEffect, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
import { api } from '../api/client.ts'
import type { McpDef } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
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
  /** What the server gives an agent — the card's visible line. */
  blurb: string
  /** The longer story (auth details, caveats), kept to the tooltip. */
  note: string
  /**
   * Header the token goes in, when it is not `Authorization: Bearer`. Carried as data rather
   * than decided by comparing the name — that comparison broke silently the moment the entries
   * were capitalised, which is exactly the kind of bug a name check invites.
   */
  header?: string
  category: string
}

const CATALOG: CatalogEntry[] = [
  // GitHub is the ecosystem's known exception: its MCP server does not support dynamic client
  // registration, so the OAuth sign-in the other entries use fails with "Incompatible auth
  // server". A fine-grained PAT over the Authorization header is the supported route.
  { name: 'GitHub', url: 'https://api.githubcopilot.com/mcp/', auth: 'token', category: 'Development',
    blurb: 'Issues, PRs, repositories',
    note: 'Needs a fine-grained personal access token stored as a credential — its OAuth rejects automated sign-in.' },
  { name: 'GitLab', url: 'https://gitlab.com/api/v4/mcp', auth: 'token', header: 'PRIVATE-TOKEN', category: 'Development',
    blurb: 'Issues, MRs, pipelines',
    note: 'Needs a personal access token stored as a credential (PRIVATE-TOKEN header).' },
  { name: 'Sentry', url: 'https://mcp.sentry.dev/mcp', auth: 'oauth', category: 'Development',
    blurb: 'Errors, issues, performance',
    note: 'Sign in from the node after adding.' },

  { name: 'Linear', url: 'https://mcp.linear.app/mcp', auth: 'oauth', category: 'Planning & docs',
    blurb: 'Issues, projects, cycles',
    note: 'Sign in from the node after adding.' },
  { name: 'Notion', url: 'https://mcp.notion.com/mcp', auth: 'oauth', category: 'Planning & docs',
    blurb: 'Pages and databases',
    note: 'Sign in from the node after adding.' },
  { name: 'Atlassian', url: 'https://mcp.atlassian.com/v1/sse', auth: 'oauth', category: 'Planning & docs',
    blurb: 'Jira issues, Confluence pages',
    note: 'Sign in from the node after adding.' },
  { name: 'Asana', url: 'https://mcp.asana.com/sse', auth: 'oauth', category: 'Planning & docs',
    blurb: 'Tasks, projects, portfolios',
    note: 'Sign in from the node after adding.' },
  { name: 'Intercom', url: 'https://mcp.intercom.com/mcp', auth: 'oauth', category: 'Planning & docs',
    blurb: 'Support conversations, contacts',
    note: 'Sign in from the node after adding.' },

  { name: 'Stripe', url: 'https://mcp.stripe.com', auth: 'oauth', category: 'Business',
    blurb: 'Customers, invoices, payments',
    note: 'Sign in from the node after adding.' },
  { name: 'PayPal', url: 'https://mcp.paypal.com/mcp', auth: 'oauth', category: 'Business',
    blurb: 'Invoices, orders, transactions',
    note: 'Sign in from the node after adding.' },
  { name: 'Square', url: 'https://mcp.squareup.com/sse', auth: 'oauth', category: 'Business',
    blurb: 'Payments, catalogue, customers',
    note: 'Sign in from the node after adding.' },
  { name: 'HubSpot', url: 'https://mcp.hubspot.com/anthropic', auth: 'oauth', category: 'Business',
    blurb: 'CRM contacts, companies, deals',
    note: 'Sign in from the node after adding.' },

  { name: 'Cloudflare', url: 'https://observability.mcp.cloudflare.com/sse', auth: 'oauth', category: 'Data & content',
    blurb: 'Workers logs and analytics',
    note: 'Sign in from the node after adding.' },
  { name: 'Figma', url: 'http://127.0.0.1:3845/mcp', auth: 'none', category: 'Data & content',
    blurb: 'Designs from Figma desktop',
    note: 'Local only: needs Figma desktop running with its MCP server enabled in preferences.' },
]

const CATEGORIES = [...new Set(CATALOG.map((e) => e.category))]

const AUTH_LABEL: Record<CatalogEntry['auth'], string> = {
  oauth: 'OAuth',
  token: 'token',
  none: 'local',
}

export function McpCatalog({ onAdded }: { onAdded: () => void }) {
  const [note, setNote] = useState<string | null>(null)
  // Names already in the user's server list, lowercased. What turns a card into a ✓: adding the
  // same server twice only creates a confusing duplicate below, so an added card says so and
  // steps aside instead of silently doing it again.
  const [added, setAdded] = useState<Set<string> | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  // Collapsed by default: the catalog is for setup moments, and open it pushed the user's OWN
  // servers — the things this tab manages — below the fold on every visit.
  const [open, setOpen] = useState(false)
  // One category at a time, as tabs. All fourteen entries at once was the wall of cards that made
  // the collapse necessary in the first place.
  const [category, setCategory] = useState(CATEGORIES[0])

  useEffect(() => {
    api
      .listMcpDefs()
      .then((defs) => setAdded(new Set(defs.map((d) => d.name.toLowerCase()))))
      // If the list cannot be read the catalog still works — it just cannot mark anything.
      .catch(() => setAdded(new Set()))
  }, [])

  const add = async (entry: CatalogEntry) => {
    setNote(null)
    setBusy(entry.name)
    try {
      await api.saveMcpDef({
        name: entry.name,
        url: entry.url,
        credentialId: '',
        authHeader: entry.header ?? '',
      } as McpDef)
      setAdded((prev) => new Set(prev ?? []).add(entry.name.toLowerCase()))
      setNote(
        entry.auth === 'oauth'
          ? `${entry.name} added. Drop an MCP node on the canvas, pick it, and press "Sign in to this server".`
          : entry.auth === 'token'
            ? `${entry.name} added. Create the token under Resources → Credentials and set its id on the definition.`
            : `${entry.name} added.`,
      )
      onAdded()
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className={styles.catalog}>
      <button
        className={styles.collapseHead}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        title="Curated servers, added with one click — each entry says how it authenticates."
      >
        <span className={styles.collapseArrow}>{open ? '▾' : '▸'}</span>
        Catalog — one click to add
        <span className={styles.collapseCount}>{CATALOG.length}</span>
      </button>

      {open && (
        <>
          <div className={styles.chipRow} role="tablist">
            {CATEGORIES.map((cat) => (
              <button
                key={cat}
                role="tab"
                aria-selected={category === cat}
                className={cx(styles.chip, category === cat && styles.chipActive)}
                onClick={() => setCategory(cat)}
              >
                {cat}
              </button>
            ))}
          </div>
          <div className={styles.catalogGrid}>
            {CATALOG.filter((e) => e.category === category).map((entry) => {
              const isAdded = added?.has(entry.name.toLowerCase()) ?? false
              return (
                <button
                  key={entry.name}
                  className={cx(styles.catalogItem, isAdded && styles.catalogAdded)}
                  title={isAdded ? 'Already in your server list below.' : entry.note}
                  disabled={isAdded || busy === entry.name}
                  onClick={() => void add(entry)}
                >
                  <span className={styles.catalogHead}>
                    <span className={styles.catalogName}>{entry.name}</span>
                    {isAdded ? (
                      <span className={cx(styles.authPill, styles.authAdded)}>✓ added</span>
                    ) : (
                      <span className={cx(styles.authPill, styles['auth_' + entry.auth])}>
                        {busy === entry.name ? '…' : AUTH_LABEL[entry.auth]}
                      </span>
                    )}
                  </span>
                  <span className={styles.catalogNote}>{entry.blurb}</span>
                </button>
              )
            })}
          </div>
        </>
      )}
      {note && <p className={panels.hint}>{note}</p>}
    </div>
  )
}
