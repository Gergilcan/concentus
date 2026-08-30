import { expect, type Locator, type Page } from '@playwright/test'
import { openApp } from './fixtures'

/**
 * Canvas idioms shared by the 14-nodes-* specs: every node kind goes through the same motions —
 * add from the palette, open with a double-click, save, come back, read the saved JSON — and
 * writing them once keeps sixteen specs from drifting apart on how a block is opened.
 *
 * Locator rules learned the hard way: a select's `<label>` text content swallows every option's
 * text, so `getByLabel` cannot match it exactly — selects are found by role, `combobox`, whose
 * accessible name is the label alone. Inputs, textareas and checkboxes match by label as usual.
 */

/** The palette button for each kind, icon included — the icon is part of the accessible name. */
export const PALETTE = {
  input: '▶ Input / trigger',
  coordinator: '★ Coordinator',
  agent: '◆ Agent',
  verifier: '⚖ Verifier',
  merge: '⛙ Merge',
  mcp: '⚙ MCP server',
  repo: '🐙 Repository',
  sql: '🗄 SQL source (RAG)',
  api: '🌐 API / endpoint',
  flow: '🔗 Run another flow',
  mail: '✉ Send mail',
  knowledge: '📚 Knowledge base',
  condition: '⑂ Condition',
  foreach: '⟳ For each',
  note: '✎ Note',
  group: '▭ Group',
} as const

export type Kind = keyof typeof PALETTE

/** A short unique suffix, so resources created by a test never collide with another test's. */
export function stamp(): string {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

/**
 * Opens the app and starts a fresh, unsaved flow with this name on the canvas.
 *
 * The minimap is folded away first: it floats over the bottom-right corner of the pane, which is
 * exactly where the palette cascades new blocks to, and a block under it cannot be hovered or
 * grabbed. The choice is remembered per browser context, so once per test is enough.
 */
export async function newFlow(page: Page, name: string): Promise<void> {
  await openApp(page)
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByLabel('Flow name').fill(name)
  await expect(page.locator('.react-flow__node')).toHaveCount(0)
  const minimap = page.getByRole('button', { name: 'Hide the minimap' })
  if (await minimap.isVisible()) await minimap.click()
  await expect(page.getByRole('button', { name: 'Show the minimap' })).toBeVisible()
}

/** Brings the whole drawing on screen — a block the palette cascaded past the pane's edge is otherwise unreachable. */
export async function fitView(page: Page): Promise<void> {
  await page.locator('.react-flow__controls-fitview').click()
  await page.waitForTimeout(400)
}

/** Every node of one kind on the canvas — React Flow classes the wrapper by node type. */
export function nodesOf(page: Page, kind: Kind): Locator {
  return page.locator(`.react-flow__node.react-flow__node-${kind}`)
}

/**
 * Adds a block through the palette and returns it. Waits for the count to grow rather than
 * returning `.last()`: the palette adds and selects, and the new wrapper is the last one only
 * once React Flow has rendered it. Then fits the view, because the palette cascades each new
 * block to the right of the last and the fourth one is already past the pane's edge.
 */
export async function addNode(page: Page, kind: Kind): Promise<Locator> {
  const before = await nodesOf(page, kind).count()
  await page.getByRole('button', { name: PALETTE[kind] }).click()
  await expect(nodesOf(page, kind)).toHaveCount(before + 1)
  await fitView(page)
  return nodesOf(page, kind).nth(before)
}

/** One part of a card: the header's icon, title and badge, or the lines under it. */
export function part(node: Locator, which: 'icon' | 'title' | 'badge' | 'snippet' | 'meta'): Locator {
  return node.locator(`[class*="_${which}_"]`).first()
}

/** Double-clicks a block and returns its properties dialog, once it is on screen. */
export async function openInspector(page: Page, node: Locator): Promise<Locator> {
  await node.dblclick()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  return dialog
}

/**
 * Escape rather than the ✕: the dialog re-renders on every keystroke it forwards to the store,
 * and a click aimed at a button that is being replaced under the pointer lands on the backdrop
 * or on nothing. The key goes to the window, which is where the dialog listens.
 */
export async function closeInspector(page: Page): Promise<void> {
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

/** A select in the inspector, by the text of its label. */
export function select(dialog: Locator, label: string): Locator {
  return dialog.getByRole('combobox', { name: label, exact: true })
}

/** Unfolds the inspector's optional settings, where every "sensible default" field lives. */
export async function fineTuning(dialog: Locator): Promise<void> {
  const head = dialog.getByRole('button', { name: 'Fine-tuning' })
  if ((await head.getAttribute('aria-expanded')) !== 'true') await head.click()
  await expect(head).toHaveAttribute('aria-expanded', 'true')
}

export async function saveFlow(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  // Saved flows have a version history, and the Versions button turns on only then — the one
  // on-screen fact that says the save round-tripped.
  await expect(page.getByRole('button', { name: 'Versions' })).toBeEnabled()
}

/** Leaves the Studio and opens the named flow again from its card. */
export async function reopenFlow(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name: '← Flows' }).click()
  await page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name, exact: true }) })
    .getByRole('button', { name: 'Open' })
    .click()
  await expect(page.locator('.react-flow__node').first()).toBeVisible()
  // The opening fit-to-view animates; a double-click during it lands its two clicks on
  // different spots and opens nothing.
  await page.waitForTimeout(700)
}

export interface SavedNode {
  id: string
  type: string
  role?: string | null
  data: Record<string, unknown>
}

export interface SavedFlow {
  id: string
  name: string
  nodes: SavedNode[]
  edges: { id: string; source: string; target: string; sourceHandle?: string | null }[]
}

/** The flow as the backend holds it, found by name. */
export async function savedFlow(page: Page, name: string): Promise<SavedFlow> {
  const list = (await (await page.request.get('/api/flows')).json()) as SavedFlow[]
  const found = list.find((f) => f.name === name)
  if (!found) throw new Error(`No saved flow named ${name}`)
  return (await (await page.request.get(`/api/flows/${found.id}`)).json()) as SavedFlow
}

export function savedNode(flow: SavedFlow, type: string, role?: string): SavedNode {
  const node = flow.nodes.find((n) => n.type === type && (role === undefined || n.role === role))
  if (!node) throw new Error(`No saved ${type} node in ${flow.name}`)
  return node
}

/**
 * A write through the page's own session. Every write has to echo the CSRF cookie back in a
 * header, exactly as the app does — page.request shares the browser context's cookies, so the
 * session is already there and only the header is missing.
 */
export async function apiPost<T = Record<string, unknown>>(page: Page, path: string, body: unknown): Promise<T> {
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value
  const res = await page.request.post(path, {
    data: body,
    headers: token ? { 'X-XSRF-TOKEN': token } : {},
  })
  if (!res.ok()) throw new Error(`POST ${path} → ${res.status()} ${await res.text()}`)
  return (await res.json()) as T
}

/** A stored credential the credential pickers can offer; the id is what a node keeps. */
export async function createCredential(page: Page, label: string): Promise<string> {
  const created = await apiPost<{ id: string }>(page, '/api/credentials', { label, kind: 'token', value: 'e2e-secret' })
  return created.id
}

/** A block's main source (right) or target (left) handle, or a named second output. */
export function handle(node: Locator, which: 'source' | 'target' | 'error' | 'rejected' | 'else'): Locator {
  if (which === 'source') return node.locator('.react-flow__handle.source:not([data-handleid])')
  if (which === 'target') return node.locator('.react-flow__handle.target')
  return node.locator(`.react-flow__handle.source[data-handleid="${which}"]`)
}

/** Turns one of a block's hidden second outputs on through its chip, and checks the handle appeared. */
export async function showOutput(node: Locator, label: 'on error' | 'on rejected'): Promise<void> {
  await node.hover()
  await node.getByRole('button', { name: `+ ${label}` }).click()
  await expect(handle(node, label === 'on error' ? 'error' : 'rejected')).toHaveCount(1)
}

/** Puts an unwired second output away again by clicking its label. */
export async function hideOutput(node: Locator, label: 'on error' | 'on rejected'): Promise<void> {
  await node.getByRole('button', { name: label, exact: true }).click()
  await expect(handle(node, label === 'on error' ? 'error' : 'rejected')).toHaveCount(0)
}

/** Drags a wire from one handle to another the way a hand would, in steps React Flow can follow. */
export async function drag(page: Page, from: Locator, to: Locator): Promise<void> {
  const a = (await from.boundingBox())!
  const b = (await to.boundingBox())!
  const ax = a.x + a.width / 2
  const ay = a.y + a.height / 2
  const bx = b.x + b.width / 2
  const by = b.y + b.height / 2
  await page.mouse.move(ax, ay)
  await page.waitForTimeout(100)
  await page.mouse.down()
  await page.mouse.move(ax + 10, ay + 10, { steps: 3 })
  await page.mouse.move(bx, by, { steps: 12 })
  await page.mouse.up()
}

export function edges(page: Page): Locator {
  return page.locator('.react-flow__edge')
}

/** Cuts every wire on the canvas through the × each one shows, so a test can draw its own. */
export async function removeAllEdges(page: Page): Promise<void> {
  while ((await edges(page).count()) > 0) {
    const before = await edges(page).count()
    await page.getByTitle('Remove connection').first().click({ force: true })
    await expect(edges(page)).toHaveCount(before - 1)
  }
}

/**
 * Draws a wire that the canvas must refuse, and checks that it did: the wire count is the same
 * after the gesture as before it, once the canvas has had a moment to draw one if it were going to.
 */
export async function refuse(page: Page, from: Locator, to: Locator): Promise<void> {
  const before = await edges(page).count()
  await drag(page, from, to)
  await page.waitForTimeout(400)
  await expect(edges(page)).toHaveCount(before)
}

/** Moves a block by its header so the grab never lands on a handle, a chip or a label. */
export async function dragNodeBy(page: Page, node: Locator, dx: number, dy: number): Promise<void> {
  const box = (await node.boundingBox())!
  const x = box.x + box.width / 2
  const y = box.y + 12
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + 8, y + 8, { steps: 3 })
  await page.mouse.move(x + dx, y + dy, { steps: 12 })
  await page.mouse.up()
}

/** Drops a block so that its centre lands on the centre of a frame. */
export async function dropInto(page: Page, node: Locator, frame: Locator): Promise<void> {
  const f = (await frame.boundingBox())!
  const n = (await node.boundingBox())!
  await dragNodeBy(page, node, f.x + f.width / 2 - (n.x + n.width / 2), f.y + f.height / 2 - (n.y + 12))
  await page.waitForTimeout(300)
}

/**
 * A field in the inspector, by its label — through its role, never through `getByLabel`.
 *
 * A `<label>` wraps its control here, so the label's text content carries the control's own text
 * along: a select's every option, a textarea's initial value. `getByLabel` matches that text and
 * an exact "System prompt" finds nothing once the prompt has words in it. The accessible name a
 * role query reads is the label alone, whatever the control holds.
 */
export function field(dialog: Locator, label: string | RegExp): Locator {
  const by = { name: label, exact: typeof label === 'string' }
  return dialog
    .getByRole('textbox', by)
    .or(dialog.getByRole('spinbutton', by))
    .or(dialog.getByRole('checkbox', by))
    .or(dialog.getByRole('combobox', by))
}
