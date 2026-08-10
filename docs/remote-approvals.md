# Remote approvals: Slack answers, Teams listens

A flow in **approval mode** stops after planning and waits for a human. Until now the human had to
be sitting at the app. This page explains how to get that question — and for Slack, the answer —
into your chat tools, and why the two integrations are deliberately different.

## The design constraint

Concentus runs on your machine, with no public URL. Every "interactive message" mechanism the chat
platforms offer (Slack interactivity, Teams bots) works by **calling your server back**, which
would mean exposing one. So:

- **Slack** works fully — request *and* answer — because the answer is read by **polling the
  reactions** on the message Concentus posted. Outbound HTTPS only, a poll every few seconds
  against one message, well inside Slack's rate limits.
- **Teams** is **notification only**. Its incoming webhooks are one-way, and anything that could
  carry a button press back requires a Bot Framework endpoint reachable from the internet. Rather
  than buttons that could never work, the card honestly says where to answer.

## What it looks like

When a run stops to ask:

1. The plan (the agent's last message, truncated if huge) is posted to the Slack channel and/or
   the Teams webhook the flow configured.
2. In Slack: **react ✅ to approve, ❌ to reject** (also accepted: 👍 / ✔️ to approve, 👎 / ⛔ to
   reject). Concentus notices within a few seconds and the run resumes — or ends — exactly as if
   the button in the app had been pressed. The message is then rewritten with the outcome, so the
   channel shows what happened instead of stale instructions.
3. If both reactions are present, **reject wins**: two people disagreeing is not an approval.
4. Deciding from the app also rewrites the Slack message. One decision, wherever it is made.

## Setting up Slack

1. Create an app at `api.slack.com/apps` → *From scratch*, in your workspace.
2. Under **OAuth & Permissions**, add the bot scopes `chat:write` and `reactions:read`, then
   *Install to Workspace* and copy the **Bot User OAuth Token** (`xoxb-…`).
3. In Concentus, store that token under **Resources → Credentials**.
4. Invite the bot to the channel that should receive approval requests: `/invite @yourbot`.
5. In the flow's **Settings → Remote approval**, pick the credential and set the channel id
   (channel details → copy id, `C…`; a public channel's name also works).

If the request never appears, the run's own console says why — `not_in_channel` means step 4 was
skipped, `channel_not_found` means the id is wrong.

## Setting up Teams

1. In the Teams channel: **Workflows → Post to a channel when a webhook request is received**
   (the successor of the retired Office 365 connectors), and copy the URL it gives you.
2. Paste it into the flow's **Settings → Remote approval → Teams webhook**.

The card carries the plan and names where to answer (the app, or Slack if configured). That is
all it can do, by construction — see above.

## Honest limitations

- **A restart drops the watch.** The Slack message stays, but reactions added after a Concentus
  restart are not seen; the run itself still waits in the app (that state survives restarts) and
  can be approved there. Re-posting on restart was deliberately avoided — duplicated questions
  are worse than a dropped watch.
- **A watch expires after 48 hours** without a reaction. The run keeps waiting in the app.
- **Anyone in the channel can react.** The channel *is* the access control — put the request in a
  channel whose members you would let press the button.
- Reactions are polled every 5 seconds; a decision is not instant, just fast.
