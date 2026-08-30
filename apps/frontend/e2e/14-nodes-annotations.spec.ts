import { expect, test } from './fixtures'
import {
  addNode, closeInspector, dropInto, edges, field, newFlow, nodesOf, openInspector, part, reopenFlow, saveFlow, savedFlow,
  savedNode, select,
} from './nodes'

/**
 * The two annotations — a sticky note and a frame — drawn for whoever reads the canvas next and
 * never for the run: no handles, no wires. Plus the gestures every block shares: Delete, the
 * inspector's own Delete, and the undo that brings a block back.
 */
test.describe.configure({ timeout: 90_000 })
test.use({ viewport: { width: 1600, height: 1000 } })

test('note: text and colour persist, it takes no wires, and undo brings it back', async ({ page }) => {
  const NAME = 'E2E nodes · note'
  await newFlow(page, NAME)
  await addNode(page, 'coordinator')
  const note = await addNode(page, 'note')
  await expect(edges(page)).toHaveCount(0)
  await expect(note).toContainText('Double-click to write')
  await expect(note.locator('.react-flow__handle')).toHaveCount(0)

  const dialog = await openInspector(page, note)
  await field(dialog, 'Text').fill('Budget: 5 €/day\nAsk Gerard before raising it.')
  for (const colour of ['yellow', 'blue', 'green', 'pink'] as const) {
    await select(dialog, 'Colour').selectOption(colour)
    await expect(note.locator(`[class*="tint_${colour}"]`)).toHaveCount(1)
  }
  await expect(dialog.getByText('Notes are for people.')).toBeVisible()
  await closeInspector(page)
  await expect(note).toContainText('Budget: 5 €/day')

  // Delete removes it; Ctrl+Z brings it back with its text.
  await note.click()
  await expect(note).toHaveClass(/selected/)
  await page.keyboard.press('Delete')
  await expect(nodesOf(page, 'note')).toHaveCount(0)
  await page.keyboard.press('Control+z')
  await expect(nodesOf(page, 'note')).toHaveCount(1)
  await expect(nodesOf(page, 'note')).toContainText('Ask Gerard before raising it.')

  await saveFlow(page)
  await reopenFlow(page, NAME)
  await expect(nodesOf(page, 'note')).toContainText('Budget: 5 €/day')
  await expect(nodesOf(page, 'note').locator('[class*="tint_pink"]')).toHaveCount(1)
  const saved = await savedFlow(page, NAME)
  expect(savedNode(saved, 'note').data).toMatchObject({ text: 'Budget: 5 €/day\nAsk Gerard before raising it.', color: 'pink' })
  expect(saved.edges).toEqual([])
})

test('group: a frame takes a block in, Tidy lays it out inside, deleting the frame keeps the block', async ({ page }) => {
  const NAME = 'E2E nodes · group'
  await newFlow(page, NAME)
  const agent = await addNode(page, 'agent')
  const group = await addNode(page, 'group')
  await expect(edges(page)).toHaveCount(0)
  await expect(group).toContainText('Group')
  await expect(group.locator('.react-flow__handle')).toHaveCount(0)

  const dialog = await openInspector(page, group)
  await field(dialog, 'Label').fill('Backend')
  await select(dialog, 'Colour').selectOption('green')
  await expect(group).toContainText('Backend')
  await expect(group.locator('[class*="tint_green"]')).toHaveCount(1)
  await closeInspector(page)

  // Dropped inside, the block belongs to the frame: `_parent` on the wire, `_pos` still absolute.
  await dropInto(page, agent, group)
  await saveFlow(page)
  let saved = await savedFlow(page, NAME)
  let frame = savedNode(saved, 'group')
  let member = savedNode(saved, 'agent')
  expect(frame.data).toMatchObject({ label: 'Backend', color: 'green', _size: { w: 480, h: 260 } })
  expect(member.data._parent).toBe(frame.id)
  const inside = (pos: { x: number; y: number }, box: { x: number; y: number }, size: { w: number; h: number }) =>
    pos.x >= box.x && pos.y >= box.y && pos.x < box.x + size.w && pos.y < box.y + size.h
  expect(inside(member.data._pos as { x: number; y: number }, frame.data._pos as { x: number; y: number }, { w: 480, h: 260 })).toBe(true)

  // Tidy shrinks the frame around its member and parks the member under the label band.
  await page.getByRole('button', { name: '⌗ Tidy' }).click()
  await page.waitForTimeout(600)
  await saveFlow(page)
  saved = await savedFlow(page, NAME)
  frame = savedNode(saved, 'group')
  member = savedNode(saved, 'agent')
  const size = frame.data._size as { w: number; h: number }
  const at = frame.data._pos as { x: number; y: number }
  const pos = member.data._pos as { x: number; y: number }
  expect(size.w).toBeLessThan(480)
  expect(size.h).toBeLessThan(260)
  expect(member.data._parent).toBe(frame.id)
  expect(pos).toEqual({ x: at.x + 16, y: at.y + 36 })

  // Deleting the frame deletes the frame, not the block it framed; undo brings the frame back.
  await reopenFlow(page, NAME)
  const frameOnCanvas = nodesOf(page, 'group').first()
  await frameOnCanvas.click({ position: { x: 24, y: 12 } })
  await expect(frameOnCanvas).toHaveClass(/selected/)
  await page.keyboard.press('Delete')
  await expect(nodesOf(page, 'group')).toHaveCount(0)
  await expect(nodesOf(page, 'agent')).toHaveCount(1)
  await page.keyboard.press('Control+z')
  await expect(nodesOf(page, 'group')).toHaveCount(1)
  await expect(nodesOf(page, 'agent')).toHaveCount(1)

  // The inspector's own Delete, on the member this time, and the same undo.
  const inspector = await openInspector(page, nodesOf(page, 'agent').first())
  await inspector.getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(nodesOf(page, 'agent')).toHaveCount(0)
  await page.keyboard.press('Control+z')
  await expect(nodesOf(page, 'agent')).toHaveCount(1)
  await expect(part(nodesOf(page, 'agent').first(), 'title')).toHaveText('Agent')
})
