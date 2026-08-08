/**
 * The first-run page, shown when flows could not actually run yet.
 *
 * Without it the failure is silent and badly timed: the designer opens, everything looks fine, and
 * the problem only surfaces when the user presses Run on a flow they have just spent ten minutes
 * building — as an error about a CLI they were never told they needed. This says so up front, at
 * the moment it can still be fixed in one command.
 *
 * It is a prompt, not a gate. The canvas is fully usable without Claude — you can design flows,
 * wire up MCP and repository nodes and save them — so "Continue without it" is a first-class
 * choice rather than a way to dismiss a warning.
 */

/**
 * Everything the page needs, and deliberately nothing more — in particular not the resolved PATH,
 * which is on the ClaudeCli record but has no business being rendered into a page.
 */
export interface OnboardingState {
  /** Absolute path to the CLI, or null if it could not be found. */
  command: string | null
  /** Whether a Claude Code login exists on this machine. */
  loggedIn: boolean
  /** An API key makes the local login unnecessary, so its absence stops being a problem. */
  cloudConfigured: boolean
}

export function onboardingPage(state: OnboardingState): string {
  // Serialised into the page so the first render needs no round trip, and re-render after a
  // re-check uses the identical code path rather than a second, subtly different one.
  //
  // NOT html-escaped: entities are literal text inside a <script>, so escaping would corrupt the
  // JSON rather than protect it. What actually needs escaping is `<`, which is the only way this
  // string could close the script element early — and the CLI path is the kind of value a user
  // controls, via the "Locate claude…" picker.
  const initial = JSON.stringify(state).replace(/</g, '\\u003c')

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>Welcome to Concentus</title>
<style>
  :root {
    color-scheme: dark;
    --bg: #0b0e14; --panel: #121722; --line: #222a3a; --text: #e6ecf7;
    --muted: #8a97ad; --accent: #6ea8fe; --ok: #4ade80; --warn: #fbbf24;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 2rem 2.25rem 1.5rem;
    font: 14px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    background: var(--bg); color: var(--text);
  }
  .mark { width: 36px; height: 36px; display: block; margin-bottom: 1rem; }
  h1 { font-size: 1.4rem; margin: 0 0 .5rem; font-weight: 800; letter-spacing: -.01em; }
  .lede { color: var(--muted); margin: 0 0 1.25rem; max-width: 62ch; }
  .status { border: 1px solid var(--line); border-radius: 10px; background: var(--panel); padding: .25rem 1rem; margin-bottom: 1.25rem; }
  .row { display: flex; align-items: flex-start; gap: .75rem; padding: .7rem 0; border-bottom: 1px solid var(--line); }
  .row:last-child { border-bottom: 0; }
  .icon { flex: 0 0 auto; width: 18px; height: 18px; margin-top: 1px; }
  .row .label { font-weight: 600; }
  .row .detail { color: var(--muted); font-size: 13px; word-break: break-all; }
  code {
    background: #0f1420; border: 1px solid var(--line); border-radius: 5px;
    padding: .1rem .4rem; font-size: 12.5px; font-family: ui-monospace, Menlo, Consolas, monospace;
  }
  pre {
    background: #0f1420; border: 1px solid var(--line); border-radius: 8px;
    padding: .8rem 1rem; margin: 0 0 1.25rem; overflow-x: auto;
    font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 13px;
  }
  pre .c { color: var(--muted); }
  .actions { display: flex; gap: .6rem; flex-wrap: wrap; align-items: center; }
  button {
    font: inherit; font-weight: 600; padding: .55rem 1.1rem; border-radius: 8px;
    cursor: pointer; border: 1px solid var(--line); background: #1a2130; color: var(--text);
  }
  button:hover { border-color: var(--accent); }
  button.primary { background: var(--accent); border-color: var(--accent); color: #071018; }
  button.primary:hover { background: #8bbaff; }
  .spacer { flex: 1 1 auto; }
  .ask { display: flex; align-items: center; gap: .45rem; color: var(--muted); font-size: 13px; margin-top: 1.1rem; }
  .foot { color: var(--muted); font-size: 12.5px; margin-top: .9rem; margin-bottom: 0; }
  .hidden { display: none; }
</style>
</head>
<body>
  <svg class="mark" viewBox="0 0 32 32" aria-hidden="true">
    <rect width="32" height="32" rx="7" fill="#121722"/>
    <path d="M25 16 L20.5 23.8 L11.5 23.8 L7 16 L11.5 8.2 L20.5 8.2 Z"
          fill="none" stroke="#6ea8fe" stroke-width="2.6" stroke-linejoin="round"/>
  </svg>

  <h1 id="title">One step before you can run flows</h1>
  <p class="lede" id="lede"></p>

  <div class="status" id="status"></div>

  <div id="how">
    <p class="lede">Open a terminal and run this. It opens a browser once, and Concentus picks it up from there — you never paste a key.</p>
    <pre><span class="c"># installs at claude.ai/download if you don't have it yet</span>
claude
<span class="c"># then, inside it:</span>
/login</pre>
  </div>

  <div class="actions">
    <button class="primary" id="recheck">Check again</button>
    <button id="locate">Locate claude…</button>
    <div class="spacer"></div>
    <button id="continue">Continue without it</button>
  </div>

  <label class="ask"><input type="checkbox" id="dontask"> Don't check on future launches</label>

  <p class="foot">
    Prefer Anthropic's cloud API? Set <code>ANTHROPIC_API_KEY</code> in your environment and flows
    run in a hosted sandbox instead — no local sign-in needed.
  </p>

<script>
  var state = ${initial};

  function tick(ok) {
    var colour = ok ? 'var(--ok)' : 'var(--warn)';
    var d = ok ? 'M20 6 9 17l-5-5' : 'M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z';
    return '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="' + colour +
           '" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="' + d + '"/></svg>';
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function row(ok, label, detail) {
    return '<div class="row">' + tick(ok) + '<div><div class="label">' + esc(label) +
           '</div><div class="detail">' + esc(detail) + '</div></div></div>';
  }

  function render() {
    var hasCli = !!state.command;
    document.getElementById('status').innerHTML =
      row(hasCli, 'Claude Code CLI',
          hasCli ? state.command : 'Not found. Install it, or point Concentus at it below.') +
      row(state.loggedIn, 'Signed in to Claude',
          state.loggedIn ? 'A Claude Code login was found on this machine.'
                         : 'No login yet — run the command below.');

    var ready = hasCli && state.loggedIn;
    document.getElementById('title').textContent =
      ready ? "You're ready to go" : 'One step before you can run flows';
    document.getElementById('lede').textContent = ready
      ? 'Concentus found your Claude Code sign-in. Flows will run on your subscription — no API key, no per-token bill.'
      : 'Concentus runs your flows through the Claude Code CLI, on the Claude subscription you already pay for. It needs to be installed and signed in once.';
    document.getElementById('how').className = ready ? 'hidden' : '';
    document.getElementById('continue').textContent = ready ? 'Open Concentus' : 'Continue without it';
    if (ready) document.getElementById('continue').className = 'primary';
  }

  document.getElementById('recheck').addEventListener('click', function () {
    var btn = this;
    btn.disabled = true; btn.textContent = 'Checking…';
    window.concentus.recheckClaude().then(function (next) {
      state = next;
      render();
      btn.disabled = false; btn.textContent = 'Check again';
    });
  });

  document.getElementById('locate').addEventListener('click', function () {
    window.concentus.locateClaude().then(function (next) {
      if (next) { state = next; render(); }
    });
  });

  document.getElementById('continue').addEventListener('click', function () {
    window.concentus.finishOnboarding(document.getElementById('dontask').checked);
  });

  render();
</script>
</body>
</html>`
}
