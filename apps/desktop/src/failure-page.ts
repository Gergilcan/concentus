/**
 * The page shown when the backend does not start.
 *
 * The reason this exists at all: without it the failure mode is a blank white window, which tells
 * the user nothing and tells a bug report even less. The log tail is on screen because the answer
 * is almost always in it — a port conflict, a missing jar, a JVM that refused to start.
 */

import { escapeHtml } from './html'
import { updateStrip } from './update-strip'

export function failurePage(message: string, logTail: string, logPath: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>Concentus — startup failed</title>
<style>
  :root { color-scheme: light dark; }
  body {
    margin: 0; padding: 2rem;
    font: 14px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    background: #fbfbfd; color: #1c1c1f;
  }
  @media (prefers-color-scheme: dark) { body { background: #17171a; color: #e8e8ea; } }
  h1 { font-size: 1.25rem; margin: 0 0 .5rem; }
  p { margin: 0 0 1rem; max-width: 70ch; }
  .msg {
    padding: .75rem 1rem; border-radius: 6px; margin-bottom: 1.25rem;
    background: #fdecec; color: #8a1f1f; border: 1px solid #f3c6c6;
  }
  @media (prefers-color-scheme: dark) {
    .msg { background: #331a1a; color: #f3b6b6; border-color: #5a2a2a; }
  }
  pre {
    background: #0f0f12; color: #d6d6da; padding: 1rem; border-radius: 6px;
    overflow: auto; max-height: 22rem; font-size: 12px; white-space: pre-wrap; word-break: break-word;
  }
  .path { font-size: 12px; opacity: .7; margin-top: .5rem; word-break: break-all; }
  .actions { display: flex; gap: .5rem; margin: 1.25rem 0; flex-wrap: wrap; }
  button {
    font: inherit; padding: .5rem 1rem; border-radius: 6px; cursor: pointer;
    border: 1px solid #c9c9d1; background: #fff; color: inherit;
  }
  button.primary { background: #3a3ad6; border-color: #3a3ad6; color: #fff; }
  @media (prefers-color-scheme: dark) {
    button { background: #232328; border-color: #3a3a42; }
    button.primary { background: #5b5bf0; border-color: #5b5bf0; }
  }
</style>
</head>
<body>
  <h1>Concentus could not start</h1>
  <div class="msg">${escapeHtml(message)}</div>
  <p>
    The backend runs as a local process alongside this window. It did not come up, so there is
    nothing to show yet. The log below usually says why.
  </p>
  <div class="actions">
    <button class="primary" onclick="concentus.retry()">Try again</button>
    <button onclick="concentus.openLogs()">Open logs folder</button>
    <button onclick="concentus.quit()">Quit</button>
  </div>
  ${updateStrip()}
  <pre>${escapeHtml(logTail)}</pre>
  <div class="path">${escapeHtml(logPath)}</div>
</body>
</html>`
}
