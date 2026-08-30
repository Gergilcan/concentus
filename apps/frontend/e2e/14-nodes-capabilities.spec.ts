import { expect, test } from './fixtures'
import {
  addNode, closeInspector, createCredential, drag, edges, field, fineTuning, handle, newFlow, nodesOf, openInspector,
  part, refuse, removeAllEdges, reopenFlow, saveFlow, savedFlow, savedNode, select, stamp,
} from './nodes'

/**
 * The capabilities an agent is handed: an MCP server, remote or launched by the run, and a
 * repository. Both only ever feed a consumer — the canvas refuses a wire the other way.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('mcp: remote and command transports, token header, env lines; it feeds an agent and nothing else', async ({ page }) => {
  const NAME = 'E2E nodes · mcp'
  await newFlow(page, NAME)
  const cred = await createCredential(page, `E2E mcp token ${stamp()}`)
  const agent = await addNode(page, 'agent')
  const remote = await addNode(page, 'mcp')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(remote, 'icon')).toHaveText('⚙')
  await expect(part(remote, 'title')).toHaveText('github')
  await expect(part(remote, 'badge')).toHaveText('MCP')
  await expect(part(remote, 'snippet')).toHaveText('https://api.githubcopilot.com/mcp/')

  const dialog = await openInspector(page, remote)
  await expect(select(dialog, 'Transport ⓘ')).toHaveValue('http')
  await field(dialog, 'Name').fill('holded')
  await field(dialog, 'URL').fill('https://mcp.example.test/holded')
  await expect(part(remote, 'title')).toHaveText('holded')
  await expect(part(remote, 'snippet')).toHaveText('https://mcp.example.test/holded')
  await select(dialog, 'Access token (optional)').selectOption(cred)
  await expect(dialog.getByRole('button', { name: 'Sign in to this server' })).toBeVisible()
  await fineTuning(dialog)
  await select(dialog, 'Send token in ⓘ').selectOption('PRIVATE-TOKEN')
  // The picker reads the server's own list; a URL nobody serves is not something to open here.
  await expect(dialog.getByRole('button', { name: 'Choose tools…' })).toBeEnabled()
  await closeInspector(page)

  // A local process: the URL side goes away, the command side appears.
  const local = await addNode(page, 'mcp')
  await expect(edges(page)).toHaveCount(2)
  const second = await openInspector(page, local)
  await select(second, 'Transport ⓘ').selectOption('stdio')
  await expect(field(second, 'URL')).toHaveCount(0)
  await field(second, 'Name').fill('google-ads')
  await field(second, 'Command').fill('npx')
  await field(second, 'Arguments (one per line)').fill('-y\n@googleads/google-ads-mcp')
  const env = field(second, /^Environment/)
  await env.fill('GOOGLE_ADS_DEVELOPER_TOKEN=credential:abc\nLOGIN_CUSTOMER_ID=123')
  await env.fill('GOOGLE_ADS_DEVELOPER_TOKEN=credential:abc')
  await expect(second.getByText(/^Launched by the run itself/)).toBeVisible()
  await expect(part(local, 'snippet')).toHaveText('no url')
  await closeInspector(page)

  await refuse(page, handle(agent, 'source'), handle(remote, 'target'))
  await refuse(page, handle(remote, 'source'), handle(local, 'target'))
  await removeAllEdges(page)
  await drag(page, handle(remote, 'source'), handle(agent, 'target'))
  await expect(edges(page)).toHaveCount(1)

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const holded = await openInspector(page, nodesOf(page, 'mcp').filter({ hasText: 'holded' }))
  await expect(select(holded, 'Transport ⓘ')).toHaveValue('http')
  await expect(field(holded, 'URL')).toHaveValue('https://mcp.example.test/holded')
  await expect(select(holded, 'Access token (optional)')).toHaveValue(cred)
  await fineTuning(holded)
  await expect(select(holded, 'Send token in ⓘ')).toHaveValue('PRIVATE-TOKEN')
  await closeInspector(page)
  const ads = await openInspector(page, nodesOf(page, 'mcp').filter({ hasText: 'google-ads' }))
  await expect(select(ads, 'Transport ⓘ')).toHaveValue('stdio')
  await expect(field(ads, 'Command')).toHaveValue('npx')
  await expect(field(ads, 'Arguments (one per line)')).toHaveValue('-y\n@googleads/google-ads-mcp')
  await expect(field(ads, /^Environment/)).toHaveValue('GOOGLE_ADS_DEVELOPER_TOKEN=credential:abc')

  const saved = await savedFlow(page, NAME)
  const h = saved.nodes.find((n) => n.data.name === 'holded')!
  expect(h.data).toMatchObject({ url: 'https://mcp.example.test/holded', credentialId: cred, authHeader: 'PRIVATE-TOKEN' })
  const g = saved.nodes.find((n) => n.data.name === 'google-ads')!
  expect(g.data).toMatchObject({
    command: 'npx', args: ['-y', '@googleads/google-ads-mcp'], env: { GOOGLE_ADS_DEVELOPER_TOKEN: 'credential:abc' },
    url: '', credentialId: '',
  })
  expect(saved.edges).toEqual([expect.objectContaining({ source: h.id, target: savedNode(saved, 'agent').id })])
})

test('repository: provider, scope, token, branch and the self-hosted extras persist', async ({ page }) => {
  const NAME = 'E2E nodes · repo'
  await newFlow(page, NAME)
  const cred = await createCredential(page, `E2E gitlab token ${stamp()}`)
  const agent = await addNode(page, 'agent')
  const repo = await addNode(page, 'repo')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(repo, 'icon')).toHaveText('🐙')
  await expect(part(repo, 'title')).toHaveText('repo')
  await expect(part(repo, 'badge')).toHaveText('github')
  await expect(part(repo, 'snippet')).toHaveText('no url')

  const dialog = await openInspector(page, repo)
  await select(dialog, 'Provider').selectOption('gitlab')
  await expect(part(repo, 'icon')).toHaveText('🦊')
  await expect(part(repo, 'badge')).toHaveText('gitlab')
  await select(dialog, 'Provider token').selectOption(cred)
  // A whole group: the card says what it stands for instead of a URL it does not have.
  await select(dialog, 'Scope').selectOption('group')
  await field(dialog, 'Group path').fill('acme/backend')
  await expect(part(repo, 'title')).toHaveText('acme/backend')
  await expect(part(repo, 'snippet')).toHaveText('all repos in acme/backend')
  await expect(dialog.getByRole('button', { name: 'Select specific repositories' })).toBeEnabled()
  await expect(field(dialog, /^Branch \(blank/)).toBeVisible()
  await fineTuning(dialog)
  await field(dialog, 'Include archived repositories').check()
  await field(dialog, 'Server URL (self-hosted only)').fill('https://gitlab.example.test')
  await field(dialog, 'Mount path').fill('/workspace')
  // Back to one repository: the group is forgotten and the URL names the node.
  await select(dialog, 'Scope').selectOption('repo')
  await field(dialog, 'URL').fill('https://gitlab.example.test/acme/api')
  await expect(part(repo, 'title')).toHaveText('acme/api')
  await field(dialog, 'Branch').fill('develop')
  await closeInspector(page)

  // Nothing feeds a repository; it feeds the agent, which the palette already drew.
  await refuse(page, handle(agent, 'source'), handle(repo, 'target'))
  await expect(edges(page)).toHaveCount(1)

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const again = await openInspector(page, nodesOf(page, 'repo').first())
  await expect(select(again, 'Provider')).toHaveValue('gitlab')
  await expect(select(again, 'Scope')).toHaveValue('repo')
  await expect(select(again, 'Provider token')).toHaveValue(cred)
  await expect(field(again, 'URL')).toHaveValue('https://gitlab.example.test/acme/api')
  await expect(field(again, 'Branch')).toHaveValue('develop')
  await fineTuning(again)
  await expect(field(again, 'Server URL (self-hosted only)')).toHaveValue('https://gitlab.example.test')
  await expect(field(again, 'Mount path')).toHaveValue('/workspace')

  const saved = await savedFlow(page, NAME)
  expect(savedNode(saved, 'repo').data).toMatchObject({
    provider: 'gitlab', url: 'https://gitlab.example.test/acme/api', branch: 'develop', credentialId: cred,
    baseUrl: 'https://gitlab.example.test', mountPath: '/workspace', group: '', only: [], includeArchived: true,
  })
  expect(saved.edges).toEqual([expect.objectContaining({ source: savedNode(saved, 'repo').id, target: savedNode(saved, 'agent').id })])
})
