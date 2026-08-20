# Signing in with Microsoft, Google or Discord

Concentus signs people in with a password by default. Pointing it at a directory instead — so
people use the account they already have, and leaving the company removes their access here too —
takes about five minutes, and one of those minutes is a mistake almost everybody makes on the first
attempt.

Everything below is done **inside the application**, under *Resources → Members → Sign-in
providers*. It used to be six environment variables and a restart, which on a desktop install is
not something anybody can do: the shell computes the environment, and there is no file to edit. The
feature worked and nobody could turn it on.

---

## The redirect URI is the whole game

The first thing on that screen is the **redirect URI**, with a button to copy it. Register exactly
that string with your provider.

Every registration fails the same way the first time: the address registered in the directory does
not match the one the application asks for, and the provider answers with a code rather than a
sentence (`AADSTS50011` on Microsoft). So the app computes it from the request you are making
rather than describing it here — on a desktop install it is
`http://127.0.0.1:8734/api/account/oidc/callback`, but behind a domain or on a different port it is
not, and copying it beats guessing.

If it does not match character for character, nothing else you do will help.

---

## Microsoft (Entra ID)

1. [portal.azure.com](https://portal.azure.com) → **Microsoft Entra ID** → **App registrations** →
   **New registration**.
2. Name it. For "only my company", choose **Accounts in this organizational directory only**.
3. Leave the redirect URI empty for now — see the next step for why.
4. **Overview**: copy the **Application (client) ID** and the **Directory (tenant) ID**.
5. **Certificates & secrets** → **New client secret** → copy the **Value**, not the Secret ID. It
   is shown once; if you lose it, delete it and make another.
6. **API permissions** → Microsoft Graph → Delegated: `openid`, `profile`, `email`, `User.Read`.
   Grant admin consent if your tenant requires it.

### The redirect URI, and the thing everybody hits

Entra's **Redirect URIs** field refuses `http://` with a loopback address, so
`http://127.0.0.1:8734/...` cannot be typed into it. Two ways round:

- **Edit the manifest** (recommended, because it matches exactly what the app sends). *Manage →
  Manifest* and add it to `web.redirectUris`:

  ```json
  "web": {
    "redirectUris": ["http://127.0.0.1:8734/api/account/oidc/callback"]
  }
  ```

  If you are looking for `replyUrlsWithType`, you are reading about the old Azure AD Graph format.
  The portal shows the **Microsoft Graph** format now; the field is `web.redirectUris`.

- **Or register `http://localhost/api/account/oidc/callback`** as a Web platform in the normal
  field. Entra ignores the port for localhost redirects, so one entry covers any port — but then
  you have to open the app on `localhost` rather than `127.0.0.1`, or the addresses will not match.

`[::1]` is not supported by Entra at all.

### In Concentus

*Resources → Members → Sign-in providers → Microsoft*: client id, client secret, and the directory
(tenant) id. **Save and offer it.** No restart — providers are read on every request precisely so
that the button you just configured is there when you go and look.

---

## Google

**Google Cloud console** → *APIs & Services → Credentials* → **Create credentials → OAuth client
ID** → **Web application**, with the redirect URI from the screen as an authorized redirect URI.
Paste the client id and secret in Concentus. Nothing else is needed: Google publishes its own
endpoints and Concentus discovers them.

---

## Discord, and anything else

Discord is OAuth2 rather than OpenID Connect: it publishes no discovery document and calls the
person's id `id` rather than `sub`. That is configuration, not code — the preset carries its
endpoints and its claim names, so it costs a client id and a secret like the others.

The same mechanism is why a provider nobody has thought of yet is an entry in the settings rather
than a release: state its authorization, token and userinfo URLs and say which claims carry the
subject and the address.

---

## What happens the first time somebody arrives

- On an installation with **no accounts**, the first identity through a provider **administers**
  it. That is the same rule the setup screen follows: it is the account that claimed an empty
  installation. Where that window is a risk — a server reachable before anybody has claimed it —
  set `CONCENTUS_ADMIN_EMAIL` and `CONCENTUS_ADMIN_PASSWORD` before the first launch.
- Everybody after that arrives as a **Viewer**. Arriving with a valid company account proves who
  somebody is, not what they should be allowed to change. Promote them under *Resources → Members*.
- An address that already has an account is **linked** to it rather than duplicated, so somebody
  who had a password and now signs in through the directory keeps their history and their role.
- People are matched by the provider's own immutable id, **never** by their address. Addresses are
  reassigned when people leave; matching on one would eventually hand a leaver's flows, and their
  role, to whoever inherited the mailbox.

## Restricting who may sign in

`app.auth.allowed-email-domains` (under *Resources → Settings*) limits sign-in to the domains you
name. It applies to **every** way in, not only to the directory, so it is also the answer to
"nobody outside the company" on a deployment that still uses passwords. Blank allows any.

---

## When it goes wrong

| What you see | What it usually is |
|---|---|
| `AADSTS50011` | The registered redirect URI is not the one the app sends. Copy it from the screen again. |
| `AADSTS7000215` | The client secret is wrong — usually the Secret **ID** was pasted instead of its **Value**. |
| `AADSTS900561` | Something did a GET on an endpoint that only accepts POST. If it happens *during* sign-in, update the app: a shell older than rc.22 cancelled the login form's POST and re-opened it in your browser. |
| The button is not there | The provider has no client id or secret yet. It still appears on the sign-in screen and says so when pressed. |
| "not registered" after configuring it | Update to rc.21 or later. Before it, every stored setting was read and silently answered with its default. |

The sign-in screen shows **every** provider, registered or not. Showing only the configured ones
answered "can I sign in with my work account here?" with an absence, which reads as no. An
unregistered one is not a link: it says what it needs, instead of sending somebody to a provider
that will refuse them after they have typed their password.
