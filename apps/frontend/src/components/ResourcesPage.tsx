import { useState } from 'react'
import { api } from '../api/client.ts'
import type { DatabaseDef, LibraryAgent, McpDef } from '../api/types.ts'
import { CrudPanel } from './CrudPanel.tsx'
import { McpClaudeActions } from './McpClaudeActions.tsx'
import styles from './resources.module.scss'

type Tab = 'agents' | 'mcp' | 'databases'

export function ResourcesPage() {
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
      </div>

      <div className={styles.tabBody}>
        {tab === 'agents' && (
          <CrudPanel<LibraryAgent>
            title="Agents"
            fields={[
              { key: 'name', label: 'Name' },
              { key: 'model', label: 'Model', placeholder: 'claude-opus-4-8' },
              { key: 'effort', label: 'Effort', type: 'select', options: ['low', 'medium', 'high', 'xhigh', 'max'] },
              { key: 'maxTokens', label: 'Max tokens', type: 'number' },
              { key: 'systemPrompt', label: 'System prompt', type: 'textarea' },
            ]}
            labelOf={(a) => a.name}
            idOf={(a) => a.id}
            empty={() => ({ name: '', model: 'claude-opus-4-8', effort: 'high', maxTokens: 16000, systemPrompt: '' })}
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
              { key: 'tokenEnv', label: 'Token env var (optional)' },
            ]}
            labelOf={(m) => m.name}
            idOf={(m) => m.id}
            empty={() => ({ name: '', url: '', tokenEnv: '' })}
            load={api.listMcpDefs}
            save={api.saveMcpDef}
            remove={api.deleteMcpDef}
            extra={(m) => <McpClaudeActions name={m.name} url={m.url} tokenEnv={m.tokenEnv} />}
          />
        )}

        {tab === 'databases' && (
          <CrudPanel<DatabaseDef>
            title="Databases"
            fields={[
              { key: 'label', label: 'Label' },
              { key: 'jdbcUrl', label: 'JDBC URL', placeholder: 'jdbc:postgresql://host:5432/db' },
              { key: 'username', label: 'Username' },
              { key: 'passwordEnv', label: 'Password env var', placeholder: 'PGPASSWORD' },
            ]}
            labelOf={(d) => d.label}
            idOf={(d) => d.id}
            empty={() => ({ label: '', jdbcUrl: '', username: '', passwordEnv: '' })}
            load={api.listDatabases}
            save={api.saveDatabase}
            remove={api.deleteDatabase}
          />
        )}
      </div>
    </div>
  )
}
