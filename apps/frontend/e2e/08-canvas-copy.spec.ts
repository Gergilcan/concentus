import { expect, openApp, test } from './fixtures'

test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

/**
 * Ctrl+C arbitration between the canvas and the rest of the page. The canvas keeps copy/paste
 * shortcuts for nodes, but a user with TEXT selected — console output, a hint, anything — is
 * copying text, and the node shortcut must stand aside. It didn't: isTextEntry() only exempts
 * focus in a field, so any selected node overwrote the clipboard with node JSON.
 */
test('selected text wins Ctrl+C; nodes still copy when nothing is selected', async ({ page }) => {
  await openApp(page)
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByRole('button', { name: '▶ Input / trigger' }).click()
  const nodes = page.locator('.react-flow__node')
  await nodes.first().click() // the node is now selected — the old failure precondition

  // Select the inspector's hint text the way a user's drag would.
  await page.evaluate(() => {
    const hint = [...document.querySelectorAll('p')].find((p) =>
      p.textContent?.includes('Connect this node'),
    )
    if (!hint) throw new Error('hint paragraph not found')
    const range = document.createRange()
    range.selectNodeContents(hint)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  })
  await page.keyboard.press('Control+c')
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain('Connect this node')

  // With the selection gone, the same shortcut is the node's again: copy, paste, two nodes.
  await page.evaluate(() => window.getSelection()?.removeAllRanges())
  await nodes.first().click()
  await page.keyboard.press('Control+c')
  await page.keyboard.press('Control+v')
  await expect(nodes).toHaveCount(2)
})

/**
 * Clicking is a human gesture, not a pixel-perfect one. React Flow's default drag threshold is
 * 1px, so a click with the slightest jitter became a micro-drag and never selected the node —
 * Gerard's "a veces no se selecciona al hacer click". Within the raised threshold a press is a
 * click; past it, it is a drag — and grabbing a node now selects it too.
 */
test('a jittery click still selects; a real drag selects as it moves', async ({ page }) => {
  await openApp(page)
  await page.getByRole('button', { name: '+ New flow' }).first().click()
  await page.getByRole('button', { name: '▶ Input / trigger' }).click()
  const node = page.locator('.react-flow__node').first()
  const inspectorEmpty = page.getByText('Select a node to edit its settings.')

  // Adding through the palette both selects the node and starts a fitView animation; clicks
  // during the animation land on a moving pane and React Flow won't count them. Let it settle,
  // then deselect on clearly-empty canvas so each gesture starts from nothing selected.
  await page.waitForTimeout(700)
  const deselect = async () => {
    // Absolute coordinates on an empty stretch of canvas, sidestepping pane-relative math
    // (the pane is ~720x330 at ~(260,100); the node sits mid-canvas, the minimap bottom-right).
    await page.mouse.click(560, 400)
    await expect(inspectorEmpty).toBeVisible()
  }
  await deselect()

  // A click that wanders 3px — under the threshold — must still be a click.
  const box = (await node.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 3, box.y + box.height / 2 + 2)
  await page.mouse.up()
  await expect(inspectorEmpty).toHaveCount(0)

  // Deselect, then drag well past the threshold: the node moves AND becomes the active one.
  await deselect()
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 80, box.y + box.height / 2 + 40, { steps: 8 })
  await page.mouse.up()
  await expect(inspectorEmpty).toHaveCount(0)
  const moved = (await node.boundingBox())!
  expect(Math.abs(moved.x - box.x)).toBeGreaterThan(40)
})
