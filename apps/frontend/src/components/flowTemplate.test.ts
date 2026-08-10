import { describe, expect, it } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { normalizeImportedFlow } from './flowsDashboard.ts'
import { templateJson, toTemplate } from './flowTemplate.ts'

function flow(): BackendFlow {
  return {
    id: 'flow-1',
    name: 'Presupuestos por correo',
    mode: 'local',
    enabled: false,
    favorite: true,
    tags: ['mail', 'holded'],
    notifyWebhook: 'https://hooks.slack.com/services/T000/B000/secret',
    budgetUsd: 25,
    edges: [{ id: 'e1', source: 'in1', target: 'a1' }],
    nodes: [
      {
        id: 'in1',
        type: 'input',
        data: {
          kind: 'input',
          mode: 'mail',
          prompt: '',
          secret: 'hmac-shared-secret',
          mailHost: 'mail.miempresa.es',
          mailUsername: 'gerard@miempresa.es',
          mailCredentialId: 'cred_9',
          mailTenantId: 'tenant-1',
          mailClientId: 'client-1',
          mailFolder: 'Presupuestos',
        },
      },
      {
        id: 'a1',
        type: 'agent',
        role: 'coordinator',
        data: {
          kind: 'agent',
          name: 'Presupuestos',
          systemPrompt: 'Create draft estimates.',
          contextFolders: ['C:/Users/GILA7/projects/erp'],
          claudeMdPath: 'C:/Users/GILA7/projects/erp/CLAUDE.md',
          skillIds: ['skill_local_1'],
        },
      },
      {
        id: 'm1',
        type: 'mcp',
        data: { kind: 'mcp', name: 'holded', url: 'https://mcp.holded.com/mcp', credentialId: 'cred_1' },
      },
      {
        id: 's1',
        type: 'sql',
        data: {
          kind: 'sql',
          label: 'clientes',
          jdbcUrl: 'jdbc:postgresql://db.miempresa.es/erp',
          username: 'erp_reader',
          credentialId: 'cred_2',
          query: 'select * from clients',
        },
      },
      {
        id: 'r1',
        type: 'repo',
        data: {
          kind: 'repo',
          provider: 'gitlab',
          url: 'https://gitlab.com/miempresa/erp.git',
          baseUrl: 'https://gitlab.miempresa.es',
          group: 'miempresa',
          only: ['miempresa/erp'],
          branch: 'main',
          credentialId: 'cred_3',
        },
      },
      {
        id: 'k1',
        type: 'knowledge',
        data: { kind: 'knowledge', label: 'manuales', baseId: 'kb_local_7', topK: 5 },
      },
    ],
  }
}

describe('toTemplate', () => {
  it('strips credentials, accounts, secrets and private infrastructure', () => {
    const t = toTemplate(flow())
    const json = templateJson(flow())

    // Nothing that identifies the author or their servers survives — not even as a blank key.
    for (const leak of [
      'cred_',
      'hmac-shared-secret',
      'miempresa',
      'gerard@',
      'tenant-1',
      'client-1',
      'GILA7',
      'skill_local_1',
      'kb_local_7',
      'hooks.slack.com',
    ]) {
      expect(json).not.toContain(leak)
    }
    expect(t.id).toBeUndefined()
    expect(t.favorite).toBeUndefined()
    expect(t.enabled).toBeUndefined()
    expect(t.notifyWebhook).toBeUndefined()
    expect(t.budgetUsd).toBeUndefined()
  })

  it('keeps the shape: prompts, queries, public endpoints, tags and edges', () => {
    const t = toTemplate(flow())

    expect(t.name).toBe('Presupuestos por correo')
    expect(t.tags).toEqual(['mail', 'holded'])
    expect(t.edges).toHaveLength(1)
    expect(t.nodes.find((n) => n.id === 'a1')?.data.systemPrompt).toBe('Create draft estimates.')
    expect(t.nodes.find((n) => n.id === 's1')?.data.query).toBe('select * from clients')
    // An MCP endpoint is usually a public service — it IS the template's value.
    expect(t.nodes.find((n) => n.id === 'm1')?.data.url).toBe('https://mcp.holded.com/mcp')
    expect(t.nodes.find((n) => n.id === 'in1')?.data.mailFolder).toBe('Presupuestos')
    expect(t.nodes.find((n) => n.id === 'r1')?.data.branch).toBe('main')
  })

  it('does not mutate the flow it was given', () => {
    const original = flow()
    toTemplate(original)

    expect(original.nodes.find((n) => n.id === 'm1')?.data.credentialId).toBe('cred_1')
    expect(original.notifyWebhook).toContain('hooks.slack.com')
  })

  it('round-trips through the existing import path', () => {
    // A template is just a flow JSON without the private parts, so the Import button on the
    // Flows page must accept it as-is.
    const imported = normalizeImportedFlow(JSON.parse(templateJson(flow())) as BackendFlow)

    expect(imported.id).toBeUndefined()
    expect(imported.name).toBe('Presupuestos por correo (imported)')
    expect(imported.nodes).toHaveLength(6)
  })
})
