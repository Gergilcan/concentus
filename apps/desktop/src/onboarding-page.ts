/**
 * The first-run wizard: where data is kept, then signing in to Claude.
 *
 * <p>Both steps exist for the same reason — they are the two things that must be settled before
 * the app can do anything, and both used to fail late and confusingly. A missing Claude sign-in
 * surfaced as an error on a flow the user had just spent ten minutes building. A database that
 * could not be reached surfaced as an app that opened onto nothing at the *next* launch, by which
 * point the connection details were somewhere else entirely.
 *
 * <p>The database step therefore refuses to advance on an untested external connection. That is
 * the whole point of doing it here: the check happens while the person who typed the host name is
 * still looking at it.
 *
 * <p>The Claude step remains a prompt rather than a gate — the canvas is fully usable without it.
 */

/** State of the Claude half. */
export interface OnboardingState {
  command: string | null
  loggedIn: boolean
  cloudConfigured: boolean
}

/** State of the database half, as the backend reports it. */
export interface StorageState {
  mode: 'embedded' | 'external'
  url: string
  username: string
  hasPassword: boolean
  activeMode: 'embedded' | 'external'
}

export function onboardingPage(claude: OnboardingState, storage: StorageState): string {
  // Entities are literal text inside <script>, so HTML-escaping would corrupt this rather than
  // protect it. `<` is what could close the element early, and these values include a JDBC URL and
  // a filesystem path the user controls.
  const initial = JSON.stringify({ claude, storage }).replace(/</g, '\\u003c')

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
    --muted: #8a97ad; --accent: #6ea8fe; --ok: #4ade80; --warn: #fbbf24; --bad: #f87171;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 2rem 2.25rem 1.5rem;
    font: 14px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    background: var(--bg); color: var(--text);
  }
  .mark { width: 36px; height: 36px; display: block; margin-bottom: .75rem; }
  .steps { display: flex; gap: .5rem; align-items: center; color: var(--muted); font-size: 12.5px; margin-bottom: 1rem; }
  .steps b { color: var(--text); }
  .steps .sep { opacity: .5; }
  h1 { font-size: 1.4rem; margin: 0 0 .5rem; font-weight: 800; letter-spacing: -.01em; }
  .lede { color: var(--muted); margin: 0 0 1.25rem; max-width: 62ch; }
  .status { border: 1px solid var(--line); border-radius: 10px; background: var(--panel); padding: .25rem 1rem; margin-bottom: 1.25rem; }
  .row { display: flex; align-items: flex-start; gap: .75rem; padding: .7rem 0; border-bottom: 1px solid var(--line); }
  .row:last-child { border-bottom: 0; }
  .icon { flex: 0 0 auto; width: 18px; height: 18px; margin-top: 1px; }
  .row .label { font-weight: 600; }
  .row .detail { color: var(--muted); font-size: 13px; word-break: break-all; }
  label.opt { display: flex; gap: .6rem; align-items: flex-start; padding: .7rem 1rem; border: 1px solid var(--line);
              border-radius: 10px; background: var(--panel); margin-bottom: .6rem; cursor: pointer; }
  label.opt.sel { border-color: var(--accent); }
  label.opt .t { font-weight: 600; }
  label.opt .d { color: var(--muted); font-size: 13px; }
  .fields { margin: .25rem 0 1rem; }
  .fields label { display: block; margin-bottom: .6rem; }
  .fields span { display: block; color: var(--muted); font-size: 12.5px; margin-bottom: .25rem; }
  .fields input {
    width: 100%; font: inherit; padding: .5rem .65rem; border-radius: 7px;
    border: 1px solid var(--line); background: #0f1420; color: var(--text);
  }
  code { background: #0f1420; border: 1px solid var(--line); border-radius: 5px; padding: .1rem .4rem;
         font-size: 12.5px; font-family: ui-monospace, Menlo, Consolas, monospace; }
  pre { background: #0f1420; border: 1px solid var(--line); border-radius: 8px; padding: .8rem 1rem;
        margin: 0 0 1.25rem; overflow-x: auto; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 13px; }
  pre .c { color: var(--muted); }
  /* The installer log: bounded so a long install cannot push the buttons off screen. */
  #installLog { max-height: 9rem; overflow-y: auto; white-space: pre-wrap; font-size: 12px; }
  .actions { display: flex; gap: .6rem; flex-wrap: wrap; align-items: center; }
  button { font: inherit; font-weight: 600; padding: .55rem 1.1rem; border-radius: 8px;
           cursor: pointer; border: 1px solid var(--line); background: #1a2130; color: var(--text); }
  button:hover:not(:disabled) { border-color: var(--accent); }
  button.primary { background: var(--accent); border-color: var(--accent); color: #071018; }
  button.primary:hover:not(:disabled) { background: #8bbaff; }
  button:disabled { opacity: .45; cursor: not-allowed; }
  .spacer { flex: 1 1 auto; }
  .ask { display: flex; align-items: center; gap: .45rem; color: var(--muted); font-size: 13px; margin-top: 1.1rem; }
  .foot { color: var(--muted); font-size: 12.5px; margin-top: .9rem; margin-bottom: 0; }
  .msg { font-size: 13px; margin: .5rem 0 0; }
  .msg.ok { color: var(--ok); }
  .msg.bad { color: var(--bad); }
  .hidden { display: none; }
</style>
</head>
<body>
  <svg class="mark" viewBox="0 0 32 32" aria-hidden="true">
    <rect width="32" height="32" rx="7" fill="#121722"/>
    <path d="M25 16 L20.5 23.8 L11.5 23.8 L7 16 L11.5 8.2 L20.5 8.2 Z"
          fill="none" stroke="#6ea8fe" stroke-width="2.6" stroke-linejoin="round"/>
  </svg>

  <div class="steps">
    <span id="lbl1">1. Database</span><span class="sep">›</span><span id="lbl2">2. Claude</span>
  </div>

  <!-- ---------------------------------------------------------------- step 1 -->
  <section id="step1">
    <h1>Where should Concentus keep its data?</h1>
    <p class="lede">Runs, credentials and flow history. Your flows, agents and MCP definitions live
      here too, so this is the thing worth backing up.</p>

    <label class="opt" id="optEmbedded">
      <input type="radio" name="mode" value="embedded">
      <span>
        <span class="t">Use the built-in database</span>
        <span class="d">Ships with the app and starts with it. Nothing to install, nothing to
          administer. Right for one person on one machine.</span>
      </span>
    </label>

    <label class="opt" id="optExternal">
      <input type="radio" name="mode" value="external">
      <span>
        <span class="t">Connect to my own PostgreSQL</span>
        <span class="d">For a team: shared between installs, backed up and audited like any other
          database. Concentus creates its own tables, so an empty database is all it needs.</span>
      </span>
    </label>

    <div class="fields hidden" id="extFields">
      <label><span>JDBC URL</span>
        <input id="url" placeholder="jdbc:postgresql://db.internal:5432/concentus"></label>
      <label><span>Username</span><input id="username"></label>
      <label><span>Password</span><input id="password" type="password"></label>
      <div class="actions">
        <button id="test">Test connection</button>
      </div>
      <p class="msg" id="testMsg"></p>
    </div>

    <div class="actions" style="margin-top:1rem">
      <button class="primary" id="next" disabled>Continue</button>
      <span class="spacer"></span>
    </div>
    <p class="foot" id="step1Foot"></p>
  </section>

  <!-- ---------------------------------------------------------------- step 2 -->
  <section id="step2" class="hidden">
    <h1 id="title">One step before you can run flows</h1>
    <p class="lede" id="lede"></p>

    <div class="status" id="status"></div>

    <!-- Shown when the CLI is missing: installing it is the step people get stuck on, and it is
         one command they should not have to find. The command itself is on screen because this
         downloads and runs a script — approving that without seeing it would not be a real
         choice. -->
    <div id="install" class="hidden">
      <p class="lede">Concentus can install it for you. This runs Anthropic's official installer as
        you, with no administrator rights:</p>
      <pre id="installCmd"></pre>
      <div class="actions">
        <button class="primary" id="doInstall">Install Claude Code</button>
      </div>
      <pre id="installLog" class="hidden"></pre>
      <p class="msg" id="installMsg"></p>
    </div>

    <div id="how">
      <p class="lede" id="howLede">Or do it yourself in a terminal. Once installed, run
        <code>claude</code> and sign in — it opens a browser once, and Concentus picks it up from
        there. You never paste a key.</p>
      <pre><span class="c"># then, inside it:</span>
claude
/login</pre>
    </div>

    <div class="actions">
      <button class="primary" id="recheck">Check again</button>
      <button id="locate">Locate claude…</button>
      <div class="spacer"></div>
      <button id="back">Back</button>
      <button id="finish">Continue without it</button>
    </div>

    <label class="ask"><input type="checkbox" id="dontask"> Don't check on future launches</label>

    <p class="foot">
      Prefer Anthropic's cloud API? Set <code>ANTHROPIC_API_KEY</code> in your environment and flows
      run in a hosted sandbox instead — no local sign-in needed.
    </p>
  </section>

<script>
  var s = ${initial};
  var claude = s.claude;
  var storage = s.storage;
  // An external connection must prove itself before Continue unlocks. Reset by any edit, so
  // changing the host after a successful test does not carry the old verdict forward.
  var tested = false;
  var busy = false;

  var $ = function (id) { return document.getElementById(id); };

  function esc(t) {
    return String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function tick(ok) {
    var colour = ok ? 'var(--ok)' : 'var(--warn)';
    var d = ok ? 'M20 6 9 17l-5-5'
               : 'M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z';
    return '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="' + colour +
           '" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="' + d + '"/></svg>';
  }

  function row(ok, label, detail) {
    return '<div class="row">' + tick(ok) + '<div><div class="label">' + esc(label) +
           '</div><div class="detail">' + esc(detail) + '</div></div></div>';
  }

  // ---------------------------------------------------------------- step 1
  function mode() {
    var checked = document.querySelector('input[name=mode]:checked');
    return checked ? checked.value : 'embedded';
  }

  function draft() {
    return {
      mode: mode(),
      url: $('url').value.trim(),
      username: $('username').value.trim(),
      // Null means "keep what is stored", which matters when returning to this step on a machine
      // that already has a password saved.
      password: $('password').value === '' && storage.hasPassword ? null : $('password').value,
    };
  }

  function renderStep1() {
    var external = mode() === 'external';
    $('extFields').className = external ? 'fields' : 'fields hidden';
    $('optEmbedded').className = external ? 'opt' : 'opt sel';
    $('optExternal').className = external ? 'opt sel' : 'opt';
    // Embedded needs no proof; external does.
    $('next').disabled = busy || (external && !tested);
    $('step1Foot').textContent = external && !tested
      ? 'Test the connection before continuing — an unreachable database would leave the app with nowhere to start next time.'
      : '';
  }

  function invalidate() {
    tested = false;
    $('testMsg').textContent = '';
    $('testMsg').className = 'msg';
    renderStep1();
  }

  Array.prototype.forEach.call(document.querySelectorAll('input[name=mode]'), function (r) {
    r.addEventListener('change', invalidate);
  });
  ['url', 'username', 'password'].forEach(function (id) {
    $(id).addEventListener('input', invalidate);
  });

  $('test').addEventListener('click', function () {
    busy = true; this.disabled = true; this.textContent = 'Testing…';
    var btn = this;
    $('testMsg').textContent = ''; $('testMsg').className = 'msg';
    window.concentus.testStorage(draft()).then(function (r) {
      tested = !!r.ok;
      $('testMsg').textContent = (r.ok ? '✓ ' : '✗ ') + r.detail;
      $('testMsg').className = 'msg ' + (r.ok ? 'ok' : 'bad');
    }).catch(function (e) {
      tested = false;
      $('testMsg').textContent = '✗ ' + (e && e.message ? e.message : String(e));
      $('testMsg').className = 'msg bad';
    }).then(function () {
      busy = false; btn.disabled = false; btn.textContent = 'Test connection';
      renderStep1();
    });
  });

  $('next').addEventListener('click', function () {
    busy = true; renderStep1();
    var btn = this; btn.textContent = 'Saving…';
    window.concentus.saveStorage(draft()).then(function () {
      showStep(2);
    }).catch(function (e) {
      $('testMsg').textContent = '✗ ' + (e && e.message ? e.message : String(e));
      $('testMsg').className = 'msg bad';
    }).then(function () {
      busy = false; btn.textContent = 'Continue'; renderStep1();
    });
  });

  // ---------------------------------------------------------------- step 2
  function renderStep2() {
    var hasCli = !!claude.command;
    $('status').innerHTML =
      row(hasCli, 'Claude Code CLI',
          hasCli ? claude.command : 'Not found. Install it, or point Concentus at it below.') +
      row(claude.loggedIn, 'Signed in to Claude',
          claude.loggedIn ? 'A Claude Code login was found on this machine.'
                          : 'No login yet — run the command below.');

    var ready = hasCli && claude.loggedIn;
    $('title').textContent = ready ? "You're ready to go" : 'One step before you can run flows';
    $('lede').textContent = ready
      ? 'Concentus found your Claude Code sign-in. Flows will run on your subscription — no API key, no per-token bill.'
      : 'Concentus runs your flows through the Claude Code CLI, on the Claude subscription you already pay for. It needs to be installed and signed in once.';
    $('how').className = ready ? 'hidden' : '';
    // The install offer is only for the case it solves. With the CLI already there, the remaining
    // step is signing in, and offering to install it again would be noise.
    $('install').className = hasCli ? 'hidden' : '';
    $('howLede').textContent = hasCli
      ? 'Run claude in a terminal and sign in — it opens a browser once, and Concentus picks it up from there. You never paste a key.'
      : 'Or do it yourself in a terminal. Once installed, run claude and sign in.';
    $('finish').textContent = ready ? 'Open Concentus' : 'Continue without it';
    $('finish').className = ready ? 'primary' : '';
  }

  $('recheck').addEventListener('click', function () {
    var btn = this; btn.disabled = true; btn.textContent = 'Checking…';
    window.concentus.recheckClaude().then(function (next) {
      claude = next; renderStep2();
      btn.disabled = false; btn.textContent = 'Check again';
    });
  });

  $('locate').addEventListener('click', function () {
    window.concentus.locateClaude().then(function (next) {
      if (next) { claude = next; renderStep2(); }
    });
  });

  // The installer's output goes on screen as it arrives. An install that takes a minute behind a
  // frozen button is indistinguishable from one that has hung.
  window.concentus.onInstallOutput(function (line) {
    var out = $('installLog');
    out.className = '';
    out.textContent += line;
    out.scrollTop = out.scrollHeight;
  });

  window.concentus.claudeInstallCommand().then(function (cmd) {
    $('installCmd').textContent = cmd;
  });

  $('doInstall').addEventListener('click', function () {
    var btn = this;
    btn.disabled = true; btn.textContent = 'Installing…';
    $('installMsg').textContent = ''; $('installMsg').className = 'msg';
    $('installLog').textContent = '';
    window.concentus.installClaude().then(function (r) {
      if (r.state) { claude = r.state; renderStep2(); }
      if (r.ok && claude.command) {
        $('installMsg').textContent = '✓ Installed. Now sign in: run claude in a terminal, then /login.';
        $('installMsg').className = 'msg ok';
      } else if (r.ok) {
        // Exit code 0 but nothing where we look — worth saying plainly rather than claiming success.
        $('installMsg').textContent = '✗ The installer finished but Concentus still cannot find claude. Try "Locate claude…".';
        $('installMsg').className = 'msg bad';
      } else {
        $('installMsg').textContent = '✗ ' + r.detail;
        $('installMsg').className = 'msg bad';
      }
    }).catch(function (e) {
      $('installMsg').textContent = '✗ ' + (e && e.message ? e.message : String(e));
      $('installMsg').className = 'msg bad';
    }).then(function () {
      btn.disabled = false; btn.textContent = 'Install Claude Code';
    });
  });

  $('back').addEventListener('click', function () { showStep(1); });

  $('finish').addEventListener('click', function () {
    window.concentus.finishOnboarding($('dontask').checked);
  });

  // ---------------------------------------------------------------- steps
  function showStep(n) {
    $('step1').className = n === 1 ? '' : 'hidden';
    $('step2').className = n === 2 ? '' : 'hidden';
    $('lbl1').innerHTML = n === 1 ? '<b>1. Database</b>' : '1. Database';
    $('lbl2').innerHTML = n === 2 ? '<b>2. Claude</b>' : '2. Claude';
    if (n === 1) renderStep1(); else renderStep2();
  }

  // Prefill from what is already configured.
  document.querySelector('input[name=mode][value=' + (storage.mode === 'external' ? 'external' : 'embedded') + ']').checked = true;
  $('url').value = storage.url || '';
  $('username').value = storage.username || '';
  if (storage.hasPassword) $('password').placeholder = '•••••••• (unchanged)';
  showStep(1);
</script>
</body>
</html>`
}
