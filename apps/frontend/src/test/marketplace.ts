import type { MarketplaceItem } from '../api/types.ts'

/** A published, global MCP item nobody installed — the shape every marketplace test starts from. */
export function mktItem(overrides: Partial<MarketplaceItem> = {}): MarketplaceItem {
  return {
    id: 'mkt_000000000001',
    kind: 'mcp',
    name: 'Linear',
    summary: 'Issues, projects and cycles',
    description: null,
    tags: [],
    version: 1,
    scope: 'global',
    organizationId: 'org_1',
    groupId: null,
    status: 'published',
    rejection: null,
    author: { userId: 'u1', email: 'ana@example.com' },
    payload: { name: 'linear', url: 'https://mcp.linear.app/mcp', auth: 'oauth' },
    icon: null,
    installs: 0,
    builtIn: false,
    createdAt: 1_000,
    updatedAt: 1_000,
    publishedAt: 1_000,
    approvedBy: null,
    installed: null,
    mine: false,
    canEdit: false,
    canCurate: false,
    ...overrides,
  }
}
