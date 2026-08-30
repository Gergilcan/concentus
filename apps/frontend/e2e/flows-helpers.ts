import { expect, type Locator, type Page } from '@playwright/test'
import { cardAction, flowCard } from './fixtures'

/**
 * The gestures the 15-flows-* specs share: a flow born in the Studio, a block's dialog opened
 * and closed, the doctor read, a write made as the signed-in account. Kept here rather than in
 * fixtures.ts because they are about the Studio and its dialogs, not about getting a page past
 * the gate — and a spec that only needs the gate should not carry them.
 */

/** A saved flow as the backend returns it — the slice these specs read back. */
export interface SavedFlow {
  id: string
  name: string
  enabled?: boolean
  favorite?: boolean
  nodes: { id: string; type: string; role?: string | null; data: Record<string, unknown> }[]
  edges: { id: string; source: string; target: string; sourceHandle?: string | null }[]
}

export const nodes = (page: Page) => page.locator('.react-flow__node')

/** A fresh, unsaved flow on the canvas, named. */
export async function startNewFlow(page: Page, name: string): Promise<void> {
  // first(): a truly empty dashboard renders a second "+ New flow" inside the empty-state card.
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await expect(page.getByLabel('Flow name')).toHaveValue('Untitled flow')
  await page.getByLabel('Flow name').fill(name)
}

/** Adds a block from the palette by its button text ('▶ Input / trigger', '★ Coordinator', …). */
export async function addBlock(page: Page, label: string): Promise<void> {
  await page.getByRole('button', { name: label, exact: true }).click()
}

/**
 * Saves and returns what the backend stored. The node count on screen is what the user drew, so
 * it proves nothing about the save; the response is what the later steps key on (the id above
 * all — the webhook URL, the doctor and the versions all need one).
 */
export async function save(page: Page): Promise<SavedFlow> {
  const written = page.waitForResponse(
    (r) => /\/api\/flows$/.test(r.url()) && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  const response = await written
  expect(response.ok(), `Save answered ${response.status()}`).toBe(true)
  return (await response.json()) as SavedFlow
}

export async function backToFlows(page: Page): Promise<void> {
  await page.getByRole('button', { name: '← Flows' }).click()
  await expect(page.getByLabel('Search flows')).toBeVisible()
}

/**
 * Opens a block's properties by double-clicking it, found by a text it shows.
 *
 * <p>Retried: opening a flow starts a fit-to-view animation, and a double-click landing during it
 * puts its two clicks on two different spots and opens nothing (07-cron-builder learnt this the
 * hard way). Three attempts a second and a half apart cover the longest animation seen.
 */
export async function openInspector(page: Page, text: string): Promise<Locator> {
  const node = nodes(page).filter({ hasText: text }).first()
  await expect(node).toBeVisible()
  const dialog = page.getByRole('dialog')
  for (let attempt = 0; attempt < 3; attempt++) {
    await node.dblclick()
    try {
      await expect(dialog).toBeVisible({ timeout: 1500 })
      return dialog
    } catch {
      /* the canvas was still moving — again */
    }
  }
  await expect(dialog).toBeVisible()
  return dialog
}

export async function closeInspector(page: Page): Promise<void> {
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

/** What the doctor said, each finding as "area: message". */
export interface Doctor {
  errors: string[]
  warnings: string[]
}

/**
 * Runs ⚕ Check on the flow open in the Studio and reads its findings back.
 *
 * <p>The `cli` area is the one finding that is about the machine rather than the flow: an error
 * on a runner with neither a CLI sign-in nor an API key, a warning or nothing on a developer's
 * laptop. Callers that assert on errors drop it first — see {@link flowErrors}.
 */
export async function runDoctor(page: Page): Promise<Doctor> {
  await page.getByRole('button', { name: '⚕ Check' }).click()
  const dialog = page.getByRole('dialog', { name: /^Check — / })
  const clear = dialog.getByText('Nothing to fix')
  const rows = dialog.getByRole('list', { name: 'Findings' }).getByRole('listitem')
  await expect(clear.or(rows.first())).toBeVisible({ timeout: 20_000 })
  const out: Doctor = { errors: [], warnings: [] }
  for (const row of await rows.all()) {
    const [head, message] = await row.locator('span').allTextContents()
    const area = head.replace(/^[✖▲]\s*/, '').trim()
    ;(head.startsWith('✖') ? out.errors : out.warnings).push(`${area}: ${message}`)
  }
  await dialog.getByRole('button', { name: 'Close' }).click()
  await expect(dialog).toHaveCount(0)
  return out
}

/** The doctor's errors that are about the FLOW: the machine-dependent `cli` finding removed. */
export function flowErrors(doctor: Doctor): string[] {
  return doctor.errors.filter((e) => !e.startsWith('cli:'))
}

/**
 * A write to the API as the account this page is signed in as.
 *
 * <p>page.request shares the context's cookies, so the session is there; the CSRF token is the
 * part a browser adds by itself and an API call has to add by hand — it is a readable cookie,
 * echoed in a header, which is what proves the caller could read it.
 */
export async function apiWrite(
  page: Page,
  method: 'POST' | 'DELETE',
  path: string,
  data?: unknown,
): Promise<Record<string, unknown>> {
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  const response = await page.request.fetch(path, {
    method,
    headers: { 'X-XSRF-TOKEN': token },
    data,
  })
  expect(response.ok(), `${method} ${path} answered ${response.status()}: ${await response.text()}`).toBe(true)
  const body = await response.text()
  return body ? (JSON.parse(body) as Record<string, unknown>) : {}
}

/** Deletes a flow from its card. The caller has already armed `page.on('dialog')` to confirm. */
export async function deleteFlow(page: Page, name: string): Promise<void> {
  await cardAction(page, name, 'Delete')
  await expect(flowCard(page, name)).toHaveCount(0)
}

/** The smallest flow that compiles: a manual trigger wired to one coordinator, as the API takes it. */
export function minimalFlow(name: string, extra: Partial<SavedFlow> = {}): Omit<SavedFlow, 'id'> {
  return {
    name,
    nodes: [
      { id: 'in-1', type: 'input', data: { mode: 'manual', prompt: '', _pos: { x: 40, y: 120 } } },
      {
        id: 'a-1',
        type: 'agent',
        role: 'coordinator',
        data: { role: 'coordinator', name: 'Coordinator', model: 'claude-opus-4-8', systemPrompt: 'Say hello.', _pos: { x: 340, y: 120 } },
      },
    ],
    edges: [{ id: 'e-1', source: 'in-1', target: 'a-1' }],
    ...extra,
  }
}
