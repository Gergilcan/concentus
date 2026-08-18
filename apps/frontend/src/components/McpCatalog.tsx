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
    note: "Google's own server (PyPI google-ads-mcp) — needs pipx installed, plus Google Ads API credentials configured as its README describes (github.com/googleads/google-ads-mcp). Read-only: it cannot change a bid, a budget or a campaign's status." },
  // Google's own server reads and never writes, so an agent that is supposed to MANAGE an account
  // needs a second one. These two are the same npm package pointed at the same account, split by
  // one env var, and that split is the point: the write tools are hidden from the tool list unless
  // GOOGLE_ADS_MCP_WRITE is set, so an analyst node physically cannot spend money, and the flow
  // that can is the one you deliberately gave the flag to. Verified 18 Aug 2026.
  { name: 'Google Ads (read)', command: 'npx', args: ['-y', 'mcp-google-ads'], auth: 'stdio', category: 'Google',
    env: { GOOGLE_ADS_CUSTOMER_ID: '', GOOGLE_ADS_MCC_CUSTOMER_ID: '' },
    blurb: 'Reports, search terms, keyword ideas',
    note: 'Community server (npm mcp-google-ads) — runs via npx, so no Python needed, unlike the official one above. Run `npx mcp-google-ads-auth` once to sign in and store the refresh token; fill the customer id (123-456-7890) and, for a manager account, the MCC id. Read-only: mutating tools stay hidden without the write flag.' },
  { name: 'Google Ads (write)', command: 'npx', args: ['-y', 'mcp-google-ads'], auth: 'stdio', category: 'Google',
    env: { GOOGLE_ADS_CUSTOMER_ID: '', GOOGLE_ADS_MCC_CUSTOMER_ID: '', GOOGLE_ADS_MCP_WRITE: 'true' },
    blurb: 'Create, pause, budgets, bids, keywords',
    note: 'The same server as above with GOOGLE_ADS_MCP_WRITE=true, which exposes the mutating tools — this one moves live ad spend. Everything it creates lands PAUSED and enabling is a separate, explicit call. Clear the flag to put it back in read-only mode.' },
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

  // Transactional and marketing email providers. Sending from a Gmail or Outlook MAILBOX already
  // lives under the Google and Microsoft tabs — this shelf is the dedicated sending platforms.
  // Endpoint and registries verified 11 Aug 2026. Mailgun and Postmark are absent on purpose:
  // their official servers are clone-and-run repositories with no npm/PyPI package to point a
  // command at, and the one unofficial Mailgun package documents nothing about its env.
  { name: 'Resend', url: 'https://mcp.resend.com/mcp', auth: 'oauth', category: 'Email',
    blurb: 'Send email, contacts, broadcasts (official)',
    note: 'Hosted by Resend. Sign in from the node after adding — or, for headless runs, store a Resend API key as a credential on the definition instead (it is accepted as a Bearer token).' },
  { name: 'Mailtrap', command: 'npx', args: ['-y', 'mcp-mailtrap'], auth: 'stdio', category: 'Email',
    env: { MAILTRAP_API_TOKEN: '', DEFAULT_FROM_EMAIL: '' },
    blurb: 'Send email, sandbox testing (official)',
    note: "Mailtrap's own server (npm mcp-mailtrap) — runs via npx. Fill MAILTRAP_API_TOKEN and DEFAULT_FROM_EMAIL; env values can reference credential:<id> to keep the token in Credentials." },
  { name: 'SendGrid', command: 'npx', args: ['-y', 'sendgrid-mcp'], auth: 'stdio', category: 'Email',
    env: { SENDGRID_API_KEY: '' },
    blurb: 'Send email, lists, templates, stats',
    note: 'Community server (npm sendgrid-mcp) against the SendGrid v3 API — runs via npx. Fill SENDGRID_API_KEY (can reference credential:<id>).' },
  { name: 'Mailchimp', command: 'uvx', args: ['mailchimp-mcp'], auth: 'stdio', category: 'Email',
    env: { MAILCHIMP_API_KEY: '', MAILCHIMP_READ_ONLY: 'true' },
    blurb: 'Campaigns, audiences, reports',
    note: 'Community server (PyPI mailchimp-mcp) — needs uv installed. Ships read-only on purpose (MAILCHIMP_READ_ONLY=true): explore safely first, and clear the flag only when the flow should write.' },
]

const CATEGORIES = [...new Set(CATALOG.map((e) => e.category))]

const AUTH_LABEL: Record<CatalogEntry['auth'], string> = {
  oauth: 'OAuth',
  token: 'token',
  none: 'no auth',
  stdio: 'runs locally',
}

/**
 * Entries whose one click leaves work behind — the ones worth handing to the setup dialog.
 *
 * Every LOCAL server does: it is launched by a command, so the machine needs the runtime that
 * command comes from (npx needs npm, uvx ships with uv), and adding one without that just moves
 * the failure to the first run. Token servers do too: they need a credential picked. OAuth and
 * open servers genuinely are one click, and routing those through a dialog would take away the
 * thing this catalogue is for.
 */
/** A catalogue entry handed to the setup dialog: what it is, and what it still needs answered. */
export interface CatalogSetup {
  name: string
  /** "stdio" (a command this machine runs) or "token" (a remote server needing a credential). */
  auth: 'stdio' | 'token'
  note: string
  url?: string
  command?: string
  args?: string[]
  env?: Record<string, string>
  authHeader?: string
}

function needsSetup(entry: CatalogEntry): boolean {
  return entry.auth === 'stdio' || entry.auth === 'token'
}

export function McpCatalog({
  onAdded,
  onConfigure,
  reloadToken = 0,
}: {
  onAdded: () => void
  /**
   * Bumped by the page when the server list changed elsewhere (the wizard saved one), so the ✓
   * marks catch up. A prop rather than a remount: remounting would fold the catalogue shut under
   * someone who has it open and is still browsing it.
   */
  reloadToken?: number
  /**
   * Opens the setup dialog for an entry that cannot just be added — a local server needing its
   * runtime installed, or one needing a token. Optional: without it every entry is added
   * directly, which is what the catalogue did before the dialog existed.
   */
  onConfigure?: (entry: CatalogSetup) => void
}) {
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
  }, [reloadToken])

  const add = async (entry: CatalogEntry) => {
    setNote(null)
    if (onConfigure && needsSetup(entry)) {
      onConfigure({
        name: entry.name,
        auth: entry.auth === 'stdio' ? 'stdio' : 'token',
        note: entry.note,
        url: entry.url,
        command: entry.command,
        args: entry.args,
        env: entry.env,
        authHeader: entry.header,
      })
      return
    }
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
              ? `${entry.name} added. It runs on this machine: pick it in the list below and fill its env values in "This server as JSON" (they can reference credential:<id>), and make sure "${entry.command}" is installed.`
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
                  title={
                    isAdded
                      ? 'Already in your server list below.'
                      : onConfigure && needsSetup(entry)
                        ? `${entry.note} — opens setup: it needs something installed or filled in before it can run.`
                        : entry.note
                  }
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
