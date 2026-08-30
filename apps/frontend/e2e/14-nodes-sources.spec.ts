import { expect, test } from './fixtures'
import {
  addNode, apiPost, closeInspector, createCredential, edges, field, fineTuning, handle, newFlow, nodesOf, openInspector,
  part, refuse, reopenFlow, saveFlow, savedFlow, savedNode, select, showOutput, stamp,
} from './nodes'

/**
 * The context sources: a SQL query, an API, a knowledge base. Each is configured from what
 * Resources already holds where it can be — a database definition, a base — and read back.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('sql source: a saved database fills the connection; the query and row cap persist', async ({ page }) => {
  const NAME = 'E2E nodes · sql'
  await newFlow(page, NAME)
  const cred = await createCredential(page, `E2E db password ${stamp()}`)
  const db = await apiPost<{ id: string }>(page, '/api/databases', {
    label: `E2E warehouse ${stamp()}`, jdbcUrl: 'jdbc:postgresql://db.example.test:5432/warehouse', username: 'reader', credentialId: cred,
  })
  await addNode(page, 'coordinator')
  const sql = await addNode(page, 'sql')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(sql, 'icon')).toHaveText('🗄')
  await expect(part(sql, 'title')).toHaveText('db')
  await expect(part(sql, 'badge')).toHaveText('SQL')
  await expect(part(sql, 'snippet')).toHaveText('SELECT * FROM my_table LIMIT 20')

  const dialog = await openInspector(page, sql)
  await select(dialog, 'Use database (from Resources)').selectOption(db.id)
  await expect(field(dialog, 'JDBC URL')).toHaveValue('jdbc:postgresql://db.example.test:5432/warehouse')
  await expect(field(dialog, 'Username')).toHaveValue('reader')
  await expect(select(dialog, 'Password')).toHaveValue(cred)
  await field(dialog, 'Label').fill('orders')
  await field(dialog, 'SQL query').fill('SELECT id, total FROM orders LIMIT 5')
  await fineTuning(dialog)
  await field(dialog, 'Max rows').fill('7')
  await expect(part(sql, 'title')).toHaveText('orders')
  await expect(part(sql, 'snippet')).toHaveText('SELECT id, total FROM orders LIMIT 5')
  // The preview would open a connection to a host that does not exist; that it is offered is enough.
  await expect(dialog.getByRole('button', { name: '▷ Preview query' })).toBeEnabled()
  await closeInspector(page)

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const again = await openInspector(page, nodesOf(page, 'sql').first())
  await expect(field(again, 'Label')).toHaveValue('orders')
  await expect(field(again, 'JDBC URL')).toHaveValue('jdbc:postgresql://db.example.test:5432/warehouse')
  await expect(field(again, 'Username')).toHaveValue('reader')
  await expect(select(again, 'Password')).toHaveValue(cred)
  await expect(field(again, 'SQL query')).toHaveValue('SELECT id, total FROM orders LIMIT 5')
  await fineTuning(again)
  await expect(field(again, 'Max rows')).toHaveValue('7')
  expect(savedNode(await savedFlow(page, NAME), 'sql').data).toMatchObject({
    label: 'orders', jdbcUrl: 'jdbc:postgresql://db.example.test:5432/warehouse', username: 'reader', credentialId: cred,
    query: 'SELECT id, total FROM orders LIMIT 5', maxRows: 7,
  })
})

test('knowledge base: picks a base, caps the passages, and only ever feeds a consumer', async ({ page }) => {
  const NAME = 'E2E nodes · knowledge'
  await newFlow(page, NAME)
  const base = await apiPost<{ id: string }>(page, '/api/knowledge', { name: `E2E manuals ${stamp()}`, description: 'e2e' })
  const coord = await addNode(page, 'coordinator')
  const kb = await addNode(page, 'knowledge')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(kb, 'icon')).toHaveText('📚')
  await expect(part(kb, 'title')).toHaveText('knowledge')
  await expect(part(kb, 'badge')).toHaveText('KB')
  await expect(part(kb, 'snippet')).toHaveText('no base selected')

  const dialog = await openInspector(page, kb)
  await field(dialog, 'Label').fill('manuals')
  await select(dialog, 'Knowledge base ⓘ').selectOption(base.id)
  await fineTuning(dialog)
  await field(dialog, 'Passages to inject (top-K)').fill('3')
  await expect(part(kb, 'title')).toHaveText('manuals')
  await expect(part(kb, 'snippet')).toHaveText('top 3 passages')
  await closeInspector(page)

  // Two feeders cannot feed each other, and a consumer cannot feed a base.
  const sql = await addNode(page, 'sql')
  await expect(edges(page)).toHaveCount(2)
  await refuse(page, handle(kb, 'source'), handle(sql, 'target'))
  await refuse(page, handle(coord, 'source'), handle(kb, 'target'))

  await saveFlow(page)
  await reopenFlow(page, NAME)
  const again = await openInspector(page, nodesOf(page, 'knowledge').first())
  await expect(field(again, 'Label')).toHaveValue('manuals')
  await expect(select(again, 'Knowledge base ⓘ')).toHaveValue(base.id)
  await fineTuning(again)
  await expect(field(again, 'Passages to inject (top-K)')).toHaveValue('3')
  const saved = await savedFlow(page, NAME)
  expect(savedNode(saved, 'knowledge').data).toMatchObject({ label: 'manuals', baseId: base.id, topK: 3 })
  expect(saved.edges).toHaveLength(2)
})

const SPEC = JSON.stringify({
  openapi: '3.0.0',
  info: { title: 'Pets', version: '1' },
  servers: [{ url: 'https://pets.example.test/v1' }],
  paths: {
    '/pets': {
      get: { operationId: 'listPets', summary: 'List pets', responses: { '200': { description: 'ok' } } },
      post: {
        operationId: 'addPet', summary: 'Add a pet',
        requestBody: { content: { 'application/json': { schema: { type: 'object' } } } },
        responses: { '201': { description: 'ok' } },
      },
    },
  },
})

test('api: a single endpoint or an OpenAPI allowlist, the marketplace shortcut, an on-error output', async ({ page }) => {
  const NAME = 'E2E nodes · api'
  await newFlow(page, NAME)
  await addNode(page, 'coordinator')
  const api = await addNode(page, 'api')
  await expect(edges(page)).toHaveCount(1)
  await expect(part(api, 'icon')).toHaveText('🌐')
  await expect(part(api, 'title')).toHaveText('api')
  await expect(part(api, 'badge')).toHaveText('API')
  await expect(part(api, 'snippet')).toHaveText('no spec loaded')

  const dialog = await openInspector(page, api)
  await field(dialog, 'Label').fill('pets')
  // One endpoint typed by hand: the URL is the allowlist.
  await select(dialog, 'This node calls ⓘ').selectOption('endpoint')
  await expect(part(api, 'snippet')).toHaveText('no URL yet')
  await select(dialog, 'Method').selectOption('POST')
  await field(dialog, 'URL ⓘ').fill('https://ops.example.test/notify/{channel}')
  await field(dialog, 'What this endpoint does ⓘ').fill('Posts a message to a channel.')
  await field(dialog, 'The agent may send a JSON body').check()
  await expect(part(api, 'snippet')).toHaveText('POST https://ops.example.test/notify/{channel}')
  await field(dialog, 'Credential id ⓘ').fill('cred_e2e')
  await fineTuning(dialog)
  await field(dialog, 'Send token in (blank = Authorization: Bearer)').fill('X-Api-Key')
  await expect(field(dialog, /^OpenAPI spec URL/)).toHaveCount(0)

  // A spec: pasted, because the URL is not fetchable from here; the operations are ticked one by one.
  await select(dialog, 'This node calls ⓘ').selectOption('spec')
  await expect(part(api, 'snippet')).toHaveText('no spec loaded')
  await field(dialog, 'OpenAPI spec URL ⓘ').fill('https://pets.example.test/openapi.json')
  await expect(part(api, 'snippet')).toHaveText('no operations allowed yet')
  await field(dialog, 'Paste the spec (when the URL is not fetchable)').fill(SPEC)
  await dialog.getByRole('button', { name: 'Load operations' }).click()
  const getPets = dialog.getByRole('checkbox', { name: /GET\s*\/pets/ })
  const postPets = dialog.getByRole('checkbox', { name: /POST\s*\/pets/ })
  await expect(getPets).toBeVisible()
  await expect(field(dialog, 'Base URL (optional) ⓘ')).toHaveValue('https://pets.example.test/v1')
  await dialog.getByRole('button', { name: 'Allow all reads' }).click()
  await expect(getPets).toBeChecked()
  await expect(postPets).not.toBeChecked()
  await expect(part(api, 'snippet')).toHaveText('1 operation(s) allowed')
  await postPets.check()
  await expect(part(api, 'snippet')).toHaveText('2 operation(s) allowed')
  await postPets.uncheck()
  await expect(part(api, 'snippet')).toHaveText('1 operation(s) allowed')

  // The Marketplace shortcut opens its own picker on top; Escape closes only that one.
  await dialog.getByRole('button', { name: 'Use from Marketplace…' }).click()
  const picker = page.getByRole('dialog', { name: 'Use from Marketplace' })
  await expect(picker).toBeVisible()
  await expect(
    picker.getByText('No API has been published yet.').or(picker.getByRole('button', { name: 'Use', exact: true }).first()),
  ).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(picker).toHaveCount(0)
  await closeInspector(page)
  await showOutput(api, 'on error')

  await saveFlow(page)
  await reopenFlow(page, NAME)
  await expect(handle(nodesOf(page, 'api').first(), 'error')).toHaveCount(1)
  const again = await openInspector(page, nodesOf(page, 'api').first())
  await expect(select(again, 'This node calls ⓘ')).toHaveValue('spec')
  await expect(field(again, 'OpenAPI spec URL ⓘ')).toHaveValue('https://pets.example.test/openapi.json')
  await expect(field(again, 'Credential id ⓘ')).toHaveValue('cred_e2e')
  await expect(again.getByText('1 operation(s) currently allowed.')).toBeVisible()
  await fineTuning(again)
  await expect(field(again, 'Base URL (optional) ⓘ')).toHaveValue('https://pets.example.test/v1')
  await expect(field(again, 'Send token in (blank = Authorization: Bearer)')).toHaveValue('X-Api-Key')
  expect(savedNode(await savedFlow(page, NAME), 'api').data).toMatchObject({
    label: 'pets', mode: 'spec', specUrl: 'https://pets.example.test/openapi.json', specInline: SPEC,
    baseUrl: 'https://pets.example.test/v1', credentialId: 'cred_e2e', authHeader: 'X-Api-Key', ops: ['GET /pets'],
    url: 'https://ops.example.test/notify/{channel}', method: 'POST', description: 'Posts a message to a channel.',
    sendsBody: true, errorOutput: true,
  })
})
