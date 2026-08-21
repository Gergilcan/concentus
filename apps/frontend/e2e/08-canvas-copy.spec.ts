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

  // Select text the way a user's drag would. The palette's hint, because it is on screen whatever
  // is selected — the inspector this used to borrow from is a dialog now, and opening one would
  // change the very thing under test: which surface owns the keystroke.
  await page.evaluate(() => {
    const hint = [...document.querySelectorAll('p')].find((p) =>
      p.textContent?.includes('Hover any button'),
    )
    if (!hint) throw new Error('hint paragraph not found')
    const range = document.createRange()
    range.selectNodeContents(hint)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  })

  // Read through the canvas rather than the clipboard. What the browser does with a native Ctrl+C
  // is the browser's business and headless Chromium does not always let a test read it back; what
  // this file exists to protect is the arbitration — that the canvas STANDS ASIDE. So: copy with
  // text selected, then paste. A second box appearing would mean the canvas took the keystroke and
  // put node JSON on the clipboard, which is exactly the bug.
  await page.keyboard.press('Control+c')
  await page.keyboard.press('Control+v')
  await expect(nodes).toHaveCount(1)

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
  // Selection is read off the node itself. It used to be inferred from the inspector panel
  // emptying, which is gone — and this is the better assertion anyway: it names the thing the
  // gesture is supposed to change instead of a side effect two components away.

  // Adding through the palette both selects the node and starts a fitView animation; clicks
  // during the animation land on a moving pane and React Flow won't count them. Let it settle,
  // then deselect on clearly-empty canvas so each gesture starts from nothing selected.
  await page.waitForTimeout(700)
  const deselect = async () => {
    // Absolute coordinates on an empty stretch of canvas, sidestepping pane-relative math
    // (the pane is ~720x330 at ~(260,100); the node sits mid-canvas, the minimap bottom-right).
    await page.mouse.click(560, 400)
    await expect(node).not.toHaveClass(/selected/)
  }
  await deselect()

  // A click that wanders 3px — under the threshold — must still be a click.
  const box = (await node.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 3, box.y + box.height / 2 + 2)
  await page.mouse.up()
  await expect(node).toHaveClass(/selected/)

  // Deselect, then drag well past the threshold: the node moves AND becomes the active one.
  await deselect()
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 80, box.y + box.height / 2 + 40, { steps: 8 })
  await page.mouse.up()
  await expect(node).toHaveClass(/selected/)
  const moved = (await node.boundingBox())!
  expect(Math.abs(moved.x - box.x)).toBeGreaterThan(40)
})
