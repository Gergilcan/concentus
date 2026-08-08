import { useState } from 'react'
import { api } from '../api/client.ts'
import type { DatabaseDef, LibraryAgent, McpDef } from '../api/types.ts'
import { DEFAULT_MAX_TOKENS, DEFAULT_MODEL, EFFORT_OPTIONS } from '../constants.ts'
import { CredentialsPanel } from './CredentialsPanel.tsx'
import { CrudPanel } from './CrudPanel.tsx'
import { McpClaudeActions } from './McpClaudeActions.tsx'
import { ModelField } from './ModelField.tsx'
import styles from './resources.module.scss'

type Tab = 'agents' | 'mcp' | 'databases' | 'credentials'

export function ResourcesPage({ pushError }: { pushError: (m: string) => void }) {
  const [tab, setTab] = useState<Tab>('agents')

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
          className={tab === 'databases' ? styles.active : ''}
          onClick={() => setTab('databases')}
        >
          Databases
        </button>
        <button
          className={tab === 'credentials' ? styles.active : ''}
          onClick={() => setTab('credentials')}
        >
          Credentials
        </button>
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
          <CrudPanel<McpDef>
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
        {tab === 'credentials' && <CredentialsPanel pushError={pushError} />}
      </div>
    </div>
  )
}
