import { expect, test as base, type Page } from '@playwright/test'

/**
 * The shared test: every page it hands out records uncaught exceptions, and the test fails if any
 * were thrown — a blank panel after a TypeError otherwise passes any assertion that never looks at
 * it. Deliberately `pageerror` only, not console.error: the backend legitimately answers 4xx on
 * some probes (an unconfigured Ollama, say) and the UI logging that is not a bug.
 */
export const test = base.extend<{ page: Page }>({
  page: async ({ page }, use) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(String(error)))
    await use(page)
    expect(errors, 'uncaught page errors during the test').toEqual([])
  },
})

export { expect }

/**
 * Loads the app and waits until the Flows dashboard shows its TRUE state.
 *
 * The subtlety this hides: while /api/flows is in flight the page renders the same "No flows yet"
 * card a genuinely empty install shows — loading and empty are indistinguishable on screen. So
 * the answer itself is captured (the listener is armed before goto, or a fast response slips
 * past), and what the wait asserts depends on what the backend actually said: cards when flows
 * exist, the empty card when none do. Only then are counts and clicks deterministic.
 */
export async function openApp(page: Page): Promise<void> {
  const flowsAnswer = page.waitForResponse(
    (r) => r.url().includes('/api/flows') && r.request().method() === 'GET',
  )
  await page.goto('/')
  const flows = (await (await flowsAnswer).json()) as unknown[]
  if (flows.length === 0) {
    await expect(page.getByText('No flows yet')).toBeVisible()
  } else {
    await expect(page.getByRole('article').first()).toBeVisible()
  }
}

/** The header navigation. `exact` matters: the Studio toolbar has a "← Flows" button too. */
export async function goTo(page: Page, view: 'Flows' | 'Studio' | 'Resources' | 'Usage'): Promise<void> {
  await page.getByRole('button', { name: view, exact: true }).click()
}

/** A flow card on the Flows page, found by its exact name. */
export function flowCard(page: Page, name: string) {
  return page.getByRole('article').filter({ has: page.getByRole('heading', { name, exact: true }) })
}
