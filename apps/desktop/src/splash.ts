import { BrowserWindow } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { escapeHtml } from './html'

/**
 * The splash screen: what the user looks at while the backend comes up.
 *
 * The launch takes seconds — a JVM, a database, a schema check — and used to show nothing at all
 * until the window appeared, which reads as "did my double-click take?". This puts the answer on
 * screen immediately: the app's icon and name, the installed version, and a progress bar that
 * moves when the startup actually moves. The percentages come from real milestones observed in
 * the backend's output (see STARTUP_MILESTONES in backend.ts), not from a timer pretending to
 * know — the bar easing between two real values is the only animation in it.
 */

export interface Splash {
  /** Move the bar forward. Values below the current one are ignored — progress never rewinds. */
  advance(percent: number, label: string): void
  close(): void
}

/** A splash that does nothing, for launches that must not show one (--hidden). */
export const noSplash: Splash = { advance: () => {}, close: () => {} }

export function showSplash(version: string): Splash {
  const window = new BrowserWindow({
    width: 460,
    height: 320,
    frame: false,
    resizable: false,
    maximizable: false,
    fullscreenable: false,
    // Deliberately in the taskbar: for the seconds when this is the only window the app has,
    // hiding it there would make the app look like it is not running at all.
    title: 'Concentus',
    icon: iconFile(),
    backgroundColor: '#0b0e14',
    webPreferences: {
      // Static HTML with no reach into anything: no preload, no node, nothing to compromise.
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  window.removeMenu()
  void window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(page(version))}`)

  let reported = 0
  return {
    advance(percent: number, label: string): void {
      if (window.isDestroyed() || percent <= reported) return
      reported = percent
      // executeJavaScript rather than IPC: the page needs no capabilities in return, so it gets no
      // preload — this is a one-way push into an inert page.
      void window.webContents
        .executeJavaScript(`window.__progress(${Math.round(percent)}, ${JSON.stringify(label)})`)
        .catch(() => { /* racing the page's first paint or its close; either is fine */ })
    },
    close(): void {
      if (!window.isDestroyed()) window.close()
    },
  }
}

/** Same lookup as main.ts's appIcon(); the PNG is what a data URI can carry. */
function iconFile(): string {
  return path.join(__dirname, '..', 'build', 'icon.png')
}

function iconDataUri(): string {
  try {
    return `data:image/png;base64,${fs.readFileSync(iconFile()).toString('base64')}`
  } catch {
    return '' // an <img> with an empty src collapses; the name still identifies the app
  }
}

function page(version: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>Concentus</title>
<style>
  :root { color-scheme: dark; }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    background: radial-gradient(120% 90% at 50% 0%, #131a2a 0%, #0b0e14 60%);
    color: #e6ecf7;
    font: 14px/1.4 system-ui, 'Segoe UI', sans-serif;
    display: flex; flex-direction: column; align-items: center;
    padding: 44px 36px 28px;
    border: 1px solid #222a3a;
    user-select: none; cursor: default; overflow: hidden;
  }
  img { width: 96px; height: 96px; }
  h1 { font-size: 26px; font-weight: 600; letter-spacing: 0.04em; margin-top: 14px; }
  .version { color: #8a97ad; font-size: 12px; margin-top: 4px; }
  .spacer { flex: 1; }
  .row { width: 100%; display: flex; justify-content: space-between; color: #8a97ad; font-size: 12px; margin-bottom: 7px; }
  .bar { width: 100%; height: 5px; background: #1a2233; border-radius: 3px; overflow: hidden; }
  .fill {
    height: 100%; width: 0%;
    background: linear-gradient(90deg, #4c86e8, #6ea8fe);
    border-radius: 3px;
    transition: width 350ms ease-out;
  }
</style>
</head>
<body>
  <img src="${iconDataUri()}" alt="">
  <h1>Concentus</h1>
  <div class="version">${escapeHtml(version)}</div>
  <div class="spacer"></div>
  <div class="row"><span id="label">Starting…</span><span id="pct"></span></div>
  <div class="bar"><div class="fill" id="fill"></div></div>
  <script>
    window.__progress = (percent, label) => {
      document.getElementById('fill').style.width = percent + '%';
      document.getElementById('label').textContent = label;
      document.getElementById('pct').textContent = percent + '%';
    };
  </script>
</body>
</html>`
}
