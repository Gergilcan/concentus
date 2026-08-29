# Licensing: free for individuals, paid for teams — design

Date: 2026-08-22. Status: approved by Gerard (chat), pending spec review.

## What this is

Concentus is PolyForm Noncommercial: free for personal/noncommercial use, commercial use needs a
license from the author. Until now that line existed only on paper. This design gives it a
mechanism: individual developers request a free license and receive it automatically by email;
companies buy an enterprise license (manual sale, "contact us"), and the enterprise features —
the shared database deployment — technically require one.

Honesty first: the code is open, anyone can patch the gate out. The real barrier for a company is
legal (the LICENSE.md they would be violating); the technical gate is the honest reminder and the
seat meter, not DRM. The design is deliberately simple and unobfuscated.

## Decisions taken (with Gerard, 22 Aug)

| Question | Decision |
| --- | --- |
| Without any license | Everything works EXCEPT enterprise features. No wall, no trial clocks. |
| Paid line | Shared/external database + server mode (`app.auth.enabled=true`), multi-user & roles, SSO providers. Slack/Teams approvals stay free. |
| Sales | Enterprise: manual, "contact us" — no payment gateway for now. Free: fully automatic. |
| Verification | Offline Ed25519 signature. No phone-home, no license server. |
| Terms | Enterprise: monthly or annual (annual −10%, a pricing-page fact, not code). Free: perpetual. |
| Issuer | Vercel function + Resend, FREE licenses only. Enterprise licenses minted by a local CLI on Gerard's machine. |
| Seats | Enterprise licenses carry `seats: N`; the server refuses to create member N+1, naming the limit. |
| Expiry | 14-day grace with banners after `expires`; then server mode refuses to start. Data intact, export works, individual mode always available. |

## 1. License format

One pastable line: `CONCENTUS.` + base64url(JSON payload) + `.` + base64url(Ed25519 signature).

Payload:

```json
{
  "v": 1,
  "tier": "individual" | "enterprise",
  "licensee": "ACME S.L." | "Jane Dev",
  "email": "who it was issued to",
  "seats": 20,            // enterprise only
  "issued": "2026-08-22",
  "expires": "2027-08-22" | null,   // null: the free perpetual license
  "id": "uuid"
}
```

**Two keypairs.** The `individual` signing key lives in Vercel (automatic issuance); the
`enterprise` signing key exists only on Gerard's machine. The app embeds BOTH public keys and
cross-checks tier↔key: an `enterprise` payload signed by the individual key is invalid. A Vercel
compromise can therefore mint nothing sellable.

Private keys live outside every repo (Gerard's local key directory, plus one Vercel env var).
The public keys are constants in the backend source.

## 2. Backend enforcement (`apps/backend`)

New `LicenseService`:

- **Sources**, first match wins: `CONCENTUS_LICENSE` env var → `license.key` file in the data
  directory. Pasting a license in Resources → Settings WRITES that file — one storage, no
  license row in a database the license itself gates. Env exists because a headless server
  deployment sets its license before any UI does.
- Verifies signature (against the tier-matching public key) and dates at startup and on change;
  result exposed on `/api/auth/status` (tier, licensee, seats, expiry, days-of-grace-left) so the
  UI can show state and banners without a new endpoint.

Gates:

- **Server mode** (`app.auth.enabled=true`) and **external PostgreSQL** (the configured-URL path
  in `EmbeddedPostgresConfig`) require a valid enterprise license. Within 14 days after
  `expires`: everything works, admins see a banner counting down. After grace: startup refuses
  with a message that names the fix (renew / remove the external config). Nothing is deleted;
  the embedded individual mode still runs; export endpoints still work up to the cutoff.
- **SSO**: registering identity providers (Google/Microsoft) requires enterprise. Existing
  providers in an expired deployment die with the server mode they live in.
- **Seats**: creating a member beyond `seats` is refused with the limit and the upgrade path in
  the message. Existing members above a shrunk limit are never deleted — only new ones refused.
- **Individual/desktop mode: zero change.** No license needed, ever. A pasted free license shows
  "Licensed to X" in Settings — registration, not a key.

## 3. Free-license issuer (website + Vercel + Resend)

- Form on the website (name + email) → `apps/website/api/license.mjs` (Vercel serverless
  function, same repo the site deploys from).
- The function: rate-limits (per IP and per email), signs an `individual` license with the key
  from Vercel env, sends it via Resend to the given address. Email delivery IS the verification —
  a fake address receives nothing usable.
- The HTTP answer is generic ("if the address is valid, the license is on its way") so the
  endpoint confirms nothing about any address.
- Resend's audience/log doubles as the lead list. No database.

## 4. Enterprise CLI (Gerard's machine only)

`mint-license.mjs` (lives in the repo; the KEY does not):

```
node mint-license.mjs --licensee "ACME S.L." --email ops@acme.com --seats 20 --months 1
node mint-license.mjs --licensee "ACME S.L." --email ops@acme.com --seats 20 --years 1
```

- Signs with the local enterprise key (path via env/config, outside the repo), sends the license
  through Resend (same delivery channel as free), and appends a JSON line to a local ledger file
  (who, what, when, expiry) — Gerard's record of what is out there, also outside the repo.
- Renewal, monthly re-issue, seat upgrades: re-run the command. When a payment gateway arrives
  someday, it automates exactly this CLI's job — the license format does not change.

## 5. Website & docs

- Pricing section: **Individual — free** (the form) · **Enterprise — per seat, monthly or annual
  (annual −10%) — contact**. Numbers pending Gerard's pricing; the page ships with the contact
  path either way.
- Docs page: what requires a license, how to request each kind, where to paste/install it
  (Settings, env var, file), what happens at expiry.
- README: short section pointing at the docs.

## 6. Not in scope (deliberately)

- No payment gateway, no Stripe/Holded integration (manual sales only, for now).
- No license server, telemetry, activation counts, or revocation before expiry.
- No gating of anything currently free for individuals (Slack/Teams approvals stay free).
- No obfuscation or anti-tamper beyond the signature itself.

## 7. Testing

- **Unit (backend)**: parse/verify round-trip against fixture licenses (committed test vectors
  generated with throwaway TEST keys, never the real ones); tampered payload; signature from the
  wrong key; tier↔key mismatch; expiry math incl. grace boundary days; seat limit at N and N+1.
- **E2E**: server mode refuses to start unlicensed; starts with a valid enterprise fixture
  license; member N+1 refused with the message; expired-in-grace shows the banner.
- **Issuer**: the Vercel function and the CLI share the signing/format module and its tests;
  a license minted by each verifies against the backend's parser (same fixtures).

## 8. Build order

1. Backend: format, `LicenseService`, gates, seats, grace — with the unit and e2e tests above.
2. Key generation + enterprise CLI. **Selling is possible from this point.**
3. Vercel function + website form + Resend (automatic free licenses).
4. Pricing page and docs.

## 9. Team tier — self-serve, up to ten seats (added 2026-08-29)

The gap the design above left: enterprise is "contact us", so a team of three wanting the shared
database had to write an email and wait for a hand-minted license. This section adds a third
tier that a card buys, WITHOUT giving up the security property in section 1 — a Vercel
compromise still mints nothing sellable at enterprise scale.

| Question | Decision |
| --- | --- |
| Tier name | `team`. Unlocks exactly what enterprise unlocks (shared database, members up to `seats`, SSO); the backend gates ask "is a paid license active", never "which one". |
| Key | A THIRD keypair. Private half in Vercel (`TEAM_SIGNING_KEY`, PKCS8 base64, one line); public half in `application.properties` as `license.team-public-key` (SPKI base64). The enterprise key stays on the author's machine. Blank property = the tier does not exist for that build, and a pasted team token says so by name. |
| Ceiling | The verifier refuses a team license with more than **10 seats** or with **no expiry**, even when genuinely signed. That ceiling — not the key's location — is what makes a key in Vercel acceptable: the worst a web-tier compromise can mint is a small, expiring license. Bigger teams are enterprise, and the enterprise key is not there to steal. |
| Terms | Monthly or annual (annual −10 %), never perpetual. Same 14-day grace as enterprise after `expires`. |
| Price | `TEAM_PRICE_MONTHLY_PER_SEAT` in Vercel, read by `GET /api/pricing`; nowhere in HTML or code. Null → the card prints "Pricing to be announced — write in" and checkout answers 503. |
| Payment | Stripe Checkout, one payment per term (`mode: payment`), through the REST API with `fetch` — no SDK, no Product/Price objects: the line item carries an ad-hoc `price_data`. Renewal is buying again, exactly as enterprise renews by re-running the CLI. A subscription that re-mints on each invoice is a possible later step, not a different design. |
| Fulfilment | `POST /api/stripe-webhook` verifies `Stripe-Signature` (HMAC-SHA256 over `t.body`, 300-second tolerance, constant-time compare), and on a PAID `checkout.session.completed` (or `async_payment_succeeded` for delayed methods) mints the team license from the session's `metadata {seats, term, email}`, emails it through the same Resend path the free license uses, and writes the ledger row (`kind: purchase`, `stripe_session`). |
| Idempotency | Per session id, carried by a partial unique index in the ledger: a second delivery of the same session finds the row `sent` and sends nothing. A row still `pending_send` (Resend failed the first time) is taken over by the retry, which is why that one failure answers 500 — Stripe retries for up to three days, and the retry is the fix. Every other outcome answers 200 so Stripe stops. |
| Not configured | Any required env var absent (`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `TEAM_SIGNING_KEY`, `TEAM_PRICE_MONTHLY_PER_SEAT`, plus the Resend pair) → a clear 503 naming what is missing. Never a stack trace. |
| Key generation | `node apps/website/scripts/keygen.mjs` prints both halves and writes nothing. No key is ever committed. |

Section 6's "no payment gateway" is superseded for THIS tier only; enterprise stays a manual
sale, and the enterprise CLI is unchanged.

## 10. Enterprise trial — automatic, fourteen days (added 2026-08-29)

A company evaluating the shared database should not have to email for a license first. The
trial is a form on the site (name, company, email, seats up to 10) that mints a TEAM license with
`expires = today + 14 days`, the seats asked for, and `trial: true` in the payload — signed with
the team key, emailed through Resend, recorded in the ledger as `kind: trial`.

| Question | Decision |
| --- | --- |
| What it is | A team license in every way the gates care about (shared database, members up to seats, SSO), so no new gate and no new tier: `trial` is a flag, not a fourth key. |
| How long | 14 days, then the ordinary 14-day grace with the same banner, then one seat. Nothing is deleted; a bought license picks up where the trial left off. |
| How many | One per address, carried by a partial unique index in the ledger (`email where kind = 'trial'`). The second request gets the same generic line and no email — the answer never says "you already had one". Without a ledger the rule cannot be remembered and the trial is issued anyway, logged. |
| What the app shows | `LicenseStatus.trial`; the Settings panel leads with "Trial — N days left", counted from `expires`. The expired-beyond-grace messages say "trial" and point at buying, not renewing. |
| Not configured | No `TEAM_SIGNING_KEY` (or Resend pair) → 503 "trials are not open yet", honest rather than generic: with no key there is nothing on its way. |

## 12. Audit trail and retention (added 2026-08-29)

Item 4 of the Enterprise line: the record of who did what, its export, and what a deployment
keeps. Everything here reads the one gate in `Feature` / `LicenseService.allows(...)`; no tier
question is asked anywhere else.

| Question | Decision |
| --- | --- |
| Where it lives | `audit_events(id bigserial, at, organization_id, actor_email, actor_role, kind, subject_type, subject_id, subject_label, detail_json)` — migration `V16__audit_events.sql`. Append-only by convention; the only delete is retention's. A serial id because the trail is paged by `before=<id>`, and a monotonic integer is that cursor. |
| What is written | `AuditService.record(kind, subjectType, subjectId, label, detail)`. The actor is read from `OrgContext` **inside** the service, never passed by the caller; with no principal the row says `system`, or `system:<trigger>` via `recordSystem(trigger, …)` — cron, webhook, mail, watch, api, subflow, retention. The role is copied at the time, not joined later. |
| Kinds | `AuditKinds`: `run.{started,stopped,approved,rejected,retried,resumed,golden_set,golden_unset}`, `flow.{created,saved,deleted,published,unpublished,token_regenerated}`, `member.{invited,role_changed}`, `credential.{created,updated,deleted}`, `setting.changed`, `license.installed`, `backup.exported`, `retention.purged`. Publish/unpublish/token changes are detected in `FlowController.save` by comparing the input node before and after — no endpoint of their own exists. |
| Never written | A credential's value, a secret setting's value, a license token, an endpoint token. Labels, keys, ids, counts and flags only — enforced at every recording site, stated in the migration. |
| Failure | Never blocks the action. `record` catches, logs a warning naming the kind and subject, and returns. A trail with a hole in it has the hole in the log. |
| Run relabelling | `RunService.start` became `launch` (registry + dispatch) plus the audit row. Callers that relabel a run — the published endpoint, a sub-flow, a golden check, a block re-run, retry, resume — record **once**, after the label is final, so a webhook's run is never on record as "cron". Retry and resume are their own kinds with `of: <original>`; a fresh `run.started` is not also written for them. |
| Reading | `GET /api/audit?actor=&kind=&from=&to=&before=&limit=` (ADMIN, every tier), newest first; `actor` is a case-blind substring, dates are inclusive UTC days or epoch millis; `GET /api/audit/status` carries the kinds, the export refusal and the retention in force. *Resources → Audit*: filters, table, Load more. |
| Export | `GET /api/audit/export?format=csv\|json&…` is `Feature.AUDIT_EXPORT`: refused with `refusal(...)` as a 403 whose body is the feature's own sentence; the panel shows the buttons disabled with that sentence. Streamed oldest-first, so a year of trail never sits in memory. |
| Retention | `RetentionService`, `@Scheduled` nightly at 03:17 (`@EnableScheduling` on the application class), `POST /api/retention/run-now` for ADMIN. Team (`license.teamTier()`): runs older than `TEAM_RETENTION_DAYS` except golden ones; flow versions older than that except the current and any a golden run executed; audit events older than that. Enterprise (`allows(UNLIMITED_RETENTION)`): nothing — unless `retention.enterprise-days` (Settings → Retention, 0 = forever) names a window; the setting is read on that tier alone. Free: nothing. The purge writes its own `retention.purged` row as `system:retention`, and evicts what it deleted from `RunService`'s in-memory registry so an upsert cannot resurrect it. |
| What the panel says | The window in force and the reason, in the backend's words (`RetentionService.Policy.reason`): "Team license: … 90 days …", "Enterprise license: … without limit", or the administrator's chosen number. |
| Tests | `AuditServiceTest` (actor, system actor, a failing store never throws), `AuditControllerTest` and `RetentionServiceTest` against the embedded PostgreSQL with the fixture licenses (team purges and spares the golden pair; enterprise and free keep all; the enterprise setting is ignored on team), the recording sites in `RunServiceTest` / `FlowControllerTest`, and `AuditPanel.test.tsx` (rows, filters, paging, the disabled export with its refusal). |
| Not done | Member removal and license removal have no endpoint today, so there is no row for them; `PolicyStore` does not exist on this branch, so no policy row either — the site is `AuditService.record` when it arrives. |
