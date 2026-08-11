import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { McpDef, McpNodeData, McpServerInfo } from '../api/types.ts'
import { CredentialField } from './CredentialField.tsx'
import { Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import { McpClaudeActions } from './McpClaudeActions.tsx'
import { McpOAuthConnect } from './McpOAuthConnect.tsx'
import { McpToolPicker } from './McpToolPicker.tsx'
import styles from './panels.module.scss'

interface Props {
  data: McpNodeData
  set: (patch: Record<string, unknown>) => void
}

export function McpInspector({ data, set }: Props) {
  const [servers, setServers] = useState<McpServerInfo[]>([])
  const [defs, setDefs] = useState<McpDef[]>([])

  useEffect(() => {
    api
      .listMcpServers()
      .then(setServers)
      .catch(() => setServers([]))
    api
      .listMcpDefs()
      .then(setDefs)
      .catch(() => setDefs([]))
  }, [])

  const useSaved = (id: string) => {
    const d = defs.find((x) => x.id === id)
    if (d) {
      set({
        name: d.name,
        url: d.url ?? '',
        credentialId: d.credentialId ?? '',
        authHeader: d.authHeader ?? '',
        command: d.command ?? '',
        args: d.args ?? [],
        env: d.env ?? {},
      })
    }
  }

  // Derived, not stored: a node with a command IS a stdio node, so the two can never disagree.
  // Only the moment between picking "Command" and typing one is local state.
  const [stdioMode, setStdioMode] = useState((data.command ?? '').trim() !== '')
  const isStdio = stdioMode || (data.command ?? '').trim() !== ''

  const selectExisting = (name: string) => {
    const s = servers.find((x) => x.name === name)
    if (s) set({ name: s.name, url: s.url })
  }

  return (
    <>
      {defs.length > 0 && (
        <SelectField label="Use saved server (from Resources)" value="" onChange={useSaved}>
          <option value="">— choose a saved MCP server —</option>
          {defs.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </SelectField>
      )}

      {servers.length > 0 && (
        <SelectField
          label="Select existing (from Claude Code)"
          value=""
          onChange={selectExisting}
          className={styles.libraryField}
        >
          <option value="">— choose a configured server —</option>
          {servers.map((s) => (
            <option key={s.name} value={s.name}>
              {s.name}
            </option>
          ))}
        </SelectField>
      )}

      <Field label="Name" value={data.name} onChange={(v) => set({ name: v })} />

      <SelectField
        label={
          <span title="Remote: an HTTP/SSE server reached by URL — most hosted MCPs. Command: a local process the run launches itself (npx, python…), speaking MCP over stdio — the kind whose README says 'add this to your mcp.json', like Google Ads. You can also paste that snippet under Resources → MCP Servers → Edit as JSON.">
            Transport ⓘ
          </span>
        }
        value={isStdio ? 'stdio' : 'http'}
        onChange={(v) => {
          setStdioMode(v === 'stdio')
          // Clearing the other side keeps a node from being half one thing and half the other.
          if (v === 'stdio') set({ url: '', credentialId: '' })
          else set({ command: '', args: [], env: {} })
        }}
      >
        <option value="http">Remote server (URL)</option>
        <option value="stdio">Command (stdio) — launched per run</option>
      </SelectField>

      {isStdio ? (
        <>
          <Field
            label="Command"
            placeholder="npx"
            value={data.command ?? ''}
            onChange={(v) => set({ command: v })}
          />
          <TextArea
            label="Arguments (one per line)"
            rows={3}
            placeholder={'-y\n@googleads/google-ads-mcp'}
            value={(data.args ?? []).join('\n')}
            onChange={(v) => set({ args: v.split('\n').map((s) => s.trim()).filter(Boolean) })}
          />
          <TextArea
            label={
              <span title="One KEY=value per line, passed to the launched process. A value of credential:<id> is resolved from Resources → Credentials when the run starts — that is how a developer token reaches the server without ever being stored in the flow.">
                Environment (KEY=value per line) ⓘ
              </span>
            }
            rows={3}
            placeholder={'GOOGLE_ADS_DEVELOPER_TOKEN=credential:cred_123\nGOOGLE_ADS_LOGIN_CUSTOMER_ID=1234567890'}
            value={Object.entries(data.env ?? {}).map(([k, v]) => `${k}=${v}`).join('\n')}
            onChange={(v) => {
              const env: Record<string, string> = {}
              for (const line of v.split('\n')) {
                const eq = line.indexOf('=')
                if (eq <= 0) continue
                env[line.slice(0, eq).trim()] = line.slice(eq + 1).trim()
              }
              set({ env })
            }}
          />
          <p className={styles.hint}>
            The run launches this command itself and talks to it over stdio — nothing to sign in
            to. The tool picker and OAuth below don't apply; the agent sees every tool the server
            exposes.
          </p>
        </>
      ) : (
        <>
          <Field label="URL" value={data.url} onChange={(v) => set({ url: v })} />
          <CredentialField
            label="Access token (optional)"
            value={data.credentialId}
            onChange={(v) => set({ credentialId: v })}
            what="this MCP server"
          />
        </>
      )}

      {!isStdio && (
      <FineTuning>
        <SelectField
          label="Send token in"
          value={data.authHeader ?? ''}
          onChange={(v) => set({ authHeader: v })}
        >
          <option value="">Authorization: Bearer … (most servers, GitHub)</option>
          <option value="PRIVATE-TOKEN">PRIVATE-TOKEN: … (GitLab)</option>
        </SelectField>
        <p className={styles.hint}>
          GitLab reads its project, group and personal access tokens from <code>PRIVATE-TOKEN</code>,
          without a <code>Bearer</code> prefix — sending one there just makes the token wrong. Most
          other servers, GitHub included, take the default.
        </p>
        <p className={styles.hint}>
          Neither provider needs an interactive sign-in: a <b>fine-grained PAT</b> (GitHub) or a{' '}
          <b>project/group access token</b> (GitLab) is a plain header, so it works headlessly — which
          is what makes this usable from Docker.
        </p>

        <McpToolPicker
          url={data.url}
          credentialId={data.credentialId}
          selected={data.tools ?? []}
          onChange={(tools) => set({ tools })}
        />
        <p className={styles.hint}>
          <b>This matters most on a self-hosted model.</b> A real server can expose hundreds of tools
          — Holded's has 338 — and each is a JSON schema in the prompt. That overflows the context
          before the conversation starts; the model server then truncates <i>silently</i>, and the
          model reports having only the few that survived. Claude has the room; a 14B does not, and it
          also picks better from eight tools than from three hundred. One agent per area — invoicing,
          contacts, treasury — each with its own short list, is what the delegation on this canvas is
          for.
        </p>
      </FineTuning>
      )}

      {!isStdio && <McpOAuthConnect url={data.url} />}

      {!isStdio && (
        <McpClaudeActions
          name={data.name}
          url={data.url}
          credentialId={data.credentialId}
          authHeader={data.authHeader}
        />
      )}
    </>
  )
}
