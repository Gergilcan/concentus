/**
 * The license wall, JetBrains-style: shown instead of the failure page when the backend refused
 * to start because this install is configured for the shared database and no enterprise license
 * is in place.
 *
 * Deliberately a dead end with exactly three exits — paste a license, go request one, or close
 * the app. No retry button: retrying without a license reproduces the same refusal, and offering
 * it would teach people that the wall sometimes blinks. It does not.
 */

import { escapeHtml } from './html'
import { updateStrip } from './update-strip'

export function licensePage(message: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>Concentus — license required</title>
<style>
  :root { color-scheme: light dark; }
  body {
    margin: 0; padding: 2.25rem 2.5rem;
    font: 14px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    background: #fbfbfd; color: #1c1c1f;
  }
  @media (prefers-color-scheme: dark) { body { background: #17171a; color: #e8e8ea; } }
  h1 { font-size: 1.25rem; margin: 0 0 .5rem; }
  p { margin: 0 0 1rem; max-width: 64ch; }
  .msg {
    padding: .75rem 1rem; border-radius: 6px; margin-bottom: 1.25rem;
    background: #fff4e0; color: #7a4b00; border: 1px solid #f0d9a8;
  }
  @media (prefers-color-scheme: dark) {
    .msg { background: #33270f; color: #f0cf8e; border-color: #5a4a22; }
  }
  textarea {
    width: 100%; min-height: 7.5rem; resize: vertical; box-sizing: border-box;
    font: 12px/1.5 ui-monospace, Consolas, monospace;
    padding: .65rem .75rem; border-radius: 6px;
    border: 1px solid #c9c9d1; background: #fff; color: inherit;
  }
  @media (prefers-color-scheme: dark) { textarea { background: #1f1f24; border-color: #3a3a42; } }
  .error { color: #b32424; margin: .5rem 0 0; min-height: 1.2em; font-size: 13px; }
  @media (prefers-color-scheme: dark) { .error { color: #ff8a8a; } }
  .actions { display: flex; gap: .5rem; margin-top: 1.25rem; flex-wrap: wrap; }
  .actions .spacer { flex: 1; }
  button {
    font: inherit; padding: .5rem 1rem; border-radius: 6px; cursor: pointer;
    border: 1px solid #c9c9d1; background: #fff; color: inherit;
  }
  button.primary { background: #3a3ad6; border-color: #3a3ad6; color: #fff; }
  button:disabled { opacity: .55; cursor: default; }
  @media (prefers-color-scheme: dark) {
    button { background: #232328; border-color: #3a3a42; }
    button.primary { background: #5b5bf0; border-color: #5b5bf0; }
  }
  .note { font-size: 12.5px; opacity: .75; margin-top: 1rem; max-width: 64ch; }
</style>
</head>
<body>
  <h1>An enterprise license is required</h1>
  <div class="msg">${escapeHtml(message)}</div>
  <p>
    This install is configured for the shared database — the team deployment. That part of
    Concentus runs under an enterprise license; everything for individual use stays free and
    needs no license at all.
  </p>
  <textarea id="token" spellcheck="false" placeholder="Paste your license here — it is one line starting with CONCENTUS."></textarea>
  <div class="error" id="error"></div>
  <div class="actions">
    <button class="primary" id="apply" onclick="apply()">Apply license</button>
    <button onclick="concentus.requestLicense()">Request a license…</button>
    <span class="spacer"></span>
    <button onclick="concentus.closeLicense()">Close</button>
  </div>
  <p class="note">
    The license is checked when the app starts. If the one you paste is not accepted, this window
    returns with the reason.
  </p>
  ${updateStrip()}
  <script>
    async function apply() {
      const button = document.getElementById('apply')
      const error = document.getElementById('error')
      error.textContent = ''
      button.disabled = true
      button.textContent = 'Applying…'
      try {
        const result = await concentus.applyLicense(document.getElementById('token').value)
        if (!result.ok) {
          error.textContent = result.error
          button.disabled = false
          button.textContent = 'Apply license'
        }
        // On ok the shell closes this window and relaunches; nothing left for this page to do.
      } catch (e) {
        error.textContent = String(e)
        button.disabled = false
        button.textContent = 'Apply license'
      }
    }
  </script>
</body>
</html>`
}
