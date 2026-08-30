import { expect, flowCard, openApp, test } from './fixtures'
import { backToFlows, closeInspector, flowErrors, nodes, openInspector, runDoctor } from './flows-helpers'

/**
 * The eight bundled flows, one by one: each is seeded into "Samples" on a fresh install, opens
 * from its card onto a canvas with the blocks its JSON declares, passes the doctor with no error
 * beyond the placeholders it ships with, opens a block's properties, and is left untouched.
 *
 * A sample is a template: three of them deliberately point at nothing yet (an MCP server without
 * a URL, a Run-another-flow block with no flow chosen) because that is the thing the person is
 * meant to fill in. The doctor is right to call those errors, and this spec pins EXACTLY which
 * ones — a new error on a sample, or a placeholder quietly gone, both fail here.
 *
 * The `cli` finding is about the machine, not the flow (an error on a runner with no sign-in and
 * no API key, silence on a laptop with the CLI), so it is recorded but never asserted on.
 */

interface Sample {
  name: string
  /** The trigger badge on the card, as flowFormat's triggerOf prints it. */
  badge: string
  paused: boolean
  blocks: number
  /** Texts the canvas must show: the trigger's mode badge, then the blocks' own names. */
  titles: string[]
  /** The block whose properties are opened. */
  open: string
  /** Error areas the doctor is expected to report — the placeholders this sample ships with. */
  errors: string[]
}

const SAMPLES: Sample[] = [
  { name: 'Daily briefing (cron)', badge: '⏱ 0 7 * * 1-5', paused: true, blocks: 2, titles: ['Automatic (cron)', 'Briefing Writer'], open: 'Briefing Writer', errors: [] },
  { name: 'Docs from code', badge: '✋ Manual', paused: false, blocks: 2, titles: ['Manual', 'Docs Writer'], open: 'Docs Writer', errors: [] },
  { name: 'Mailbox assistant (IMAP)', badge: '✉ INBOX', paused: true, blocks: 2, titles: ['Mail (IMAP)', 'Mail Triage'], open: 'Mail Triage', errors: [] },
  // The Holded MCP block has no URL yet: that is the one thing this sample asks the person for.
  { name: 'Presupuestos por correo → Holded', badge: '✉ Presupuestos', paused: false, blocks: 3, titles: ['Mail (IMAP)', 'Presupuestos', 'holded'], open: 'Presupuestos', errors: ['graph', 'mcp'] },
  { name: 'PR review crew', badge: '✋ Manual', paused: false, blocks: 4, titles: ['Manual', 'Review Lead', 'Backend Reviewer', 'Frontend Reviewer'], open: 'Review Lead', errors: [] },
  // Both hand-off blocks wait for a flow to be chosen.
  { name: 'Report with a safety net (on error)', badge: '⏱ 0 8 * * 1', paused: true, blocks: 4, titles: ['Automatic (cron)', 'Weekly report', 'deliver the report', 'tell someone it broke'], open: 'Weekly report', errors: ['graph'] },
  { name: 'Support triage (if/else)', badge: '✋ Manual', paused: true, blocks: 5, titles: ['Manual', 'Triage', 'urgent?', 'escalate', 'queue for tomorrow'], open: 'Triage', errors: ['graph'] },
  { name: 'Issue triage (webhook)', badge: '⚡ Webhook', paused: true, blocks: 3, titles: ['Webhook', 'Issue Triage', 'linear'], open: 'Issue Triage', errors: [] },
]

/**
 * Inside the Samples folder. A fresh install opens there by itself; an install where another
 * spec left a flow at the root opens at the root, with Samples as a tile to enter.
 */
async function enterSamples(page: import('@playwright/test').Page): Promise<void> {
  const first = flowCard(page, SAMPLES[0].name)
  const tile = page.getByRole('button', { name: 'Folder Samples' })
  // A loop, not a decision: the dashboard mounts at the root, and on a fresh install walks into
  // Samples by itself a render later — so a tile seen a moment ago may be gone by the time it is
  // clicked, and a count taken while the flow list is in flight is zero either way.
  await expect(async () => {
    if (await first.count()) return
    if (await tile.isVisible()) await tile.click({ timeout: 2000 }).catch(() => {})
    expect(await first.count()).toBeGreaterThan(0)
  }).toPass({ timeout: 15_000 })
}

test('all eight bundled flows are seeded into Samples', async ({ page }) => {
  await openApp(page)
  await enterSamples(page)
  for (const sample of SAMPLES) await expect(flowCard(page, sample.name)).toHaveCount(1)
})

for (const sample of SAMPLES) {
  test(`${sample.name}: opens as declared, passes the doctor, and is left as found`, async ({ page }) => {
    await openApp(page)
    await enterSamples(page)

    // The card says how it starts — and that a scheduled sample ships paused, so installing the
    // app never quietly starts polling a mailbox or firing at 07:00.
    const card = flowCard(page, sample.name)
    await expect(card.getByText(sample.badge, { exact: true })).toBeVisible()
    await expect(card.getByText('paused', { exact: true })).toHaveCount(sample.paused ? 1 : 0)

    // Looking must not write. Every POST to the flow API is captured from here on, and the
    // test ends by asserting there was none.
    const writes: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'POST' && /\/api\/flows/.test(r.url())) writes.push(r.url())
    })

    await card.getByRole('button', { name: 'Open' }).click()
    await expect(page.getByLabel('Flow name')).toHaveValue(sample.name)
    await expect(nodes(page)).toHaveCount(sample.blocks)
    for (const title of sample.titles) {
      await expect(nodes(page).filter({ hasText: title }).first(), `a block showing "${title}"`).toBeVisible()
    }

    const doctor = await runDoctor(page)
    expect(flowErrors(doctor).map((e) => e.split(':')[0]).sort(), 'error areas beyond the sample\'s placeholders')
      .toEqual([...sample.errors].sort())
    // Recorded, not asserted: which warnings a sample carries is worth knowing and free to change.
    test.info().annotations.push({
      type: 'doctor',
      description: `errors=${JSON.stringify(doctor.errors)} warnings=${JSON.stringify(doctor.warnings)}`,
    })
    console.log(`DOCTOR ${sample.name} | errors: ${doctor.errors.join(' || ') || '-'} | warnings: ${doctor.warnings.join(' || ') || '-'}`)

    await openInspector(page, sample.open)
    await closeInspector(page)

    await backToFlows(page)
    await enterSamples(page)
    await expect(card).toHaveCount(1)
    expect(writes, 'opening, checking and closing a sample saved nothing').toEqual([])
  })
}
