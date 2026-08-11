import { expect, goTo, openApp, test } from './fixtures'

/**
 * The Usage page reads this machine's Claude Code transcripts, so its content is environmental:
 * a developer machine shows the metering windows, a CI runner shows the no-transcripts note.
 * Both are the page working; the error state is the only wrong answer.
 */

test('settles on real data or the honest no-transcripts note', async ({ page }) => {
  await openApp(page)
  await goTo(page, 'Usage')

  await expect(
    page.getByText('Last 5 hours').or(page.getByText(/No Claude Code transcripts found/)),
  ).toBeVisible({ timeout: 15_000 })
})
