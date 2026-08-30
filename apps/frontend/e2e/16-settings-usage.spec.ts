import { expect, goTo, openApp, test } from './fixtures'
import { apiJson, openTab, settingControl, settingsSave } from './16-settings.helpers'

/**
 * The Usage page against the one setting that shapes it: usage.weekly-allowance-usd.
 *
 * What the page renders is environmental — it reads this machine's Claude Code transcripts, and a
 * CI runner (CONCENTUS_E2E_NO_CLI=1 points the backend at an empty home) has none, in which case
 * the page shows its honest note instead of the meter. So the meter is asserted on screen where
 * the transcripts exist and through the API in every case: the allowance the backend computes
 * is the same object the page draws, transcripts or not.
 */

interface Usage {
  available: boolean
  allowance?: { allowanceUsd: number; percent: number; state: string }
}

const KEY = 'usage.weekly-allowance-usd'

async function setAllowance(page: Parameters<typeof openTab>[0], value: string): Promise<void> {
  await openTab(page, 'Settings')
  await settingControl(page, KEY).fill(value)
  await settingsSave(page).click()
  await expect(page.getByText('Saved.')).toBeVisible()
}

test('the allowance set in Settings is what the Usage page meters against', async ({ page }) => {
  await openApp(page)
  // Fresh install: no allowance, so no meter — the page says where to set one, or that it has no transcripts.
  expect((await apiJson<Usage>(page, '/api/usage')).allowance).toBeUndefined()
  await goTo(page, 'Usage')
  const noTranscripts = page.getByText(/No Claude Code transcripts found/)
  const fallback = page.getByText(/Set your plan's weekly allowance for non-interactive use under Settings → Usage/)
  await expect(noTranscripts.or(fallback)).toBeVisible({ timeout: 15_000 })
  const measured = await fallback.isVisible()
  if (!measured) {
    test.info().annotations.push({
      type: 'note',
      description: 'No transcripts on this machine: the meter is asserted through the API, the page through its note.',
    })
  }

  await setAllowance(page, '50')
  const fifty = await apiJson<Usage>(page, '/api/usage')
  expect(fifty.allowance).toMatchObject({ allowanceUsd: 50, state: 'ok' })
  expect(fifty.allowance!.percent).toBeGreaterThanOrEqual(0)
  await goTo(page, 'Usage')
  if (measured) {
    const meter = page.getByText('Weekly allowance for runs')
    await expect(meter).toBeVisible()
    await expect(page.getByText(/of \$50\.00 —/)).toBeVisible()
    await expect(page.getByText(`${fifty.allowance!.percent}%`, { exact: true })).toBeVisible()
    await expect(fallback).toHaveCount(0)
  } else {
    await expect(noTranscripts).toBeVisible()
  }

  // Raised: the ceiling follows, on the next visit.
  await setAllowance(page, '80')
  expect((await apiJson<Usage>(page, '/api/usage')).allowance).toMatchObject({ allowanceUsd: 80 })
  await goTo(page, 'Usage')
  if (measured) {
    await expect(page.getByText(/of \$80\.00 —/)).toBeVisible()
    await expect(page.getByText(/of \$50\.00 —/)).toHaveCount(0)
  }

  // Blank turns the meter off again.
  await setAllowance(page, '')
  expect((await apiJson<Usage>(page, '/api/usage')).allowance).toBeUndefined()
  await goTo(page, 'Usage')
  if (measured) {
    await expect(fallback).toBeVisible()
    await expect(page.getByText('Weekly allowance for runs')).toHaveCount(0)
  }
})
