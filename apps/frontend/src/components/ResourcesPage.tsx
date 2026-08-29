import { Fragment, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { DatabaseDef, FacadeProfile, LibraryAgent, McpDef, Variable } from '../api/types.ts'
import { DEFAULT_MAX_TOKENS, DEFAULT_MODEL, EFFORT_OPTIONS } from '../constants.ts'
import { AddMcpServerModal } from './AddMcpServerModal.tsx'
import { AuditPanel } from './AuditPanel.tsx'
import { CredentialsPanel } from './CredentialsPanel.tsx'
import { CrudPanel } from './CrudPanel.tsx'
import { KnowledgePanel } from './KnowledgePanel.tsx'
import { McpCatalog, type CatalogSetup } from './McpCatalog.tsx'
import { McpClaudeActions } from './McpClaudeActions.tsx'
import { McpServerJson } from './McpServerJson.tsx'
import { MembersPanel } from './MembersPanel.tsx'
import { OrganizationsPanel } from './OrganizationsPanel.tsx'
import { ModelField } from './ModelField.tsx'
import { PluginsPanel } from './PluginsPanel.tsx'
import { ServiceAccountsPanel } from './ServiceAccountsPanel.tsx'
import { PoliciesPanel } from './PoliciesPanel.tsx'
import { SettingsPanel } from './SettingsPanel.tsx'
import { SkillsPanel } from './SkillsPanel.tsx'
import { StoragePanel } from './StoragePanel.tsx'
import { UpdatesPanel } from './UpdatesPanel.tsx'
import { shellBridge } from '../api/shell.ts'
import { usePermissions } from '../state/permissions.tsx'
import styles from './resources.module.scss'

type Tab = 'settings' | 'members' | 'serviceAccounts' | 'audit' | 'policies' | 'organizations' | 'agents' | 'mcp' | 'facades' | 'databases' | 'knowledge' | 'skills' | 'plugins' | 'variables' | 'credentials' | 'storage' | 'updates'

/**
 * The tab strip, in display order, in two groups: the things a flow uses, then how the
 * installation is run. They sat in one undifferentiated row, which asks somebody looking for a
 * knowledge base to read past "Members" and "Storage" to find it. The divider is the whole
 * treatment — a second row or a nested menu would cost more attention than the distinction is
 * worth.
 *
 * `desktopOnly` keeps Updates out of a browser tab, which has no app to update — the shell bridge
 * is absent there.
 */
const TABS: Array<{
  id: Tab
  label: string
  title?: string
  desktopOnly?: boolean
  startsAdmin?: boolean
  /** Shown to administrators only: the backend refuses everyone else, and a tab that only 403s is a broken tab. */
  adminOnly?: boolean
}> = [
  { id: 'agents', label: 'Agents' },
  { id: 'mcp', label: 'MCP Servers' },
  {
    id: 'facades',
    label: 'Facades',
    title:
      'What an independent worker may reach through its MCP facade: which tools, whether writes are blocked (read-only) or simulated (dry-run). Enforced by the backend on every call.',
  },
  { id: 'databases', label: 'Databases' },
  { id: 'knowledge', label: 'Knowledge' },
  { id: 'skills', label: 'Skills' },
  {
    id: 'plugins',
    label: 'Plugins',
    title:
      'Claude Code plugins installed on this machine. Each agent picks which ones its runs load.',
  },
  {
    id: 'variables',
    label: 'Variables',
    title:
      "Values substituted into every flow's prompts as {{NAME}} when a run starts. A flow can override any of them — or add its own — in its settings.",
  },
  { id: 'credentials', label: 'Credentials' },
  {
    id: 'members',
    label: 'Members',
    startsAdmin: true,
    title:
      'Who is in this organization and what each of them may do: read, run, edit, or administer. Enforced on every request, not only in the interface.',
  },
  {
    id: 'serviceAccounts',
    label: 'Service accounts',
    // Admin only, and hidden rather than disabled for everyone else: every route behind it
    // answers 403 to a non-admin, so a tab that opens onto an error is a tab that should not open.
    adminOnly: true,
    title:
      'Tokens for machines — a CI job, a cron entry, another system. Each acts as its role on every request and takes no seat.',
  },
  {
    id: 'audit',
    label: 'Audit',
    title:
      'Who did what, and when. Readable by administrators on every tier; exporting it as a file is an Enterprise feature.',
  },
  {
    id: 'policies',
    label: 'Policies',
    title:
      'Rules over every flow in the organization: a default facade for workers, a permission ceiling, an organization-wide budget, approval for published endpoints. Enterprise; read-only elsewhere.',
  },
  {
    id: 'organizations',
    label: 'Organizations',
    adminOnly: true,
    title:
      'Several organizations on one deployment, each with its own flows, credentials, runs and settings. Creating a second one is an Enterprise feature.',
  },
  {
    id: 'settings',
    label: 'Settings',
    title:
      'Limits, timeouts and allowlists for this installation. All of it used to be environment variables, which on a desktop install is a place nobody can edit.',
  },
  { id: 'storage', label: 'Storage' },
  { id: 'updates', label: 'Updates', desktopOnly: true },
]

/** A server launched here rather than reached over HTTP — the fields it has no use for hide. */
const isLocalServer = (draft: Record<string, unknown>) => String(draft.command ?? '').trim() !== ''

/** A list of substrings edited as one per line; blank lines are dropped on the way back. */
function LinesTextarea({
  value,
  onChange,
  rows,
  placeholder,
}: {
  value: unknown
  onChange: (lines: string[]) => void
  rows: number
  placeholder: string
}) {
  return (
    <textarea
      rows={rows}
      placeholder={placeholder}
      value={Array.isArray(value) ? (value as string[]).join('\n') : ''}
      onChange={(e) => onChange(e.target.value.split('\n').map((s) => s.trim()).filter(Boolean))}
    />
  )
}

export function ResourcesPage({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const { canAdminister } = usePermissions()
  const [tab, setTab] = useState<Tab>('agents')
  // Remounts the MCP CrudPanel after a catalog add, so the new definition appears in its list —
  // the panel loads on mount and has no other way to be told.
  const [mcpListVersion, setMcpListVersion] = useState(0)
  // Setup for a catalogue server that needs something before it can run. Null when closed; it is
  // only ever opened BY the catalogue — there is no blank state, because inventing a server from
  // scratch is what the list below and each server's own JSON box are for.
  const [wizard, setWizard] = useState<CatalogSetup | null>(null)

  return (
    <div className={styles.resources}>
      <div className={styles.tabs}>
        {TABS.filter((td) => (!td.desktopOnly || shellBridge()) && (!td.adminOnly || canAdminister)).map((td) => (
          <Fragment key={td.id}>
            {td.startsAdmin && <span className={styles.tabDivider} aria-hidden="true" />}
            <button
              className={tab === td.id ? styles.active : ''}
              onClick={() => setTab(td.id)}
              // NAME passed through literally so the {{NAME}} in the Variables tab's tooltip
              // survives interpolation in every language.
              title={td.title ? t(td.title, { NAME: '{{NAME}}' }) : undefined}
            >
              {t(td.label)}
            </button>
          </Fragment>
        ))}
      </div>

      <div className={styles.tabBody}>
        {tab === 'members' && <MembersPanel pushError={pushError} />}
        {tab === 'serviceAccounts' && canAdminister && <ServiceAccountsPanel pushError={pushError} />}
        {tab === 'audit' && <AuditPanel pushError={pushError} />}
        {tab === 'policies' && <PoliciesPanel pushError={pushError} />}
        {tab === 'organizations' && canAdminister && <OrganizationsPanel pushError={pushError} />}

        {tab === 'agents' && (
          <CrudPanel<LibraryAgent>
            title={t('Agents')}
            fields={[
              { key: 'name', label: t('Name') },
              {
                key: 'model',
                label: 'Model',
                // The same picker the canvas uses, rather than a text box you had to already know
                // the answer to fill in — grouped by family, with rates and any locally-served
                // models the backend reports.
                render: (value, onChange) => (
                  <ModelField
                    value={String(value ?? DEFAULT_MODEL)}
                    onChange={(v) => onChange(v)}
                  />
                ),
              },
              { key: 'effort', label: t('Effort'), type: 'select', options: [...EFFORT_OPTIONS] },
              { key: 'maxTokens', label: t('Max tokens'), type: 'number' },
              // The routing text a delegator reads. Here because a block linked to this agent
              // takes it from the library like the prompt — a field the block cannot edit has
              // to be editable somewhere.
              { key: 'description', label: t('Delegate when… (routing)'), type: 'textarea' },
              { key: 'systemPrompt', label: t('System prompt'), type: 'textarea' },
            ]}
            // The version beside the name: every save is one, and it is the number a linked
            // block is compared against, so the list is where you check what the blocks link.
            labelOf={(a) => (a.version ? `${a.name} · v${a.version}` : a.name)}
            idOf={(a) => a.id}
            empty={() => ({ name: '', model: DEFAULT_MODEL, effort: 'high', maxTokens: DEFAULT_MAX_TOKENS, description: '', systemPrompt: '' })}
            load={api.listAgents}
            save={api.saveAgent}
            remove={api.deleteAgent}
          />
        )}

        {tab === 'mcp' && (
          <>
          {/* Padded wrapper: this renders above the CRUD grid, and without it the collapse
              header sat glued to the window's left edge. */}
          <div className={styles.tabExtras}>
            <McpCatalog
              onAdded={() => setMcpListVersion((v) => v + 1)}
              onConfigure={setWizard}
              reloadToken={mcpListVersion}
            />
          </div>
          {wizard && (
            <AddMcpServerModal
              entry={wizard}
              onClose={() => setWizard(null)}
              onSaved={() => setMcpListVersion((v) => v + 1)}
            />
          )}
          <CrudPanel<McpDef>
            key={mcpListVersion}
            title={t('MCP Servers')}
            fields={[
              { key: 'name', label: t('Name'), placeholder: 'linear' },
              // The three below describe a remote server. A local one is a command, and its
              // command, arguments and environment are edited in its JSON box under the form.
              { key: 'url', label: t('URL'), placeholder: 'https://mcp.linear.app/mcp', hidden: isLocalServer },
              { key: 'credentialId', label: t('Credential id (optional)'), placeholder: t('from Resources -> Credentials'), hidden: isLocalServer },
              {
                key: 'authHeader',
                label: t('Send token in'),
                type: 'select',
                options: ['', 'PRIVATE-TOKEN'],
                hidden: isLocalServer,
              },
            ]}
            labelOf={(m) => m.name}
            idOf={(m) => m.id}
            empty={() => ({ name: '', url: '', credentialId: '', authHeader: '' })}
            load={api.listMcpDefs}
            save={api.saveMcpDef}
            remove={api.deleteMcpDef}
            extra={(m, apply) => (
              <>
                {/* The rest of this one definition — a local server's command, args and env have
                    no fields above, and this is also where a README snippet is pasted. Scoped to
                    the selected server: the list beside it is how you choose which. */}
                <McpServerJson def={m} onApplied={apply} />
                <McpClaudeActions
                  name={m.name}
                  url={m.url}
                  credentialId={m.credentialId}
                  authHeader={m.authHeader}
                />
              </>
            )}
          />
          </>
        )}

        {tab === 'variables' && (
          <CrudPanel<Variable>
            title={t('Variables')}
            fields={[
              {
                key: 'name',
                // NAME passed through literally so the {{NAME}} example survives interpolation.
                label: t('Name (used as {{NAME}} in prompts)', { NAME: '{{NAME}}' }),
                placeholder: t('COMPANY'),
              },
              { key: 'value', label: t('Value'), placeholder: 'ACME S.L.' },
              { key: 'description', label: t('Description (optional)'), placeholder: t('Legal company name') },
            ]}
            labelOf={(v) => v.name}
            idOf={(v) => v.id}
            empty={() => ({ name: '', value: '', description: '' })}
            load={api.listVariables}
            save={api.saveVariable}
            remove={api.deleteVariable}
          />
        )}

        {tab === 'facades' && (
          <CrudPanel<FacadeProfile>
            title={t('Facade profiles')}
            fields={[
              { key: 'name', label: t('Name'), placeholder: t('reader') },
              { key: 'description', label: t('Description'), placeholder: t('Read-only lookups for support workers') },
              {
                key: 'tools',
                label: 'Allowed tools',
                render: (value, onChange) => (
                  <label className={styles.field}>
                    <span
                      title={t(
                        "Case-insensitive substrings, one per line — 'contact' covers create_contact and list_contacts. Empty exposes every tool the worker's MCP nodes wire in (writes still gated below).",
                      )}
                    >
                      {t('Allowed tools (one substring per line, empty = all)')} ⓘ
                    </span>
                    <LinesTextarea rows={4} placeholder={'contact\ninvoice'} value={value} onChange={onChange} />
                  </label>
                ),
              },
              {
                key: 'readOnly',
                label: 'Read-only',
                render: (value, onChange) => (
                  <label
                    className={styles.field}
                    title={t(
                      'Write-shaped tools (create_*, send_*, anything not clearly a read) are not shown to the worker and refuse to run. The strictest setting; wins over dry-run.',
                    )}
                  >
                    <span>
                      <input
                        type="checkbox"
                        checked={Boolean(value)}
                        onChange={(e) => onChange(e.target.checked)}
                      />{' '}
                      {t('Read-only — writes are blocked outright')} ⓘ
                    </span>
                  </label>
                ),
              },
              {
                key: 'readAlso',
                label: 'Also reads',
                render: (value, onChange) => (
                  <label className={styles.field}>
                    <span
                      title={t(
                        "Read or write is guessed from the verb a tool's name starts with — get, list, search, find, read, fetch, query, preview, download, describe, show, count, check, status, history, ping. Anything else counts as a write, because guessing the other way would execute something. That leaves real reads outside: run_gaql_query on Google Ads and run_report on Analytics are the most useful reads either server has, and read-only hides both. Name them here and they survive. Case-insensitive substrings, one per line.",
                      )}
                    >
                      {t('Also treat as reads, whatever the name suggests')} ⓘ
                    </span>
                    <LinesTextarea rows={3} placeholder={'run_gaql_query\nrun_report'} value={value} onChange={onChange} />
                  </label>
                ),
              },
              {
                key: 'dryRun',
                label: 'Dry run',
                render: (value, onChange) => (
                  <label
                    className={styles.field}
                    title={t(
                      "Writes are visible and callable, but the facade answers 'DRY RUN — nothing was executed' and the worker reports the action as proposed. On by default: writes only really execute when you deliberately untick this.",
                    )}
                  >
                    <span>
                      <input
                        type="checkbox"
                        checked={value === undefined || value === null ? true : Boolean(value)}
                        onChange={(e) => onChange(e.target.checked)}
                      />{' '}
                      {t('Dry-run writes — simulate instead of executing')} ⓘ
                    </span>
                  </label>
                ),
              },
            ]}
            labelOf={(p) => p.name}
            idOf={(p) => p.id}
            empty={() => ({ name: '', description: '', tools: [], readAlso: [], readOnly: false, dryRun: true })}
            load={api.listFacadeProfiles}
            save={api.saveFacadeProfile}
            remove={api.deleteFacadeProfile}
          />
        )}

        {tab === 'databases' && (
          <CrudPanel<DatabaseDef>
            title={t('Databases')}
            fields={[
              { key: 'label', label: t('Label') },
              { key: 'jdbcUrl', label: t('JDBC URL'), placeholder: 'jdbc:postgresql://host:5432/db' },
              { key: 'username', label: t('Username') },
              { key: 'credentialId', label: t('Credential id'), placeholder: t('from Resources -> Credentials') },
            ]}
            labelOf={(d) => d.label}
            idOf={(d) => d.id}
            empty={() => ({ label: '', jdbcUrl: '', username: '', credentialId: '' })}
            load={api.listDatabases}
            save={api.saveDatabase}
            remove={api.deleteDatabase}
          />
        )}
        {tab === 'knowledge' && <KnowledgePanel />}
        {tab === 'skills' && <SkillsPanel />}

        {tab === 'credentials' && <CredentialsPanel pushError={pushError} />}

        {tab === 'plugins' && <PluginsPanel pushError={pushError} />}
        {tab === 'settings' && <SettingsPanel pushError={pushError} />}
        {tab === 'storage' && <StoragePanel pushError={pushError} />}
        {tab === 'updates' && <UpdatesPanel />}
      </div>
    </div>
  )
}
