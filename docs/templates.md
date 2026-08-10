# Sharing a flow as a template

A flow you got working is worth more than the afternoon it took you: someone else with the same
problem can import it and start from a canvas that already works. This page explains what the
**Copy as template** button produces, how to use a template someone shared with you, and how to
propose one for the gallery.

---

## Copy as template JSON (⎘ on the flow card)

Next to **Export JSON** on every flow card there is a second button, **⎘ Copy as template JSON**.
Both produce the flow's JSON; the difference is who it is for:

- **Export JSON (↓)** is for *you*: a complete backup of the flow, including every setting, meant
  to be re-imported on your own machines.
- **Copy as template (⎘)** is for *everyone else*: the same flow with everything that identifies
  you or your infrastructure removed, copied to the clipboard.

### What a template keeps

The flow's **shape** — the thing worth sharing:

- nodes, edges, and the flow's name, mode and tags
- system prompts and agent settings (model, effort, tools)
- SQL queries, MCP server URLs, OpenAPI spec URLs, allowed API operations
- trigger configuration that describes behaviour (mail folder, conditions, cron expression,
  permission mode, shadow)

### What a template strips

Anything that is yours, deleted outright (not blanked, so the JSON does not even reveal which
fields were set):

| Removed | Why |
|---|---|
| credential references (`credentialId`, `mailCredentialId`) | ids minted by your install; meaningless and mildly leaky elsewhere |
| webhook `secret` | the one field on a flow that is an actual secret |
| mail account (`mailUsername`, host, tenant/client ids) | your account and your servers |
| SQL `jdbcUrl` and `username` | names your database hosts |
| repository `url`, `group`, `baseUrl` | your repos; the importer wires their own |
| local paths (`contextFolders`, `claudeMdPath`) and local ids (`skillIds`, knowledge `baseId`) | only exist on your machine |
| flow `id`, favourite, paused state, failure webhook, budget | your install's bookkeeping |

**Review the JSON before sharing anyway.** The stripping covers structured fields — it cannot know
whether your *system prompt* mentions a customer by name or pastes an internal URL. The prompts are
yours, verbatim.

### Using a template

**Flows → Import**, pick the `.json` file (or paste the clipboard content into a file first). It
arrives named "… (imported)", with no credentials. Then walk the nodes and fill in what the
template deliberately lacks: pick your own credentials, set the mail host/username, point repo
nodes at your repositories. Nodes that need this are simply incomplete until you do — nothing runs
against someone else's account by accident, because the template physically cannot carry one.

---

## Proposing a template for the gallery

The gallery lives in this repository:

- [`docs/flows/`](flows/) — one document per flow, explaining what it does and how to set it up
  (see [mail-to-holded.md](flows/mail-to-holded.md) for the expected depth), plus the template
  JSON itself.
- The flows bundled into the app on first start (`FlowLibrarySeeder`) are drawn from the same
  pool: a gallery template that proves popular can graduate into the app.

To propose yours:

1. **Copy as template** on your flow card, and save the clipboard as
   `docs/flows/<short-slug>.flow.json`.
2. Write `docs/flows/<short-slug>.md` alongside it: what the flow does, the shape (nodes and
   edges, in one diagram or line), what the importer must configure, and anything you learned
   running it for real. Honest limitations included — "works only with IMAP", "Holded draft
   estimates only" is what makes a template trustworthy.
3. Open a pull request titled `Template: <flow name>` with both files.

A template PR is reviewed for three things:

- **No leaks.** Nothing in the JSON or the document identifies a person, an account, a server or
  a customer. The button strips the structured fields, but prompts and docs are on you.
- **It runs.** You have executed this flow against the real services it names, recently. Say so
  in the PR, with what you ran it on (provider, model, backend).
- **It teaches.** The document explains *why* the flow is shaped the way it is, not just which
  buttons to press.
