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
