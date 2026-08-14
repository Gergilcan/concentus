import { describe, expect, it } from 'vitest'
import type { McpDef } from '../api/types.ts'
import { formatServer, parseServerJson, serverObject } from './mcpJson.ts'

const stdio: McpDef = {
  id: 'mcp_1',
  name: 'Google Ads',
  url: '',
  credentialId: '',
  command: 'pipx',
  args: ['run', 'google-ads-mcp'],
  env: { DEVELOPER_TOKEN: 'credential:cred_1' },
}

const remote: McpDef = {
  id: 'mcp_2',
  name: 'GitLab',
  url: 'https://gitlab.com/api/v4/mcp',
  credentialId: 'cred_2',
  authHeader: 'PRIVATE-TOKEN',
}

describe('serverObject', () => {
  it('shows a local server as its command, arguments and environment', () => {
    expect(serverObject(stdio)).toEqual({
      command: 'pipx',
      args: ['run', 'google-ads-mcp'],
      env: { DEVELOPER_TOKEN: 'credential:cred_1' },
    })
  })

  it('keeps an empty env visible — it is where a local server’s variables get filled in', () => {
    expect(serverObject({ ...stdio, env: undefined })).toMatchObject({ env: {} })
  })

  it('shows a remote server as its URL and how the token reaches it', () => {
    expect(serverObject(remote)).toEqual({
      url: 'https://gitlab.com/api/v4/mcp',
      credentialId: 'cred_2',
      authHeader: 'PRIVATE-TOKEN',
    })
  })

  it('never shows the id or the name — the form owns those', () => {
    expect(Object.keys(serverObject(stdio))).not.toContain('name')
    expect(formatServer(stdio)).not.toContain('mcp_1')
  })

  it('survives a definition whose url came back null', () => {
    // What the backend stores for a stdio server, and what used to take the panel down.
    const nulled = { ...remote, url: null } as unknown as McpDef
    expect(serverObject(nulled)).toEqual({ url: '', credentialId: 'cred_2', authHeader: 'PRIVATE-TOKEN' })
  })
})

describe('parseServerJson', () => {
  it('keeps the id, so an edit updates the server instead of creating a second one', () => {
    const { def } = parseServerJson('{"command":"pipx","args":["run","x"]}', stdio)

    expect(def.id).toBe('mcp_1')
    expect(def.name).toBe('Google Ads')
    expect(def.command).toBe('pipx')
    expect(def.args).toEqual(['run', 'x'])
  })

  it('accepts a README snippet pasted whole, wrapper and all', () => {
    const snippet = `{
      "mcpServers": {
        "google-ads": { "command": "pipx", "args": ["run", "google-ads-mcp"], "env": { "TOKEN": "" } }
      }
    }`

    const { def } = parseServerJson(snippet, { ...stdio, name: 'Old name' })

    // The key in the snippet names the server: it is what the person pasting chose to call it.
    expect(def.name).toBe('google-ads')
    expect(def.env).toEqual({ TOKEN: '' })
  })

  it('refuses a snippet holding several servers rather than picking one', () => {
    const two = '{"mcpServers":{"a":{"url":"http://a"},"b":{"url":"http://b"}}}'

    expect(() => parseServerJson(two, stdio)).toThrow(/one at a time/)
  })

  it('clears the other transport, so a definition is never half command and half URL', () => {
    const { def } = parseServerJson('{"url":"https://example.test/mcp"}', stdio)

    expect(def.url).toBe('https://example.test/mcp')
    expect(def.command).toBe('')
    expect(def.args).toEqual([])
    expect(def.env).toEqual({})
  })

  it('says what is wrong in a sentence, because a bad paste is the normal case here', () => {
    expect(() => parseServerJson('{oops', stdio)).toThrow(/Not valid JSON/)
    expect(() => parseServerJson('[]', stdio)).toThrow(/Expected an object/)
    expect(() => parseServerJson('{"env":{}}', stdio)).toThrow(/"url".*or a "command"/)
    expect(() => parseServerJson('{"url":"http://x"}', { ...stdio, name: '' })).toThrow(/no name yet/)
  })

  it('points a pasted token at the credential store instead of storing it', () => {
    const { def, warnings } = parseServerJson(
      '{"url":"http://x","headers":{"Authorization":"Bearer sk-live-123"}}',
      remote,
    )

    expect(warnings.join(' ')).toMatch(/headers.*ignored.*Credentials/i)
    expect(JSON.stringify(def)).not.toContain('sk-live-123')
  })

  it('names any other key it dropped, rather than swallowing it', () => {
    const { warnings } = parseServerJson('{"url":"http://x","transport":"sse"}', remote)

    expect(warnings).toEqual(['"transport" was ignored.'])
  })

  it('says nothing about "type", which mcp.json snippets carry and this shape implies', () => {
    const { warnings } = parseServerJson('{"type":"stdio","command":"npx","args":["-y","x"]}', stdio)

    expect(warnings).toEqual([])
  })
})
