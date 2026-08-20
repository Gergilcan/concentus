# Quote requests by email → Holded estimates

A flow that watches an IMAP folder and, for each matching message, asks an agent to create a
**draft** estimate in Holded through Holded's MCP server.

It never sends, accepts, invoices or deletes anything.

This is a **flow**, not a subsystem. Everything below is either a node on the canvas or a system
prompt you can edit — there is no bespoke integration to configure.

---

## The shape

```
input (mail: folder + conditions)  →  agent "Presupuestos"  ──  mcp: holded
```

A saved flow named **Presupuestos por correo → Holded** appears in the Flows page on first start.
Open it on the canvas and edit it like any other.

- The **Input node** polls IMAP and starts one run per matching message.
- The **agent** receives the mail already read — sender, subject, date, body as text, and the text
  extracted from PDF / Word / Excel attachments — and does the commercial work.
- The **MCP node** gives it Holded's tools.

## Why IMAP and not SMTP

SMTP is a transport: it hands a message over and forgets it. Folders, flags and read state live in
the mail **store**, so everything this flow relies on is IMAP-only:

| Condition | IMAP |
|---|---|
| in the folder `Presupuestos` | `SELECT` |
| unread | `SEARCH UNSEEN` |
| flagged | `SEARCH FLAGGED` |
| from / subject / body contains | `SEARCH FROM · SUBJECT · BODY` |
| move to `Presupuestos/Procesados` afterwards | `COPY` + `\Deleted` + `EXPUNGE` |

Over SMTP none of those exist — there would be no folder to watch and nowhere to move the message
once handled.

Conditions are pushed into the IMAP `SEARCH` so the server does the filtering. Two are applied
locally afterwards because the protocol cannot express them: *has attachments*, and the exclusion
of messages already processed.

---

## Setting it up

### 1. The mailbox

Create the folders you want in the mailbox — e.g. `Presupuestos` and `Presupuestos/Procesados`.

Add the mailbox password under **Resources → Credentials**. It is encrypted with AES-256-GCM before
being written, under a master key the installation generates for itself on the first start and keeps
in `secret.key` beside its data. Nothing to set up — but that file is what makes the stored
passwords readable, so it belongs in whatever backs up the database. To hold the key somewhere else
(a vault, a container secret), set `CONCENTUS_SECRET_KEY` and it is used instead of the file.

Then on the Input node set the host, port, username and folder, and pick the credential from the
dropdown.

> **The node stores the credential id, not the value.** Every flow save snapshots the flow JSON
> into version history, and duplicating a flow copies its nodes — a secret on a node, even
> encrypted, would fan out into every revision and every copy.
>
> **What the encryption buys.** It protects a leaked database backup, a database-only compromise,
> or a read-only SQL injection. It does not protect against someone who compromises the server,
> since the key must be readable there to be usable. Nothing ever returns a stored value — not to
> a user, not to an administrator.

**Microsoft 365:** Basic authentication for IMAP was retired in Exchange Online, so a Microsoft
mailbox needs OAuth2 (XOAUTH2) with an Entra app registration granted `IMAP.AccessAsApp`. Gmail and
self-hosted IMAP work with an app password.

### 2. The conditions

All optional — blank means "don't filter on this". A node with only a folder set matches everything
in it.

**Unread only** defaults to on, deliberately: without it, the first poll of an existing folder
would start a run for every message ever received.

**Max runs per poll** caps a single tick, so a folder with a thousand unread messages doesn't
launch a thousand agents. The rest are picked up on later polls.

### 3. Holded

Add the Holded MCP server URL to the MCP node (or pick a saved one from Resources → MCP Servers),
and select its bearer token from **Resources → Credentials**.

Give the token only what the flow needs: read and write contacts and estimates. Do **not** grant
delete, send, accept, invoice, payment, banking, accounting or payroll scopes — nothing here uses
them, and granting them only widens what a mistake could reach.

### 4. Run it

The flow ships enabled but inert: host and username are blank, so nothing polls until you fill them
in. Watch the first few runs before trusting it.

---

## How duplicates are prevented

Two layers, because either alone has a hole.

**Before the run starts**, the poller records the message's RFC 5322 `Message-ID` in
`processed_mail` and only starts the run if that insert succeeded. It is an insert against a unique
index, not a read-then-write, so two overlapping polls on a slow server cannot both win. Keyed on
`Message-ID` rather than the IMAP UID because the UID does not survive the folder move this flow
performs.

Marking mail read and moving it usually prevents re-matching too — but not always. A run can fail
before filing, a human can mark something unread, a move can be undone. So the record is kept
independently of the mailbox's own state.

**Inside the run**, step 1 of the agent's instructions is to search Holded for an estimate whose
notes carry this `Message-ID`, and stop if one exists. That is the layer that survives a database
restore from an older backup, where our own record is gone but Holded's is not — which is why the
agent is told to write the `Message-ID` into every estimate's notes.

---

## Security

**The email is hostile input.** Anyone can write to a monitored mailbox, so the body and attachments
are attacker-controlled text going into a prompt. Three things contain that:

1. the message is fenced with an **unguessable, per-run marker**, so a sender who knows the format
   still cannot close the fence and continue as if they were the system;
2. the metadata the mail system established — sender, subject, date — is stated **outside** the
   fence and labelled verified, so the agent never reads a "From:" line out of a body that could
   claim anything;
3. the system prompt states the prohibitions explicitly and tells the agent to *report* attempted
   manipulation rather than obey it.

**Attachments are hostile too.** The filename and declared MIME type are written by the sender, so
neither is used for routing — every decision comes from the magic bytes. Executables, macro-enabled
Office files and archives are refused by content. Nothing is executed, no macro is evaluated,
spreadsheet formulas are read as cached values rather than evaluated, and size and count are capped.

**Credentials stay out of the flow.** The node holds a credential id; the value is encrypted in the
`credentials` table and no API returns it. See [Stored credentials](../../README.md#stored-credentials).

**Logs are redacted** — bearer tokens, JWTs and email addresses are stripped before anything is
written.

---

## Troubleshooting

**Nothing happens, and there are no errors.** Check the backend log at startup: a half-configured
mail node is reported by name (`Flow 'X' has a mail trigger missing: host, username`) rather than
silently skipped. If the flow is paused, it is not polled.

**"references a credential that no longer exists".** The credential was deleted, or the flow was
imported from elsewhere. Pick one again on the Input node.

**"could not decrypt the credential".** The master key is not the one it was saved under — usually
a `secret.key` that was not copied along with the data, or a `CONCENTUS_SECRET_KEY` that changed.
Restore the old key, or re-enter the credential under Resources → Credentials.

**Authentication fails against Microsoft 365.** Basic auth for IMAP is retired there; see above.

**The same message starts a run every poll.** The processed-mail store is unavailable — the log
says so at startup, and the poller refuses to run in that state precisely to avoid this. It needs
PostgreSQL.

**A message is processed but never moves.** The destination folder does not exist; the log names
it. Filing failures never fail the run, because the run has already started and leaving mail in the
wrong folder is far less bad than processing it twice.

**Attachments aren't read.** PDF, DOCX and XLSX are parsed in pure Java. Images and scanned PDFs
need the native `tesseract-ocr` package, which the backend `Dockerfile` installs; without it the
extractor reports itself unavailable and images are skipped.
