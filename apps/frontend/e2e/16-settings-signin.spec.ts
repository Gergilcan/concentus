import { expect, openApp, test } from './fixtures'
import { apiCall, apiJson, ENTERPRISE_PORT, onLicensed, openTab } from './16-settings.helpers'

/**
 * Sign-in providers, under Members: the preset cards, the redirect URI every registration needs,
 * and what holds a registration back — no client id on any tier, and the license on a free one.
 *
 * The custom issuer is INACTIVE only on a Team license (16-settings-team.spec.ts); a free install
 * shows it enabled and is refused by the API instead, which is what the first test pins down.
 */

interface ProvidersList {
  providers: Array<{ id: string; enabled: boolean; clientId: string; hasSecret: boolean }>
  redirectUri: string
}

const PRESETS = ['Microsoft', 'Google', 'Discord', 'your organization']

/** One provider's card, by the heading that names it. */
function card(page: Parameters<typeof openTab>[0], name: string) {
  return page.locator('section').filter({ has: page.getByRole('heading', { name, exact: true }) })
}

test('the preset cards, the redirect URI to copy, and the refusals for a registration without a client id', async ({ page, backend }) => {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await openApp(page)
  await openTab(page, 'Members')
  await expect(page.getByRole('heading', { name: 'Sign-in providers' })).toBeVisible()
  for (const name of PRESETS) await expect(card(page, name)).toHaveCount(1)
  await expect(page.locator('section').filter({ has: page.getByLabel('Client id') })).toHaveCount(4)
  // Only Microsoft asks for a directory, only the custom issuer for an issuer.
  await expect(card(page, 'Microsoft').getByLabel('Directory (tenant)')).toBeVisible()
  await expect(page.getByLabel('Directory (tenant)')).toHaveCount(1)
  await expect(card(page, 'your organization').getByLabel('Issuer')).toBeVisible()
  await expect(page.getByLabel('Issuer')).toHaveCount(1)

  // The redirect URI: computed from the request, shown verbatim, and copyable.
  const list = await apiJson<ProvidersList>(page, '/api/account/providers')
  expect(list.redirectUri).toBe(`${backend.baseURL}/api/account/oidc/callback`)
  await expect(page.locator('code', { hasText: '/api/account/oidc/callback' })).toHaveText(list.redirectUri)
  await page.getByRole('button', { name: 'Copy', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Copied' })).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(list.redirectUri)

  // No client id: every card holds its Save back and says why.
  for (const name of PRESETS) {
    const save = card(page, name).getByRole('button', { name: 'Save and offer it' })
    await expect(save, name).toBeDisabled()
    await expect(save, name).toHaveAttribute('title', 'A client id and a secret are what make the button work.')
  }

  // A free install marks nothing inactive; the API is what refuses a registration here.
  await expect(page.getByText('Enterprise — inactive')).toHaveCount(0)
  await expect(card(page, 'your organization').getByLabel('Client id')).toBeEnabled()
  const refused = await apiCall(page, 'PUT', '/api/account/providers', {
    id: 'generic', enabled: true, clientId: '', clientSecret: '', tenant: '', issuer: '', displayName: '',
  })
  expect(refused.status()).toBe(409)
  expect(((await refused.json()) as { error: string }).error).toContain('SSO providers are an enterprise feature')
})

test('with an enterprise license a preset is registered and offered, its secret never read back, and the custom issuer is open', async ({ browser }, testInfo) => {
  test.setTimeout(150_000)
  await onLicensed(browser, 'enterprise', ENTERPRISE_PORT + testInfo.parallelIndex, async ({ page }) => {
    await openTab(page, 'Members')
    const custom = card(page, 'your organization')
    await expect(custom.getByText('Enterprise — inactive')).toHaveCount(0)
    await expect(custom.getByLabel('Client id')).toBeEnabled()
    await expect(custom.getByLabel('Issuer')).toBeEnabled()

    // Discord: the one preset with nothing to discover, so registering it touches no network.
    const discord = card(page, 'Discord')
    await expect(discord.getByText('not offered')).toBeVisible()
    await discord.getByLabel('Client id').fill('e2e-discord-client-id')
    await discord.getByLabel('Client secret').fill('e2e-discord-secret')
    const saved = page.waitForResponse((r) => r.url().includes('/api/account/providers') && r.request().method() === 'PUT')
    await discord.getByRole('button', { name: 'Save and offer it' }).click()
    expect((await saved).ok()).toBe(true)
    await expect(discord.getByText('on the sign-in screen')).toBeVisible()
    await expect(discord.getByLabel('Client secret')).toHaveAttribute('placeholder', '•••••••• (unchanged)')

    const list = await apiJson<ProvidersList>(page, '/api/account/providers')
    expect(list.providers.find((p) => p.id === 'discord')).toMatchObject({
      enabled: true, clientId: 'e2e-discord-client-id', hasSecret: true,
    })
    expect(JSON.stringify(list)).not.toContain('e2e-discord-secret')

    await discord.getByRole('button', { name: 'Stop offering it' }).click()
    await expect(discord.getByText('not offered')).toBeVisible()
    expect((await apiJson<ProvidersList>(page, '/api/account/providers')).providers.find((p) => p.id === 'discord'))
      .toMatchObject({ enabled: false, clientId: 'e2e-discord-client-id' })
  })
})
