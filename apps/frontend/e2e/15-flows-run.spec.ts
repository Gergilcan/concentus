import { expect, flowCard, openApp, signedInRequest, test } from './fixtures'
import {
  addBlock,
  backToFlows,
  closeInspector,
  deleteFlow,
  openInspector,
  save,
  startNewFlow,
} from './flows-helpers'

/**
 * ▶ Run without a model, and what the UI says about it.
 *
 * <p>This suite never makes a model call, so the smallest compiling flow — a manual trigger with
 * no prompt, wired to one coordinator — is exactly right: it starts, has no instruction, and
 * waits for a human. Which of two honest answers the backend gives depends on the machine, so
 * the machine is asked rather than assumed (10-headless-cli does the same): with a Claude sign-in
 * the run exists and idles; without one (a CI runner) the run is refused before it exists, and
 * the refusal is on screen. Both are asserted as UI states — never as the outcome of a model.
 */

const NAME = 'E2E run flow'

test('the executions dock shows the run — or the refusal — with the controls in the right state', async ({
  page,
  baseURL,
  request,
}) => {
  const auth = await signedInRequest(request, baseURL!, '/api/auth/status')
  await openApp(page)
  await startNewFlow(page, NAME)
  await addBlock(page, '▶ Input / trigger')
  await addBlock(page, '★ Coordinator')
  await save(page)

  // The dock starts closed; a run does not open it, the rail does.
  await page.getByRole('button', { name: 'Show the executions panel' }).click()
  await expect(page.getByRole('heading', { name: 'Executions' })).toBeVisible()
  await expect(page.getByText(/No executions for this flow yet|No executions yet/)).toBeVisible()
  const compare = page.getByRole('button', { name: '⇄ Compare' })
  const replay = page.getByRole('button', { name: '⟲ Replay vs current' })
  await expect(compare).toBeDisabled()
  await expect(replay).toBeDisabled()

  await page.getByRole('button', { name: '▶ Run' }).click()

  if (!auth.authenticated) {
    // Nothing on this machine can execute an agent: the run never starts, and the backend's own
    // words are the toast rather than a bare status code.
    await expect(page.getByRole('alert')).toContainText('Not signed in')
    await expect(page.getByText(/No executions for this flow yet|No executions yet/)).toBeVisible()
    await expect(compare).toBeDisabled()
    await expect(replay).toBeDisabled()
  } else {
    // The run is listed under this flow, selected, and settles where a manual flow given no
    // input settles: waiting for a human. Never RUNNING for long — nothing was sent.
    const row = page.getByRole('button', { name: new RegExp(NAME) })
    await expect(row).toBeVisible({ timeout: 15_000 })
    await expect(row).toContainText(/IDLE|AWAITING_ANSWER|AWAITING_APPROVAL|ERROR/, { timeout: 30_000 })
    await expect(row).toContainText('v1')

    // The run detail: the console says why it is waiting, and the buttons match a settled run.
    await expect(page.getByText(/Send a command to start|Not signed in/)).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('button', { name: 'Stop' })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Stop' })).toHaveAttribute('title', 'Nothing is running to stop')
    await expect(page.getByRole('button', { name: '⟳ Retry' })).toBeVisible()
    await expect(page.getByRole('button', { name: '⟲ Resume' })).toBeVisible()
    await page.getByRole('button', { name: 'Timeline' }).click()
    await page.getByRole('button', { name: 'Output' }).click()

    // Golden / replay / compare: only that the controls exist and explain themselves. One run
    // has nothing to be compared with; a replay of an idle run has nothing to diverge on and
    // either paints (and can be hidden) or says why not — neither result is asserted.
    await expect(compare).toBeDisabled()
    await expect(compare).toHaveAttribute('title', /at least one other/)
    await expect(replay).toBeEnabled()
    await replay.click()
    const hide = page.getByRole('button', { name: '⟲ Hide replay' })
    await expect(hide.or(page.locator('[class*="err"]')).first()).toBeVisible()
    if (await hide.isVisible()) {
      await hide.click()
      await expect(replay).toBeVisible()
    }
    await row.getByRole('button', { name: 'Mark as golden reference' }).click()
    await expect(row.getByRole('button', { name: 'Unmark golden reference' })).toBeVisible()
    await expect(page.getByRole('button', { name: '⭐▶ Test current flow' })).toBeVisible()
  }

  await backToFlows(page)
  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, NAME)
})

test('the approval mode on the coordinator saves and comes back', async ({ page }) => {
  const APPROVED = 'E2E approval flow'
  await openApp(page)
  await startNewFlow(page, APPROVED)
  await addBlock(page, '▶ Input / trigger')
  await addBlock(page, '★ Coordinator')
  let inspector = await openInspector(page, 'Coordinator')
  await inspector.getByText('Fine-tuning').click()
  await inspector.getByLabel(/Permissions for this flow/).selectOption('approval')
  await closeInspector(page)
  await save(page)
  await backToFlows(page)

  // No run is started: an approval that never comes is not something a test waits for. What is
  // asserted is that the saved flow really carries the mode, read back from the API.
  await flowCard(page, APPROVED).getByRole('button', { name: 'Open' }).click()
  inspector = await openInspector(page, 'Coordinator')
  await inspector.getByText('Fine-tuning').click()
  await expect(inspector.getByLabel(/Permissions for this flow/)).toHaveValue('approval')
  await closeInspector(page)
  await backToFlows(page)

  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, APPROVED)
})
