import { expect, flowCard, goTo, openApp, test } from './fixtures'
import {
  addBlock,
  apiWrite,
  backToFlows,
  deleteFlow,
  nodes,
  save,
  startNewFlow,
  type SavedFlow,
} from './flows-helpers'

/**
 * The canvas from the keyboard, beyond 08's copy/paste arbitration: Ctrl+K reaches a flow by
 * name, Delete removes a selected wire (and Ctrl+Z brings it back), a save restarts the undo
 * history rather than letting Ctrl+Z walk back past what was written, and Tidy lays the members
 * of a frame out INSIDE it — asserted on the saved JSON, which is what every other client reads.
 *
 * Ctrl+S is not a shortcut of this application (Save is the toolbar button only), so nothing
 * here presses it.
 */

/** A card's width on the canvas, fixed in nodes.module.scss; its height is measured. */
const NODE_W = 214

test('Ctrl+K opens the command palette, and a flow can be reached by typing its name', async ({ page }) => {
  await openApp(page)
  await page.keyboard.press('Control+k')
  const palette = page.getByRole('dialog', { name: 'Command palette' })
  await expect(palette).toBeVisible()
  // exact: the list of matches is labelled "Commands", which a substring match also finds.
  await palette.getByLabel('Command', { exact: true }).fill('Open Docs from code')
  await expect(palette.getByRole('option').first()).toContainText('Open Docs from code')
  await page.keyboard.press('Enter')

  await expect(palette).toHaveCount(0)
  await expect(page.getByLabel('Flow name')).toHaveValue('Docs from code')
  await expect(nodes(page)).toHaveCount(2)
  await backToFlows(page)
})

test('Delete removes a selected wire, and undo puts it back', async ({ page }) => {
  await openApp(page)
  await startNewFlow(page, 'E2E wire flow')
  await addBlock(page, '▶ Input / trigger')
  await addBlock(page, '★ Coordinator')
  const edges = page.locator('.react-flow__edge')
  await expect(edges).toHaveCount(1)
  // Adding starts a fit-to-view animation; a click on a moving wire lands on the pane.
  await page.waitForTimeout(700)

  // Off-centre on purpose: the wire's × button sits at its midpoint, and clicking that would
  // delete the wire by the button rather than by the key this test is about.
  const box = (await edges.first().boundingBox())!
  await edges.first().click({ position: { x: box.width * 0.25, y: box.height / 2 } })
  await expect(edges.first()).toHaveClass(/selected/)
  await page.keyboard.press('Delete')
  await expect(edges).toHaveCount(0)
  await expect(nodes(page)).toHaveCount(2)

  await page.keyboard.press('Control+z')
  await expect(edges).toHaveCount(1)
})

test('a save restarts the undo history; after it, undo and redo work on the new edits', async ({ page }) => {
  await openApp(page)
  await startNewFlow(page, 'E2E undo flow')
  await addBlock(page, '▶ Input / trigger')
  await addBlock(page, '★ Coordinator')
  const undo = page.getByRole('button', { name: 'Undo' })
  const redo = page.getByRole('button', { name: 'Redo' })
  await expect(undo).toBeEnabled()

  await save(page)
  // What was saved is the new baseline: Ctrl+Z cannot walk back past a write, which would leave
  // the canvas disagreeing with the record without any button having been pressed.
  await expect(undo).toBeDisabled()
  await expect(redo).toBeDisabled()

  await addBlock(page, '◆ Agent')
  await expect(nodes(page)).toHaveCount(3)
  await page.keyboard.press('Control+z')
  await expect(nodes(page)).toHaveCount(2)
  await expect(redo).toBeEnabled()
  await page.keyboard.press('Control+y')
  await expect(nodes(page)).toHaveCount(3)

  await backToFlows(page)
  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, 'E2E undo flow')
})

test('Tidy lays a frame\'s members out inside the frame, and the saved JSON says so', async ({ page }) => {
  const NAME = 'E2E tidy flow'
  await openApp(page)
  // Two members stacked on the same spot inside a frame, and a block outside it — the shape a
  // canvas assembled by hand ends up in. `_pos` is absolute on the wire; `_parent` names the frame.
  const seeded = (await apiWrite(page, 'POST', '/api/flows', {
    name: NAME,
    nodes: [
      { id: 'g-1', type: 'group', data: { label: 'Frame', color: 'blue', _pos: { x: 0, y: 0 }, _size: { w: 480, h: 260 } } },
      { id: 'in-1', type: 'input', data: { mode: 'manual', prompt: '', _pos: { x: 40, y: 60 }, _parent: 'g-1' } },
      {
        id: 'a-1', type: 'agent', role: 'coordinator',
        data: { role: 'coordinator', name: 'Coordinator', model: 'claude-opus-4-8', systemPrompt: 'Say hello.', _pos: { x: 40, y: 60 }, _parent: 'g-1' },
      },
      {
        id: 'a-2', type: 'agent', role: 'subagent',
        data: { role: 'subagent', name: 'Outside', model: 'claude-opus-4-8', systemPrompt: 'Help.', _pos: { x: 900, y: 400 } },
      },
    ],
    edges: [
      { id: 'e-1', source: 'in-1', target: 'a-1' },
      { id: 'e-2', source: 'a-1', target: 'a-2' },
    ],
  })) as unknown as SavedFlow

  // The dashboard reads the flow list when it mounts (polling is the Studio's), so a flow made
  // behind its back is seen by leaving and coming back — the same as a person would.
  await goTo(page, 'Usage')
  await goTo(page, 'Flows')
  await expect(flowCard(page, NAME)).toBeVisible({ timeout: 10_000 })
  await flowCard(page, NAME).getByRole('button', { name: 'Open' }).click()
  await expect(nodes(page)).toHaveCount(4)
  await page.waitForTimeout(700)
  await page.getByRole('button', { name: '⌗ Tidy' }).click()
  // The layout animates for 400ms; saving mid-animation still writes the target positions, but
  // waiting keeps the screenshot on failure honest.
  await page.waitForTimeout(600)
  await save(page)

  const after = (await (await page.request.get(`/api/flows/${seeded.id}`)).json()) as SavedFlow
  const node = (id: string) => after.nodes.find((n) => n.id === id)!
  const at = (id: string) => node(id).data._pos as { x: number; y: number }
  const frame = { ...at('g-1'), ...(node('g-1').data._size as { w: number; h: number }) }

  // Un-stacked, still members, and each one within the frame the author drew.
  expect(at('in-1')).not.toEqual(at('a-1'))
  for (const id of ['in-1', 'a-1']) {
    expect(node(id).data._parent, `${id} still belongs to the frame`).toBe('g-1')
    const p = at(id)
    expect(p.x, `${id} left edge`).toBeGreaterThanOrEqual(frame.x)
    expect(p.y, `${id} top edge`).toBeGreaterThanOrEqual(frame.y)
    expect(p.x + NODE_W, `${id} right edge`).toBeLessThanOrEqual(frame.x + frame.w)
    expect(p.y + 60, `${id} bottom edge`).toBeLessThanOrEqual(frame.y + frame.h)
  }
  // The chain reads left to right inside the frame, and the outsider stayed outside it.
  expect(at('a-1').x).toBeGreaterThan(at('in-1').x)
  expect(node('a-2').data._parent).toBeUndefined()
  expect(at('a-2').x).toBeGreaterThanOrEqual(frame.x + frame.w)

  await backToFlows(page)
  page.on('dialog', (d) => void d.accept())
  await deleteFlow(page, NAME)
})
