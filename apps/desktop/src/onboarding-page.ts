import type { RunnerState } from './runner'

/**
 * The first-run wizard: where data is kept, then signing in to Claude, then — optionally — the
 * Concentus server this machine also runs flows for.
 *
 * <p>The first two steps exist for the same reason — they are the two things that must be settled
 * before the app can do anything, and both used to fail late and confusingly. A missing Claude
 * sign-in surfaced as an error on a flow the user had just spent ten minutes building. A database
 * that could not be reached surfaced as an app that opened onto nothing at the *next* launch, by
 * which point the connection details were somewhere else entirely.
 *
 * <p>The database step therefore refuses to advance on an untested external connection. That is
 * the whole point of doing it here: the check happens while the person who typed the host name is
 * still looking at it.
 *
 * <p>The Claude step remains a prompt rather than a gate — the canvas is fully usable without it.
 *
 * <p>The server step is here because it is the one place the desktop app can be told about a
 * server at all, and because the reason it is a desktop setting belongs next to the sign-in it
 * depends on: the server never gets the Claude login, so a machine with the login is where the
 * server's flows have to run. Skipped by most people, and reachable again from the tray.
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

/**
 * Where the key that seals stored credentials lives, so the database step can say what a backup
 * of the data has to include. Only the two states worth a sentence are shown: a key kept as a
 * file (back it up with the data) and no key this launch (credentials stored as typed).
 */
export interface DataKeyNote {
  source: 'environment' | 'keyring' | 'file' | 'none'
  file: string
  detail: string
}

export function onboardingPage(
  claude: OnboardingState,
  storage: StorageState,
  /** Whether an API key is already stored, so the page opens on the answer already given. */
  hasKey: boolean,
  dataKey: DataKeyNote,
  /** The server this machine runs for, if any — the URL and whether a token is stored, never the token. */
  runner: RunnerState,
): string {
  // Entities are literal text inside <script>, so HTML-escaping would corrupt this rather than
  // protect it. `<` is what could close the element early, and these values include a JDBC URL, a
  // server URL and a filesystem path the user controls.
  const initial = JSON.stringify({ claude, storage, hasKey, dataKey, runner }).replace(/</g, '\\u003c')

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
  .opt { display: flex; gap: .6rem; align-items: flex-start; padding: .6rem .7rem; margin: .4rem 0;
         border: 1px solid var(--line); border-radius: 9px; cursor: pointer; }
  .opt:hover { border-color: var(--accent); }
  .opt input { margin-top: .25rem; }
  .opt .sub { color: var(--muted); font-size: 13px; }
  #apikey input[type=password] { width: 100%; }
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
    <span id="lbl1">1. Database</span><span class="sep">›</span><span id="lbl2">2. Claude</span><span class="sep">›</span><span id="lbl3">3. Server</span>
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
    <!-- Outside #extFields on purpose. Saving can fail on either choice, and #extFields is hidden
         whenever the built-in database is selected — which is how a refused save came to look like
         a Continue button that did nothing at all: the reason was on the page, in a hidden div. -->
    <p class="msg" id="saveMsg"></p>
    <p class="foot" id="step1Foot"></p>
    <!-- Where the credential key is, when that is something the person should know: a key kept
         as a file belongs in the same backup as the data, and "no key this launch" is a state
         somebody should hear about here rather than discover as a locked credential. -->
    <p class="foot" id="keyFoot"></p>
  </section>

  <!-- ---------------------------------------------------------------- step 2 -->
  <section id="step2" class="hidden">
    <h1 id="title">One step before you can run flows</h1>

    <!-- Asked first, because it decides whether anything below is relevant. Somebody paying per
         token has no use for a CLI login, and installing one before asking would be doing work
         on their behalf that they did not want. -->
    <div id="billing">
      <p class="lede">How should Concentus pay for what it runs?</p>
      <label class="opt"><input type="radio" name="billing" value="subscription" checked>
        <span><b>Your Claude subscription</b><br>
        <span class="sub">Runs through Claude Code on this machine. No second bill, and no key to
        look after. Needs a one-time sign-in.</span></span></label>
      <label class="opt"><input type="radio" name="billing" value="apikey">
        <span><b>An Anthropic API key</b><br>
        <span class="sub">Billed per token to your Anthropic account. Nothing to install and no
        sign-in — right for a machine nobody sits at.</span></span></label>
    </div>

    <!-- The key, and only when it was asked for. -->
    <div id="apikey" class="hidden">
      <label for="apiKeyInput">API key</label>
      <input id="apiKeyInput" type="password" placeholder="sk-ant-..." autocomplete="off" spellcheck="false">
      <div class="actions">
        <button class="primary" id="saveKey">Save the key</button>
        <button id="clearKey">Remove it</button>
      </div>
      <p class="msg" id="keyMsg"></p>
      <p class="lede">Kept in your operating system's keyring, on this machine only. It is never
        sent anywhere except to Anthropic, and never stored in a flow.</p>
    </div>

    <div id="claudeSetup">
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
      <div class="actions" id="loginActions">
        <button class="primary" id="doLogin">Sign in now</button>
      </div>
      <p class="msg" id="loginMsg"></p>
      <p class="lede" id="howLede">A terminal opens on the sign-in itself and your browser follows.
        Concentus picks it up from there — you never paste a key. To do it by hand:</p>
      <pre>claude auth login</pre>
    </div>
    </div>

    <div class="actions">
      <button class="primary" id="recheck">Check again</button>
      <button id="locate">Locate claude…</button>
      <div class="spacer"></div>
      <button id="back">Back</button>
      <button id="toServer">Continue without it</button>
    </div>

    <label class="ask"><input type="checkbox" id="dontask"> Don't check on future launches</label>

    <p class="foot">
      Either can be changed later: this screen is under <b>Setup…</b> on the Concentus tray icon. A
      key already in your environment as <code>ANTHROPIC_API_KEY</code> still works; one saved here
      takes precedence over it.
    </p>
  </section>

  <!-- ---------------------------------------------------------------- step 3 -->
  <section id="step3" class="hidden">
    <h1>Connect this machine to a Concentus server?</h1>
    <p class="lede">Optional. A Concentus server that somebody deployed keeps the flows, the runs
      and the approvals; this machine then also executes flows for that server, through the Claude
      login that is here. The server never receives the login — only the work — and everything
      local keeps working exactly as it does now.</p>

    <!-- The form, or the connection: never both. Changing the server is disconnect, then connect,
         so the token can never be half-replaced. -->
    <div id="runnerForm">
      <div class="fields">
        <label><span>Server URL</span>
          <input id="runnerUrl" placeholder="https://concentus.example.com" spellcheck="false"></label>
        <label><span>Registration token</span>
          <input id="runnerToken" type="password" placeholder="crn_…" autocomplete="off" spellcheck="false"></label>
        <label><span>Name (optional)</span>
          <input id="runnerName" placeholder="How the server lists this machine"></label>
      </div>
      <p class="foot">The token comes from the server's <b>Resources → Runners</b> screen, where it is
        shown once. It is kept in your operating system's keyring, on this machine only.</p>
    </div>

    <div class="status hidden" id="runnerStatus"></div>

    <div class="actions" style="margin-top:1rem">
      <button class="primary" id="runnerConnect">Connect</button>
      <button id="runnerDisconnect" class="hidden">Disconnect</button>
      <div class="spacer"></div>
      <button id="back3">Back</button>
      <button id="finish">Skip</button>
    </div>
    <p class="msg" id="runnerMsg"></p>
  </section>

<script>
  var s = ${initial};
  var claude = s.claude;
  var storage = s.storage;
  var hasKey = !!s.hasKey;
  var dataKey = s.dataKey || { source: 'keyring', file: '', detail: '' };
  var runner = s.runner || { url: null, name: null, hasToken: false };
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

  function renderKeyNote() {
    if (dataKey.source === 'file') {
      $('keyFoot').innerHTML = 'Stored credentials are encrypted, but this machine has no OS keyring, '
        + 'so the key is a file only your account can read: <code>' + esc(dataKey.file) + '</code>. '
        + 'Treat it as the credentials themselves, and back it up with the data.';
    } else if (dataKey.source === 'none') {
      $('keyFoot').innerHTML = 'The credential key could not be used this launch (' + esc(dataKey.detail)
        + ') Credentials already encrypted show as locked until it can be read again; new ones are '
        + 'stored as typed.';
    } else {
      $('keyFoot').textContent = '';
    }
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
    $('saveMsg').textContent = '';
    $('saveMsg').className = 'msg';
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
    var btn = this; btn.textContent = 'Saving\u2026';
    $('saveMsg').textContent = ''; $('saveMsg').className = 'msg';
    window.concentus.saveStorage(draft()).then(function () {
      showStep(2);
    }).catch(function (e) {
      $('saveMsg').textContent = '\u2717 ' + (e && e.message ? e.message : String(e));
      $('saveMsg').className = 'msg bad';
    }).then(function () {
      busy = false; btn.textContent = 'Continue'; renderStep1();
    });
  });

  // ---------------------------------------------------------------- step 2
  /** Which way this machine pays. Remembered only for the length of the wizard. */
  function billingChoice() {
    var picked = document.querySelector('input[name=billing]:checked');
    return picked ? picked.value : 'subscription';
  }

  function renderStep2() {
    var apiKey = billingChoice() === 'apikey';
    $('apikey').className = apiKey ? '' : 'hidden';
    // Everything about the CLI hides when it is not the way this machine pays. Leaving it on
    // screen would be asking somebody to install a tool their answer just made irrelevant.
    $('claudeSetup').className = apiKey ? 'hidden' : '';
    // Checking for a CLI and locating one are questions about the half that is hidden.
    $('recheck').className = apiKey ? 'hidden' : 'primary';
    $('locate').className = apiKey ? 'hidden' : '';
    if (apiKey) {
      $('title').textContent = hasKey ? "You're ready to go" : 'One step before you can run flows';
      $('toServer').textContent = hasKey ? 'Continue' : 'Continue without it';
      $('toServer').className = hasKey ? 'primary' : '';
      return;
    }

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
    // The one-click sign-in only makes sense with a CLI to sign in to.
    $('loginActions').className = hasCli && !claude.loggedIn ? 'actions' : 'hidden';
    // The install offer is only for the case it solves. With the CLI already there, the remaining
    // step is signing in, and offering to install it again would be noise.
    $('install').className = hasCli ? 'hidden' : '';
    $('howLede').textContent = hasCli
      ? 'A terminal opens on the sign-in itself and your browser follows. Concentus picks it up from there — you never paste a key. To do it by hand:'
      : 'Or do it yourself, once it is installed:';
    $('toServer').textContent = ready ? 'Continue' : 'Continue without it';
    $('toServer').className = ready ? 'primary' : '';
  }

  Array.prototype.forEach.call(document.querySelectorAll('input[name=billing]'), function (el) {
    el.addEventListener('change', renderStep2);
  });

  $('saveKey').addEventListener('click', function () {
    var btn = this; btn.disabled = true; btn.textContent = 'Saving…';
    window.concentus.saveApiKey($('apiKeyInput').value).then(function (r) {
      hasKey = r.hasKey;
      $('keyMsg').textContent = r.detail;
      // The field is cleared on success and kept on failure: a rejected key is usually a typo,
      // and making somebody paste it again to fix one character is a small cruelty.
      if (r.ok) $('apiKeyInput').value = '';
      btn.disabled = false; btn.textContent = 'Save the key';
      renderStep2();
    });
  });

  $('clearKey').addEventListener('click', function () {
    window.concentus.saveApiKey(null).then(function (r) {
      hasKey = r.hasKey;
      $('keyMsg').textContent = r.detail;
      renderStep2();
    });
  });

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

  // While a sign-in terminal is open, the wizard checks by itself every few seconds — asking a
  // person to complete an OAuth hop AND come back to press "Check again" loses half of them at
  // the second step. Bounded, so an abandoned sign-in does not poll forever.
  var loginPoll = null;
  function pollForLogin() {
    if (loginPoll) return;
    var until = Date.now() + 5 * 60 * 1000;
    loginPoll = setInterval(function () {
      if (claude.loggedIn || Date.now() > until) {
        clearInterval(loginPoll); loginPoll = null;
        return;
      }
      window.concentus.recheckClaude().then(function (next) {
        claude = next;
        renderStep2();
        if (claude.loggedIn) {
          $('loginMsg').textContent = '✓ Signed in. You are ready to go.';
          $('loginMsg').className = 'msg ok';
          clearInterval(loginPoll); loginPoll = null;
        }
      });
    }, 4000);
  }

  $('doLogin').addEventListener('click', function () {
    var btn = this; btn.disabled = true;
    window.concentus.openClaudeLogin().then(function (r) {
      btn.disabled = false;
      if (r.state) { claude = r.state; renderStep2(); }
      if (r.ok) {
        $('loginMsg').textContent = 'A terminal opened running claude — complete the sign-in there (it opens your browser once). Concentus will notice by itself.';
        $('loginMsg').className = 'msg ok';
        pollForLogin();
      } else {
        $('loginMsg').textContent = '✗ ' + r.detail;
        $('loginMsg').className = 'msg bad';
      }
    });
  });

  $('doInstall').addEventListener('click', function () {
    var btn = this;
    btn.disabled = true; btn.textContent = 'Installing…';
    $('installMsg').textContent = ''; $('installMsg').className = 'msg';
    $('installLog').textContent = '';
    window.concentus.installClaude().then(function (r) {
      if (r.state) { claude = r.state; renderStep2(); }
      if (r.ok && claude.command && r.loginOpened) {
        $('installMsg').textContent = '✓ Installed. A terminal opened running claude — complete the sign-in there (it opens your browser once). Concentus will notice by itself.';
        $('installMsg').className = 'msg ok';
        pollForLogin();
      } else if (r.ok && claude.command) {
        $('installMsg').textContent = '✓ Installed. Now sign in: press "Sign in now" below.';
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
  $('toServer').addEventListener('click', function () { showStep(3); });

  // ---------------------------------------------------------------- step 3
  /** Half a configuration — a URL whose token is gone — is shown as the form, URL filled in. */
  function runnerConfigured() { return !!(runner.url && runner.hasToken); }

  function renderRunnerStatus(st) {
    var connected = !!(st && st.connected);
    var detail = connected
      ? 'Connected. Flows the server assigns to this runner execute here, on this machine\\'s Claude login.'
      : 'Not connected' + (st && st.error ? ' \\u2014 ' + st.error : ' yet \\u2014 the backend is dialing the server.');
    $('runnerStatus').innerHTML =
      row(true, 'Server', runner.url + (runner.name ? '  \\u00b7  listed as \\u201c' + runner.name + '\\u201d' : '')) +
      row(connected, 'Connection', detail);
  }

  function refreshRunnerStatus() {
    window.concentus.runnerStatus().then(renderRunnerStatus).catch(function (e) {
      renderRunnerStatus({ connected: false, error: e && e.message ? e.message : String(e) });
    });
  }

  // The agent dials once the restarted backend is up, so the first answer after Connect is
  // usually "not yet"; the page keeps asking for half a minute rather than leaving that as the
  // last word. Bounded, so a server that never answers is not polled forever.
  var statusPoll = null;
  function pollRunnerStatus() {
    if (statusPoll) return;
    var until = Date.now() + 30 * 1000;
    statusPoll = setInterval(function () {
      if (!runnerConfigured() || Date.now() > until) {
        clearInterval(statusPoll); statusPoll = null;
        return;
      }
      window.concentus.runnerStatus().then(function (st) {
        renderRunnerStatus(st);
        if (st && st.connected) { clearInterval(statusPoll); statusPoll = null; }
      });
    }, 3000);
  }

  function renderStep3() {
    var on = runnerConfigured();
    $('runnerForm').className = on ? 'hidden' : '';
    $('runnerStatus').className = on ? 'status' : 'status hidden';
    $('runnerConnect').className = on ? 'hidden' : 'primary';
    $('runnerDisconnect').className = on ? '' : 'hidden';
    $('finish').textContent = on ? 'Open Concentus' : 'Skip';
    $('finish').className = on ? 'primary' : '';
    if (on) {
      renderRunnerStatus(null);
      refreshRunnerStatus();
    }
  }

  $('runnerConnect').addEventListener('click', function () {
    var btn = this; btn.disabled = true; btn.textContent = 'Connecting\\u2026';
    $('runnerMsg').textContent = ''; $('runnerMsg').className = 'msg';
    window.concentus.saveRunner({
      url: $('runnerUrl').value, token: $('runnerToken').value, name: $('runnerName').value,
    }).then(function (r) {
      if (r.runner) runner = r.runner;
      $('runnerMsg').textContent = (r.ok ? '\\u2713 ' : '\\u2717 ') + r.detail;
      $('runnerMsg').className = 'msg ' + (r.ok ? 'ok' : 'bad');
      // The token is cleared on success and kept on failure, as the API key's field is: a refused
      // token is usually a truncated paste, and the fix is one character, not the whole thing.
      if (r.ok) { $('runnerToken').value = ''; pollRunnerStatus(); }
    }).catch(function (e) {
      $('runnerMsg').textContent = '\\u2717 ' + (e && e.message ? e.message : String(e));
      $('runnerMsg').className = 'msg bad';
    }).then(function () {
      btn.disabled = false; btn.textContent = 'Connect'; renderStep3();
    });
  });

  $('runnerDisconnect').addEventListener('click', function () {
    var btn = this; btn.disabled = true; btn.textContent = 'Disconnecting\\u2026';
    window.concentus.clearRunner().then(function (r) {
      if (r.runner) runner = r.runner;
      $('runnerMsg').textContent = (r.ok ? '\\u2713 ' : '\\u2717 ') + r.detail;
      $('runnerMsg').className = 'msg ' + (r.ok ? 'ok' : 'bad');
    }).catch(function (e) {
      $('runnerMsg').textContent = '\\u2717 ' + (e && e.message ? e.message : String(e));
      $('runnerMsg').className = 'msg bad';
    }).then(function () {
      btn.disabled = false; btn.textContent = 'Disconnect'; renderStep3();
    });
  });

  $('back3').addEventListener('click', function () { showStep(2); });

  $('finish').addEventListener('click', function () {
    window.concentus.finishOnboarding($('dontask').checked);
  });

  // ---------------------------------------------------------------- steps
  function showStep(n) {
    $('step1').className = n === 1 ? '' : 'hidden';
    $('step2').className = n === 2 ? '' : 'hidden';
    $('step3').className = n === 3 ? '' : 'hidden';
    $('lbl1').innerHTML = n === 1 ? '<b>1. Database</b>' : '1. Database';
    $('lbl2').innerHTML = n === 2 ? '<b>2. Claude</b>' : '2. Claude';
    $('lbl3').innerHTML = n === 3 ? '<b>3. Server</b>' : '3. Server';
    if (n === 1) renderStep1(); else if (n === 2) renderStep2(); else renderStep3();
  }

  // Fixed for the life of the page: the key was decided when the backend started.
  renderKeyNote();

  // Prefill from what is already configured.
  // Opening on the answer already given: somebody who saved a key and came back is not being
  // asked the question again, they are being shown what they chose.
  if (hasKey) document.querySelector('input[name=billing][value=apikey]').checked = true;
  document.querySelector('input[name=mode][value=' + (storage.mode === 'external' ? 'external' : 'embedded') + ']').checked = true;
  $('url').value = storage.url || '';
  $('username').value = storage.username || '';
  if (storage.hasPassword) $('password').placeholder = '•••••••• (unchanged)';
  $('runnerUrl').value = runner.url || '';
  $('runnerName').value = runner.name || '';
  showStep(1);
</script>
</body>
</html>`
}
