# Remote approvals and questions: Slack answers, Teams listens

A run stops for a human in two ways. A flow in **approval mode** stops after planning and waits for
a yes or no. Any run can also stop having **asked you something** — the agent's final answer was a
question, and the run waits for a reply. Until now the human had to be sitting at the app. This page
explains how to get both — and, for Slack, the answer — into your chat tools, and why the two
integrations are deliberately different.

## The design constraint

Concentus runs on your machine, with no public URL. Every "interactive message" mechanism the chat
platforms offer (Slack interactivity, Teams bots) works by **calling your server back**, which
would mean exposing one. So:

- **Slack** works fully — request *and* answer — because the answer is read by **polling**: the
  reactions on an approval message, the replies in a question's thread. Outbound HTTPS only, a poll
  every few seconds against one message, well inside Slack's rate limits.
- **Teams** is **notification only**. Its incoming webhooks are one-way, and anything that could
  carry a button press back requires a Bot Framework endpoint reachable from the internet. Rather
  than buttons that could never work, the card honestly says where to answer.

## Approvals: what it looks like

When a run stops to ask for approval:

1. The plan (the agent's last message, truncated if huge) is posted to the Slack channel and/or
   the Teams webhook the flow configured.
2. In Slack: **react ✅ to approve, ❌ to reject** (also accepted: 👍 / ✔️ to approve, 👎 / ⛔ to
   reject). Concentus notices within a few seconds and the run resumes — or ends — exactly as if
   the button in the app had been pressed. The message is then rewritten with the outcome, so the
   channel shows what happened instead of stale instructions.
3. If both reactions are present, **reject wins**: two people disagreeing is not an approval.
4. Deciding from the app also rewrites the Slack message. One decision, wherever it is made.

## Questions: answering from your phone

When a run ends a turn by asking you something (status `AWAITING_ANSWER`):

1. The question is posted to the same Slack channel, as its own message.
2. **Reply in that message's thread.** The first human reply becomes the run's next command —
   verbatim, through the same door the app's own command box uses. Concentus posts a ✔ into the
   thread to confirm it was taken.
3. The run carries on, and if it asks something else, that is a new message with a new thread.
4. Answering in the app instead simply ends the watch; the question stays in the channel, because
   rewriting it would erase the question itself.
5. A reply that arrives after the run has moved on is answered in the thread saying so, rather
   than silently doing nothing.

Only the **first** reply is taken. A second reply to a question already answered would be a command
nobody asked for.

## Setting up Slack

1. Create an app at `api.slack.com/apps` → *From scratch*, in your workspace.
2. Under **OAuth & Permissions**, add the bot scopes `chat:write`, `reactions:read` and
   `channels:history` (the last one is what lets Concentus read the replies in a question's
   thread; use `groups:history` instead for a private channel). Then *Install to Workspace* and
   copy the **Bot User OAuth Token** (`xoxb-…`).

   > Slack may also offer you an **App-Level Token** (`xapp-…`). You do not need it: that token
   > exists for Socket Mode, which this design deliberately avoids — Concentus polls instead of
   > holding a WebSocket. If it starts with `xoxb` it is the right one; if it starts with `xapp`,
   > ignore it. Socket Mode and Event Subscriptions can stay disabled.
3. In Concentus, store that token under **Resources → Credentials**.
4. Invite the bot to the channel that should receive requests: `/invite @yourbot`.
5. In the flow's **Settings → Remote approvals and questions**, pick the credential and set the
   channel id (channel details → copy id, `C…`; a public channel's name also works).

If nothing ever appears, the run's own console says why — `not_in_channel` means step 4 was
skipped, `channel_not_found` means the id is wrong, `missing_scope` means step 2 is incomplete.

## Setting up Teams

1. In the Teams channel: **Workflows → Post to a channel when a webhook request is received**
   (the successor of the retired Office 365 connectors), and copy the URL it gives you.
2. Paste it into the flow's **Settings → Remote approvals and questions → Teams webhook**.

The card carries the plan or the question and names where to answer (the app, or Slack if
configured). That is all it can do, by construction — see above.

## Honest limitations

- **A restart drops the watch.** The Slack message stays, but reactions and replies added after a
  Concentus restart are not seen; the run itself still waits in the app (that state survives
  restarts) and can be answered there. Re-posting on restart was deliberately avoided — duplicated
  questions are worse than a dropped watch.
- **A watch expires after 48 hours** without an answer. The run keeps waiting in the app.
- **Anyone in the channel can react or reply.** The channel *is* the access control — put the
  requests in a channel whose members you would let press the button.
- Polling is every 5 seconds; an answer is not instant, just fast.
- **Whether a run "asked something" is a heuristic**: the CLI stream carries no structured question
  signal, so a final answer whose last line ends in `?` is what makes the run wait. An agent that
  asks in the middle of a paragraph finishes instead — and one that ends with a rhetorical question
  waits when it did not need to.
