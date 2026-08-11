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
