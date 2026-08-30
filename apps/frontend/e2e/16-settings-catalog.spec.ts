import * as fs from 'node:fs'
import { expect, openApp, signedInRequest, test } from './fixtures'
import {
  apiJson,
  openTab,
  reloadWorkspace,
  SETTINGS_CATALOG_PATH,
  settingControl,
  settingMeta,
  settingsSave,
} from './16-settings.helpers'

/**
 * The Settings tab against the catalog it renders (SettingsCatalog.java is the truth).
 *
 * Every key the backend serves has to appear with the control its type asks for, and the keys the
 * backend serves have to be the keys the Java file declares — so a setting added to the catalog
 * and forgotten by the screen, or the other way round, fails here rather than on a customer's
 * screen. Then one key of each type goes through the whole loop: change, Save, reload, back.
 *
 * On this backend nothing is withheld: it has no license, and the gates only bite on a Team one
 * (see 16-settings-team.spec.ts for the disabled rows).
 */

interface SettingEntry {
  key: string
  group: string
  label: string
  type: 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'LIST' | 'SECRET' | 'CHOICE'
  restartRequired: boolean
  options: string[]
  source: 'STORED' | 'CONFIGURED' | 'DEFAULT'
  value: string
  hasValue: boolean
  refusal: string | null
}

/** Every dotted, lowercase string literal in the Java catalog — which is exactly its keys. */
function catalogKeys(): string[] {
  const java = fs.readFileSync(SETTINGS_CATALOG_PATH, 'utf8')
  return [...new Set([...java.matchAll(/"([a-z]+(?:\.[a-z-]+)+)"/g)].map((m) => m[1]))]
}

test('every catalog key is rendered with the control its type asks for', async ({ page, request, backend }) => {
  const { settings } = (await signedInRequest(request, backend.baseURL, '/api/settings')) as unknown as {
    settings: SettingEntry[]
  }
  const keys = catalogKeys()
  expect(keys.length).toBeGreaterThanOrEqual(29)
  expect([...settings.map((s) => s.key)].sort()).toEqual([...keys].sort())
  // Every type the screen has a control for is exercised by at least one key.
  expect(new Set(settings.map((s) => s.type))).toEqual(new Set(['NUMBER', 'TEXT', 'BOOLEAN', 'CHOICE', 'SECRET']))

  await openApp(page)
  await openTab(page, 'Settings')
  await expect(page.locator('[id^="setting-"]')).toHaveCount(settings.length)

  for (const entry of settings) {
    const control = settingControl(page, entry.key)
    await expect(page.locator(`label[for="setting-${entry.key}"]`), entry.key).toHaveText(entry.label)
    const tag = await control.evaluate((el) => el.tagName.toLowerCase())
    const inputType = await control.getAttribute('type')
    switch (entry.type) {
      case 'NUMBER':
        expect([tag, inputType], entry.key).toEqual(['input', 'number'])
        break
      case 'TEXT':
      case 'LIST':
        expect([tag, inputType], entry.key).toEqual(['input', 'text'])
        break
      case 'SECRET':
        expect([tag, inputType], entry.key).toEqual(['input', 'password'])
        // Never read back: the API answers an empty value whatever is stored.
        expect(entry.value, entry.key).toBe('')
        break
      case 'BOOLEAN':
        expect(tag, entry.key).toBe('select')
        await expect(control.locator('option'), entry.key).toHaveText(['On', 'Off'])
        break
      case 'CHOICE':
        expect(tag, entry.key).toBe('select')
        expect(entry.options.length, entry.key).toBeGreaterThan(0)
        for (const option of entry.options) {
          await expect(control.locator(`option[value="${option}"]`), `${entry.key} ${option}`).toHaveCount(1)
        }
        break
    }
    // The restart mark, exactly where the catalog says and nowhere else.
    if (entry.restartRequired) await expect(settingMeta(page, entry.key), entry.key).toContainText('needs a restart')
    else await expect(settingMeta(page, entry.key), entry.key).not.toContainText('needs a restart')
    // Unlicensed: nothing is withheld, so every row is editable.
    expect(entry.refusal ?? null, entry.key).toBeNull()
    await expect(control, entry.key).toBeEnabled()
  }
})

const NUMBER = 'runs.max-concurrent' // restart required
const TEXT = 'knowledge.ocr-languages' // in effect now
const FLAG = 'management.otlp.metrics.export.enabled' // restart required; not withheld here
const CHOICE = 'local.permission-mode'
const SECRET = 'approvals.telegram.bot-token'
const SECRET_VALUE = '123456:e2e-bot-secret'

test('one key of each type survives Save and a reload, the note says whether a restart is needed, a secret is never echoed, and a cleared value falls back', async ({ page }) => {
  await openApp(page)
  await openTab(page, 'Settings')
  const before = (await apiJson<{ settings: SettingEntry[] }>(page, '/api/settings')).settings
  const was = (key: string) => before.find((s) => s.key === key)!

  await settingControl(page, NUMBER).fill('7')
  await settingControl(page, TEXT).fill('eng+spa')
  await settingControl(page, FLAG).selectOption('true')
  await settingControl(page, CHOICE).selectOption('plan')
  await settingControl(page, SECRET).fill(SECRET_VALUE)
  await expect(settingsSave(page)).toHaveText('Save 5 changes')
  const saved = page.waitForResponse((r) => r.url().includes('/api/settings') && r.request().method() === 'PUT')
  await settingsSave(page).click()
  expect((await saved).ok()).toBe(true)
  await expect(page.getByText('Saved.')).toBeVisible()
  // Two of the five are read once at start, so the note says so.
  await expect(page.getByText(/Restart Concentus for it to take effect/)).toBeVisible()

  await reloadWorkspace(page)
  await openTab(page, 'Settings')
  await expect(settingControl(page, NUMBER)).toHaveValue('7')
  await expect(settingControl(page, TEXT)).toHaveValue('eng+spa')
  await expect(settingControl(page, FLAG)).toHaveValue('true')
  await expect(settingControl(page, CHOICE)).toHaveValue('plan')
  await expect(settingMeta(page, NUMBER)).toContainText('set here')

  // The secret: masked, empty after the reload, only a placeholder says one is stored.
  const secret = settingControl(page, SECRET)
  await expect(secret).toHaveAttribute('type', 'password')
  await expect(secret).toHaveValue('')
  await expect(secret).toHaveAttribute('placeholder', '•••••••• (unchanged)')
  const after = (await apiJson<{ settings: SettingEntry[] }>(page, '/api/settings')).settings
  expect(after.find((s) => s.key === SECRET)).toMatchObject({ value: '', hasValue: true, source: 'STORED' })
  expect(JSON.stringify(after)).not.toContain(SECRET_VALUE)
  expect(after.find((s) => s.key === NUMBER)).toMatchObject({ value: '7', source: 'STORED' })

  // A key read live says so.
  await settingControl(page, TEXT).fill('eng')
  await expect(settingsSave(page)).toHaveText('Save 1 change')
  await settingsSave(page).click()
  await expect(page.getByText(/In effect now\./)).toBeVisible()

  // A blank clears the override: the deployment's own value — or the default — stands again.
  await settingControl(page, NUMBER).fill('')
  await settingControl(page, CHOICE).selectOption('')
  await settingsSave(page).click()
  await expect(page.getByText('Saved.')).toBeVisible()
  await expect(settingMeta(page, NUMBER)).not.toContainText('set here')
  await expect(settingControl(page, NUMBER)).toHaveValue(was(NUMBER).value)
  await expect(settingControl(page, CHOICE)).toHaveValue(was(CHOICE).value)
  const reset = (await apiJson<{ settings: SettingEntry[] }>(page, '/api/settings')).settings
  for (const key of [NUMBER, CHOICE]) {
    expect(reset.find((s) => s.key === key), key).toMatchObject({ value: was(key).value, source: was(key).source })
  }
})
