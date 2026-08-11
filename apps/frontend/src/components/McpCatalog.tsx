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
  /** Remote entries carry a url; local (stdio) entries carry command/args instead. */
  url?: string
  /**
   * A stdio server the user's machine runs on demand — every Google/Microsoft-API entry ships
   * this way, there is no hosted endpoint to point at. The command must exist on PATH (npx
   * comes with Node; the Python ones need pipx or uv), which each note says out loud.
   */
  command?: string
  args?: string[]
  /** Env the server expects, pre-created EMPTY so the definition shows what must be filled. */
  env?: Record<string, string>
  auth: 'oauth' | 'token' | 'none' | 'stdio'
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

  // Google's API surface has no hosted MCP endpoints — its own servers and the community's all
  // run as local stdio processes. Package names and registries verified 11 Aug 2026.
  { name: 'Google Analytics', command: 'pipx', args: ['run', 'analytics-mcp'], auth: 'stdio', category: 'Google',
    env: { GOOGLE_APPLICATION_CREDENTIALS: '' },
    blurb: 'Reports and metrics (official)',
    note: "Google's own server (PyPI analytics-mcp) — needs pipx installed. Point GOOGLE_APPLICATION_CREDENTIALS at a service-account key with Analytics access, or leave it empty to use gcloud's application-default login." },
  { name: 'Google Ads', command: 'pipx', args: ['run', 'google-ads-mcp'], auth: 'stdio', category: 'Google',
    blurb: 'Campaigns and reporting (official)',
    note: "Google's own server (PyPI google-ads-mcp) — needs pipx installed, plus Google Ads API credentials configured as its README describes (github.com/googleads/google-ads-mcp)." },
  { name: 'Google Search Console', command: 'npx', args: ['-y', 'mcp-server-gsc'], auth: 'stdio', category: 'Google',
    env: { GOOGLE_APPLICATION_CREDENTIALS: '' },
    blurb: 'Search performance and sitemaps',
    note: 'Community server (npm mcp-server-gsc) — runs via npx. Point GOOGLE_APPLICATION_CREDENTIALS at a service-account key added to your Search Console property.' },
  { name: 'Google Tag Manager', command: 'npx', args: ['-y', 'google-tag-manager-mcp-server'], auth: 'stdio', category: 'Google',
    blurb: 'Containers, tags, triggers',
    note: 'Community server (npm google-tag-manager-mcp-server, by stape-io) — runs via npx; needs Google OAuth credentials as its README describes.' },
  { name: 'Google Workspace', command: 'uvx', args: ['workspace-mcp'], auth: 'stdio', category: 'Google',
    env: { GOOGLE_OAUTH_CLIENT_ID: '', GOOGLE_OAUTH_CLIENT_SECRET: '' },
    blurb: 'Gmail, Calendar, Drive, Docs, Sheets',
    note: 'Community server (PyPI workspace-mcp) — needs uv installed. Fill the OAuth client id/secret from a Google Cloud project with the Workspace APIs enabled.' },
  { name: 'Google Maps', command: 'npx', args: ['-y', '@modelcontextprotocol/server-google-maps'], auth: 'stdio', category: 'Google',
    env: { GOOGLE_MAPS_API_KEY: '' },
    blurb: 'Places, directions, geocoding',
    note: 'Reference server (npm @modelcontextprotocol/server-google-maps) — runs via npx; fill GOOGLE_MAPS_API_KEY.' },

  { name: 'Microsoft Graph', command: 'npx', args: ['-y', '@merill/lokka'], auth: 'stdio', category: 'Microsoft',
    env: { TENANT_ID: '', CLIENT_ID: '', CLIENT_SECRET: '' },
    blurb: 'Users, mail, Teams, Entra, Intune',
    note: 'Lokka (npm @merill/lokka) — runs via npx. Fill the env from an Entra app registration with the Graph permissions you want; env values can reference credential:<id> to keep secrets in Credentials.' },
  { name: 'Microsoft Learn Docs', url: 'https://learn.microsoft.com/api/mcp', auth: 'none', category: 'Microsoft',
    blurb: 'Official Microsoft/Azure docs search',
    note: 'Hosted by Microsoft, no sign-in needed.' },
]

const CATEGORIES = [...new Set(CATALOG.map((e) => e.category))]

const AUTH_LABEL: Record<CatalogEntry['auth'], string> = {
  oauth: 'OAuth',
  token: 'token',
  none: 'no auth',
  stdio: 'runs locally',
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
        url: entry.url ?? '',
        credentialId: '',
        authHeader: entry.header ?? '',
        command: entry.command,
        args: entry.args,
        env: entry.env,
      } as McpDef)
      setAdded((prev) => new Set(prev ?? []).add(entry.name.toLowerCase()))
      setNote(
        entry.auth === 'oauth'
          ? `${entry.name} added. Drop an MCP node on the canvas, pick it, and press "Sign in to this server".`
          : entry.auth === 'token'
            ? `${entry.name} added. Create the token under Resources → Credentials and set its id on the definition.`
            : entry.auth === 'stdio'
              ? `${entry.name} added. It runs on this machine: open "Edit as JSON" above to fill its env values (they can reference credential:<id>), and make sure "${entry.command}" is installed.`
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
