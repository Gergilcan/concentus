# Run flows on models other than Claude

Adds an `api` execution backend so a flow can run on OpenAI, Gemini, Vertex AI, DeepSeek and
anything else speaking the OpenAI shape — alongside the two existing Claude backends, not
replacing them.

**Read the "What this does not do" section before reviewing the code.** The headline ask was
"exactly the same functionalities", and that part is not achievable; the shape of this PR follows
from why.

---

## Why a third backend

The two existing backends don't generalise, because neither is really "calling a model":

- **local** drives the `claude` CLI — sub-agents come from `.claude/agents/*.md`, tools from Claude
  Code's sandbox, MCP from `claude mcp add`.
- **cloud** uses Anthropic Managed Agents — the agent loop and the container are Anthropic's.

Both delegate *orchestration itself* to an Anthropic product. No other vendor offers an equivalent,
so this backend owns the loop: **delegation is expressed as an ordinary tool call**, which every
provider supports.

## What this does

| | local (Claude) | cloud (Claude) | **api (others)** |
|---|:---:|:---:|:---:|
| Multi-agent + delegation chains | ✅ | ✅ | ✅ |
| Per-agent input / output / logs / status | ✅ | ✅ | ✅ |
| Tokens + cost per block | ✅ | ✅ | ✅ |
| SQL / RAG context | ✅ | ✅ | ✅ |
| Webhooks, cron, versions, retries | ✅ | ✅ | ✅ |
| **File editing / bash** | ✅ | ✅ | ❌ |
| **MCP servers** | ✅ | ✅ | ❌ |
| **Context folders** | ✅ | ❌ | ❌ |

Each agent is offered a `delegate_to_<name>` tool for **exactly** the agents wired behind it, so
the delegation chains you drew work here as they do locally — a reviewer behind an engineer
reviews that engineer's work, not the flow's work in general.

**No new switch.** The coordinator's model picks the engine: put `gpt-5` on it and the flow runs on
OpenAI; put `claude-opus-4-8` and it runs on your subscription.

## What this does not do — and why

File editing, bash and MCP don't work on non-Claude models here. **These are "not built", not
"impossible"** — every provider supports function calling, so each is reachable as a tool. The
reasons differ, and only one is a real blocker:

- **MCP** — an open protocol, not an Anthropic feature. It looks Claude-only here purely because
  this implementation registers servers via `claude mcp add`. A native MCP client (JSON-RPC over
  stdio/HTTP) would serve any provider. Scoped out for time, not portability.
- **File editing** — needs `read`/`write`/`edit` tools confined to an allowlist. The containment
  already exists (`ContextFolderResolver`, with `toRealPath()` so `..` and symlinks can't escape),
  so this is a small change.
- **Bash** — held back deliberately. Flows are triggerable by **public webhooks**, so
  model-generated shell commands on the host is a remote-code-execution path. Claude Code carries
  its own permission model and a trust boundary you accepted when installing it; neither transfers
  when this app spawns the process. Same class of decision as the context-folder allowlist, with a
  much larger blast radius — so it wants your call (container per run, command allowlist, approval
  gate), not a default I pick unattended.

**Concretely: your `Test` flow would not work on GPT or Gemini today.** It edits
`RsqlParser.java`. Flows that reason, delegate and read SQL context work fine.

Say which of the three you want and I'll build it — file tools and MCP are straightforward; bash
I'd want to design with you first.

## Provider coverage

One OpenAI-compatible adapter covers a lot: **OpenAI, DeepSeek, Groq, Mistral, xAI, OpenRouter,
Together**, and local **Ollama / vLLM**. Gemini and Vertex share bodies and differ only in endpoint
and credential, so they're one adapter with an endpoint/auth strategy.

Adding a vendor is config, not code:

```bash
LLM_OPENAI_COMPATIBLE=groq|https://api.groq.com/openai/v1|gsk-...
LLM_MODEL_PREFIXES=llama-:groq
```

A provider with no credential is **not registered at all**, so naming its model fails at launch
with a message naming the missing key — rather than as an HTTP error mid-run.
`GET /api/llm/providers` lists what's configured.

## ⚠️ One decision I want your call on

**Google has split its API.** `generateContent` — which this implements — is now documented as
*legacy*, and Google states new models and agentic capabilities are launching **only** on the newer
Interactions API.

I implemented `generateContent` because its shape is verifiable and works today; I had no verified
wire format for Interactions. The provider seam keeps switching contained, but it's worth deciding
deliberately rather than discovering later. Happy to research Interactions in the same depth.

## Testing

**33 new tests; 291 backend + 174 frontend passing.**

Both HTTP adapters are tested against a **stub HTTP server** — the request shape is the entire
contract with a vendor, and a wrong field name would otherwise only fail against a live endpoint.
Pinned because they fail silently otherwise:

- Gemini uses `user`/`model` roles, not `assistant`
- a Gemini tool *result* is a user turn with a `functionResponse` part
- Gemini args arrive as an object, OpenAI's as a string
- Gemini has no tool-call finish reason — you scan the parts
- OpenAI's `prompt_tokens` **includes** cached tokens (subtract, don't add)
- Gemini's `thoughtsTokenCount` bills as output but sits **outside** `candidatesTokenCount`

Delegation-loop tests cover roster scoping, per-agent attribution, a failed delegate not killing
the run, unknown tool names, and a runaway coordinator being capped.

## Not verified

- **No live API call has been made.** No keys were available, so every adapter is tested against a
  stub. The wire formats come from official docs (Gemini verified; **Vertex partially** — the auth
  header and exact `usageMetadata` field names could not be confirmed from live docs and follow
  convention). First real call per provider is the actual test.
- The UI was not driven end-to-end; backend + frontend tests, typecheck and build pass.

## Review path

1. `ApiAgentExecutor` — the delegation loop, the substantive new logic
2. `ProviderRegistry` — model→provider routing and the "unconfigured means absent" rule
3. `OpenAiCompatibleProvider` / `GeminiProvider` — wire formats
4. README "Model providers" — the capability table

## Suggested first run

```bash
export OPENAI_API_KEY=sk-...
```

Then duplicate `Test`, set the coordinator and sub-agents to `gpt-5`, remove the MCP nodes, and ask
it something that needs delegation but not file edits.
