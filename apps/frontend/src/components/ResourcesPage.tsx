import { useState } from 'react'
import { api } from '../api/client.ts'
import type { DatabaseDef, FacadeProfile, LibraryAgent, McpDef } from '../api/types.ts'
import { DEFAULT_MAX_TOKENS, DEFAULT_MODEL, EFFORT_OPTIONS } from '../constants.ts'
import { CredentialsPanel } from './CredentialsPanel.tsx'
import { CrudPanel } from './CrudPanel.tsx'
import { KnowledgePanel } from './KnowledgePanel.tsx'
import { McpCatalog } from './McpCatalog.tsx'
import { McpClaudeActions } from './McpClaudeActions.tsx'
import { McpJsonEditor } from './McpJsonEditor.tsx'
import { ModelField } from './ModelField.tsx'
import { PluginsPanel } from './PluginsPanel.tsx'
import { SkillsPanel } from './SkillsPanel.tsx'
import { StoragePanel } from './StoragePanel.tsx'
import { UpdatesPanel } from './UpdatesPanel.tsx'
import { shellBridge } from '../api/shell.ts'
import styles from './resources.module.scss'

type Tab = 'agents' | 'mcp' | 'facades' | 'databases' | 'knowledge' | 'skills' | 'plugins' | 'variables' | 'credentials' | 'storage' | 'updates'

export function ResourcesPage({ pushError }: { pushError: (m: string) => void }) {
  const [tab, setTab] = useState<Tab>('agents')
  // Remounts the MCP CrudPanel after a catalog add, so the new definition appears in its list —
  // the panel loads on mount and has no other way to be told.
  const [mcpListVersion, setMcpListVersion] = useState(0)

  return (
    <div className={styles.resources}>
      <div className={styles.tabs}>
        <button className={tab === 'agents' ? styles.active : ''} onClick={() => setTab('agents')}>
          Agents
        </button>
        <button className={tab === 'mcp' ? styles.active : ''} onClick={() => setTab('mcp')}>
          MCP Servers
        </button>
        <button
          className={tab === 'facades' ? styles.active : ''}
          onClick={() => setTab('facades')}
          title="What an independent worker may reach through its MCP facade: which tools, whether writes are blocked (read-only) or simulated (dry-run). Enforced by the backend on every call."
        >
          Facades
        </button>
        <button
          className={tab === 'databases' ? styles.active : ''}
          onClick={() => setTab('databases')}
        >
          Databases
        </button>
        <button
          className={tab === 'knowledge' ? styles.active : ''}
          onClick={() => setTab('knowledge')}
        >
          Knowledge
        </button>
        <button className={tab === 'skills' ? styles.active : ''} onClick={() => setTab('skills')}>
          Skills
        </button>
        <button
          className={tab === 'plugins' ? styles.active : ''}
          onClick={() => setTab('plugins')}
          title="Claude Code plugins installed on this machine. Each agent picks which ones its runs load."
        >
          Plugins
        </button>
        <button
          className={tab === 'variables' ? styles.active : ''}
          onClick={() => setTab('variables')}
          title="Values substituted into every flow's prompts as {{NAME}} when a run starts. A flow can override any of them — or add its own — in its settings."
        >
          Variables
        </button>
        <button
          className={tab === 'credentials' ? styles.active : ''}
          onClick={() => setTab('credentials')}
        >
          Credentials
        </button>
        <button
          className={tab === 'storage' ? styles.active : ''}
          onClick={() => setTab('storage')}
        >
          Storage
        </button>
        {shellBridge() && (
          // Only inside the desktop shell: a browser tab has no app to update.
          <button
            className={tab === 'updates' ? styles.active : ''}
            onClick={() => setTab('updates')}
          >
            Updates
          </button>
        )}
      </div>

      <div className={styles.tabBody}>
        {tab === 'agents' && (
          <CrudPanel<LibraryAgent>
            title="Agents"
            fields={[
              { key: 'name', label: 'Name' },
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
              { key: 'effort', label: 'Effort', type: 'select', options: [...EFFORT_OPTIONS] },
              { key: 'maxTokens', label: 'Max tokens', type: 'number' },
              { key: 'systemPrompt', label: 'System prompt', type: 'textarea' },
            ]}
            labelOf={(a) => a.name}
            idOf={(a) => a.id}
            empty={() => ({ name: '', model: DEFAULT_MODEL, effort: 'high', maxTokens: DEFAULT_MAX_TOKENS, systemPrompt: '' })}
            load={api.listAgents}
            save={api.saveAgent}
            remove={api.deleteAgent}
          />
        )}

        {tab === 'mcp' && (
          <>
          {/* Padded wrapper: these render above the CRUD grid, and without it their collapse
              headers sat glued to the window's left edge. */}
          <div className={styles.tabExtras}>
            <McpCatalog onAdded={() => setMcpListVersion((v) => v + 1)} />
            <McpJsonEditor onSaved={() => setMcpListVersion((v) => v + 1)} />
          </div>
          <CrudPanel<McpDef>
            key={mcpListVersion}
            title="MCP Servers"
            fields={[
              { key: 'name', label: 'Name', placeholder: 'linear' },
              { key: 'url', label: 'URL', placeholder: 'https://mcp.linear.app/mcp' },
              { key: 'credentialId', label: 'Credential id (optional)', placeholder: 'from Resources -> Credentials' },
              {
                key: 'authHeader',
                label: 'Send token in',
                type: 'select',
                options: ['', 'PRIVATE-TOKEN'],
              },
            ]}
            labelOf={(m) => m.name}
            idOf={(m) => m.id}
            empty={() => ({ name: '', url: '', credentialId: '', authHeader: '' })}
            load={api.listMcpDefs}
            save={api.saveMcpDef}
            remove={api.deleteMcpDef}
            extra={(m) => (
              <McpClaudeActions
                name={m.name}
                url={m.url}
                credentialId={m.credentialId}
                authHeader={m.authHeader}
              />
            )}
          />
          </>
        )}

        {tab === 'variables' && (
          <CrudPanel<import('../api/types.ts').Variable>
            title="Variables"
            fields={[
              { key: 'name', label: 'Name (used as {{NAME}} in prompts)', placeholder: 'COMPANY' },
              { key: 'value', label: 'Value', placeholder: 'ACME S.L.' },
              { key: 'description', label: 'Description (optional)', placeholder: 'Legal company name' },
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
            title="Facade profiles"
            fields={[
              { key: 'name', label: 'Name', placeholder: 'reader' },
              { key: 'description', label: 'Description', placeholder: 'Read-only lookups for support workers' },
              {
                key: 'tools',
                label: 'Allowed tools',
                render: (value, onChange) => (
                  <label className={styles.field}>
                    <span title="Case-insensitive substrings, one per line — 'contact' covers create_contact and list_contacts. Empty exposes every tool the worker's MCP nodes wire in (writes still gated below).">
                      Allowed tools (one substring per line, empty = all) ⓘ
                    </span>
                    <textarea
                      rows={4}
                      placeholder={'contact\ninvoice'}
                      value={Array.isArray(value) ? (value as string[]).join('\n') : ''}
                      onChange={(e) =>
                        onChange(e.target.value.split('\n').map((s) => s.trim()).filter(Boolean))
                      }
                    />
                  </label>
                ),
              },
              {
                key: 'readOnly',
                label: 'Read-only',
                render: (value, onChange) => (
                  <label
                    className={styles.field}
                    title="Write-shaped tools (create_*, send_*, anything not clearly a read) are not shown to the worker and refuse to run. The strictest setting; wins over dry-run."
                  >
                    <span>
                      <input
                        type="checkbox"
                        checked={Boolean(value)}
                        onChange={(e) => onChange(e.target.checked)}
                      />{' '}
                      Read-only — writes are blocked outright ⓘ
                    </span>
                  </label>
                ),
              },
              {
                key: 'dryRun',
                label: 'Dry run',
                render: (value, onChange) => (
                  <label
                    className={styles.field}
                    title="Writes are visible and callable, but the facade answers 'DRY RUN — nothing was executed' and the worker reports the action as proposed. On by default: writes only really execute when you deliberately untick this."
                  >
                    <span>
                      <input
                        type="checkbox"
                        checked={value === undefined || value === null ? true : Boolean(value)}
                        onChange={(e) => onChange(e.target.checked)}
                      />{' '}
                      Dry-run writes — simulate instead of executing ⓘ
                    </span>
                  </label>
                ),
              },
            ]}
            labelOf={(p) => p.name}
            idOf={(p) => p.id}
            empty={() => ({ name: '', description: '', tools: [], readOnly: false, dryRun: true })}
            load={api.listFacadeProfiles}
            save={api.saveFacadeProfile}
            remove={api.deleteFacadeProfile}
          />
        )}

        {tab === 'databases' && (
          <CrudPanel<DatabaseDef>
            title="Databases"
            fields={[
              { key: 'label', label: 'Label' },
              { key: 'jdbcUrl', label: 'JDBC URL', placeholder: 'jdbc:postgresql://host:5432/db' },
              { key: 'username', label: 'Username' },
              { key: 'credentialId', label: 'Credential id', placeholder: 'from Resources -> Credentials' },
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
        {tab === 'storage' && <StoragePanel pushError={pushError} />}
        {tab === 'updates' && <UpdatesPanel />}
      </div>
    </div>
  )
}
