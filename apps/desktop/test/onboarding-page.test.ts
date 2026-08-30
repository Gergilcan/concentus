import { describe, expect, it } from 'vitest'
import { DataKeyNote, OnboardingState, StorageState, onboardingPage } from '../src/onboarding-page'
import type { RunnerState } from '../src/runner'

/**
 * onboarding-page.ts: the wizard is one HTML string, rendered from the state it is handed. These
 * assert the third step is on the page and that the state reaches its script intact — including
 * the one character that could break out of the script element.
 */

const claude: OnboardingState = { command: '/usr/local/bin/claude', loggedIn: true, cloudConfigured: false }
const storage: StorageState = { mode: 'embedded', url: '', username: '', hasPassword: false, activeMode: 'embedded' }
const dataKey: DataKeyNote = { source: 'keyring', file: '', detail: '' }

function render(runner: RunnerState): string {
  return onboardingPage(claude, storage, false, dataKey, runner)
}

describe('onboardingPage — the server step', () => {
  it('has a third step with the question, the two fields and both ways out', () => {
    const html = render({ url: null, name: null, hasToken: false })

    expect(html).toContain('<span id="lbl3">3. Server</span>')
    expect(html).toContain('<section id="step3"')
    expect(html).toContain('Connect this machine to a Concentus server?')
    expect(html).toContain('The server never receives the login')
    expect(html).toContain('placeholder="https://concentus.example.com"')
    expect(html).toContain('id="runnerToken" type="password" placeholder="crn_…"')
    expect(html).toContain('id="runnerName"')
    expect(html).toContain('<button class="primary" id="runnerConnect">Connect</button>')
    expect(html).toContain('<button id="runnerDisconnect" class="hidden">Disconnect</button>')
    expect(html).toContain('<button id="finish">Skip</button>')
    // The page drives it through the three bridge calls the preload exposes.
    expect(html).toContain('window.concentus.saveRunner(')
    expect(html).toContain('window.concentus.clearRunner()')
    expect(html).toContain('window.concentus.runnerStatus()')
  })

  it('hands the script the runner state it was rendered with — URL, name and whether a token exists', () => {
    const html = render({ url: 'https://hub.example.com', name: 'office-pc', hasToken: true })

    expect(html).toContain('"runner":{"url":"https://hub.example.com","name":"office-pc","hasToken":true}')
    // Never the token itself: the page only learns that one is stored.
    expect(html).not.toContain('crn_a')
  })

  it('escapes the one character that could end the script early, in the runner URL as everywhere', () => {
    const html = render({ url: 'https://hub.example.com/</script><script>alert(1)', name: null, hasToken: false })

    expect(html).not.toContain('</script><script>alert(1)')
    expect(html).toContain('\\u003c/script>\\u003cscript>alert(1)')
  })

  it('the Claude step now continues to the server step instead of finishing', () => {
    const html = render({ url: null, name: null, hasToken: false })

    expect(html).toContain('<button id="toServer">')
    expect(html).toContain("$('toServer').addEventListener('click', function () { showStep(3); });")
    expect(html).toContain("window.concentus.finishOnboarding($('dontask').checked);")
  })
})
