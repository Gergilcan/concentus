import { expect, openApp, test } from './fixtures'

/** Screenshot + check for the card overflow menu. Deleted after use. */
const SHOTS =
  'C:/Users/GILA7/AppData/Local/Temp/claude/c--Users-GILA7-concentus/57946cd8-6dc9-45b6-936f-9a6679fbb464/scratchpad/shots'

test('the card footer is four controls, and the rest have names', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1000 })
  await openApp(page)
  await page.waitForTimeout(500)
  await page.screenshot({ path: `${SHOTS}/menu-closed.png` })

  const card = page.getByRole('article').first()
  const footer = card.locator('[class*="cardActions"]')
  const buttons = await footer.locator('button').count()
  // eslint-disable-next-line no-console
  console.log('FOOTER BUTTONS:', buttons)
  // Open, Run, ⋯ — plus a schedule toggle on a scheduled flow. Never the old nine.
  expect(buttons).toBeLessThanOrEqual(4)

  // Nothing sticks out of the card any more.
  const cardBox = (await card.boundingBox())!
  const footerBox = (await footer.boundingBox())!
  // eslint-disable-next-line no-console
  console.log('CARD RIGHT:', Math.round(cardBox.x + cardBox.width), 'FOOTER RIGHT:', Math.round(footerBox.x + footerBox.width))
  expect(footerBox.x + footerBox.width).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)

  await footer.getByRole('button', { name: /More actions/ }).click()
  const menu = page.getByRole('menu')
  await expect(menu).toBeVisible()
  const labels = await menu.getByRole('menuitem').allInnerTexts()
  // eslint-disable-next-line no-console
  console.log('MENU:', labels.map((l) => l.replace(/\s+/g, ' ').trim()).join(' | '))
  await page.screenshot({ path: `${SHOTS}/menu-open.png` })

  // The point of the menu: words, not glyphs.
  expect(labels.join(' ')).toContain('Version history')
  expect(labels.join(' ')).toContain('Delete')

  await page.keyboard.press('Escape')
  await expect(page.getByRole('menu')).toHaveCount(0)
})
