# Internal marketplace — design (30 August 2026)

## 1. What it is

A place inside Concentus where people publish the things a flow is built from — MCP servers,
library agents, facade profiles, skills, plugins, API endpoints, flow templates — and everyone
else installs them into their own organization with one click, so a flow can use them
directly. It is a new top-level view, **Marketplace**, beside Flows, Studio, Resources and Usage.

Two audiences, two scopes:

- **Organization** — an item published to one organization is visible to its members only. Any
  member with the MEMBER role or above can publish there; nothing needs approval.
- **Global** — an item published to the whole deployment is visible to every organization. A
  global submission waits for a **curator** to approve it; until then only its author and the
  curators see it, marked *pending*. Rejection carries a sentence the author reads.

The deployment's **curators** are the administrators of the organization named by the setting
`marketplace.curator-organization` (default: the oldest organization — the one the first
account created, i.e. the author's own). One setting, no new role: a deployment with a single
organization behaves exactly as "the admin approves".

## 2. Items

```
MarketplaceItem
  id            mkt_<12 hex>
  kind          mcp | agent | facade | skill | plugin | api | flow
  name, summary (one line), description (markdown, optional), tags[]
  version       integer, bumped by the author on re-publish
  scope         organization | global
  organizationId (the publishing org; a global item keeps it as "from")
  status        published | pending | rejected   (organization-scoped items are born published)
  rejection     sentence, when status = rejected
  author        { userId, email }   (the account that published it)
  payload       JSON — the definition of the thing (see §3)
  icon          optional emoji or short glyph
  installs      count, deployment-wide
  createdAt, updatedAt, publishedAt, approvedBy
  builtIn       true for the ones the app seeds (§5); they cannot be edited, only re-seeded
```

Stored in a new table `marketplace_items` (migration V19), not in `resources`: an item is
visible across organizations, which `JsonStore`'s per-organization scoping is built to forbid.
A second table `marketplace_installs (item_id, organization_id, resource_id, version,
installed_at, installed_by)` records what each organization installed and which resource it
became, so *Update available* and *Installed* can be shown honestly and an uninstall knows what
to remove.

## 3. Payloads and what "install" creates

| kind | payload | install = |
|---|---|---|
| `mcp` | `McpDef` minus `id`/`credentialId` (name, url or command/args, env keys, authHeader) plus `auth: oauth|token|none|stdio` | `POST /api/mcp-defs` in the installing org; a token/oauth server is created with the credential slot empty and the panel says what to fill |
| `agent` | `LibraryAgent` minus `id` (name, model, effort, maxTokens, systemPrompt, description) | `POST /api/agents` |
| `facade` | `FacadeProfile` minus `id` | `POST /api/facade-profiles` |
| `skill` | `SkillDef` minus `id` (name, description, files[]) | `POST /api/skills` (the existing skill store) |
| `plugin` | `{ marketplace, pluginId }` — a reference into the Claude Code plugin marketplaces the deployment already knows | `POST /api/plugins/install` |
| `api` | `{ name, baseUrl, specUrl?, spec?, description }` — what the API node's inspector asks for | nothing is created server-side: the API node's inspector gets **Use from Marketplace…**, which fills the node's fields from the payload, and the install is recorded so the count is honest |
| `flow` | a `FlowGraph` minus ids, with `libraryAgentId` links resolved to the marketplace item ids they came from | `POST /api/flows` in the installing org (the flow arrives paused and without secrets, like a duplicate) |

Each payload is validated on publish by the same code that validates the resource on save
(the controllers already refuse a bad `McpDef`, a nameless agent, …); the marketplace calls
those validators, it does not grow its own.

Credentials never travel: a payload names which credential slots exist (an env key, a header),
never their values. Publishing a resource that carries a `credentialId` strips it and says so.

## 4. API

```
GET    /api/marketplace/items?q=&kind=&scope=&tag=&status=&sort=   what the caller may see:
                                                                    published (all orgs) +
                                                                    own org's items + own
                                                                    pending/rejected + all
                                                                    pending for curators
GET    /api/marketplace/items/{id}                                 with payload, install state
POST   /api/marketplace/items                                       publish (body: item without id)
PUT    /api/marketplace/items/{id}                                  author or curator: edit,
                                                                    bump version, change scope
DELETE /api/marketplace/items/{id}                                  author or curator (built-in: refused)
POST   /api/marketplace/items/{id}/install                          → { resourceId, kind }
POST   /api/marketplace/items/{id}/uninstall                        removes the resource it created
POST   /api/marketplace/items/{id}/approve  |  /reject { reason }   curators only
POST   /api/marketplace/publish-from { kind, resourceId, scope }    "Publish" from an existing
                                                                    resource in Resources
GET    /api/marketplace/status                                      { curator: bool, pending: n,
                                                                      scope caps, tags }
```

Rules the controller enforces (tests for each):
- Organization scope: MEMBER+ may publish, edit their own, delete their own; ADMIN of that org
  may edit/delete any of its items.
- Global scope: anyone MEMBER+ may *submit*; the item is `pending` until a curator approves.
  Curators approve/reject/edit/delete any global item. An author may withdraw a pending one.
- Install: OPERATOR+ in the installing org (it creates a resource there — the same right the
  resource's own panel asks for). A global item installs into the caller's *current*
  organization.
- Visibility is enforced in the query, never only in the interface.
- Audit rows: `marketplace.published`, `.approved`, `.rejected`, `.installed`, `.uninstalled`
  (the `AuditService` vocabulary grows five kinds).
- Team vs Enterprise: the marketplace is on every tier, including free — it is how a single
  person keeps their own library. Global scope exists only where there is more than one
  organization to share with; on a one-organization deployment the scope selector is not shown
  and everything is, in effect, global.

## 5. Seeding — the items that already exist

On first start after this version, and again whenever a built-in changes, a seeder publishes
as **global, built-in, approved** (author `system:concentus`):

- the 28 entries of the frontend MCP catalog (`McpCatalog.tsx` — moved to a backend resource
  `library-mcp.json` so both the catalog and the marketplace read one list; the catalog panel
  then reads the marketplace),
- the 4 library agents (`library-agents/*.yaml`),
- the 8 library flows (`library-flows/*.json`) — the same files `FlowLibrarySeeder` copies
  into every organization today; that seeder keeps working for existing installs and new
  organizations still get their starters, but the marketplace is where they are *found*,
- the recipes (`recipes.ts`) as `flow` items with their question sheet kept in the payload
  (`recipe: { fields, questions }`), so installing one still asks its questions,
- the skill catalog repositories the app already lists (`/api/skills/catalog`) as `skill`
  items that resolve on install,
- the plugin marketplaces' plugins as `plugin` items.

Seeded items are re-read on every start and replaced when the bundled definition changed
(a `builtInHash` column); a deleted built-in stays deleted for that deployment, the way seeded
flows do today.

## 6. Frontend

A new view `marketplace` in `AppNav` (label **Marketplace**).

```
┌ Marketplace ───────────────────────────────────────────────────────────────┐
│ [search………………………………]  Kind ▾  Scope ▾  Sort ▾            [+ Publish]  (3 pending) │
│ ┌ tags: mcp · google · github · review · mail · … (chips, multi-select) ┐  │
│                                                                           │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐             │
│  │ ⚙ linear    │ │ ★ Tech Lead │ │ ⚖ Reviewer  │ │ 🔗 PR review│  …          │
│  │ MCP · global│ │ agent · org │ │ facade      │ │ flow · v3   │             │
│  │ one line    │ │ one line    │ │ one line    │ │ one line    │             │
│  │ 142 installs│ │ Installed ✓ │ │ Update ↑    │ │ Pending ⏳  │             │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘             │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Cards**, not rows: icon, name, kind chip, scope chip, one summary line, install count, and
  the state chip (Installed / Update available / Pending / Rejected). Hover explains; there is no
  paragraph on the grid. A click opens the **item dialog**: description (markdown), the payload
  rendered as the same inspector the resource has (an MCP shows url/command/env keys; an agent
  shows model, effort, prompt), version history, author, and the one action — Install /
  Update / Uninstall — plus Edit/Delete for the author and Approve/Reject for curators.
- **Search** is instant and local over what the list endpoint returned (name, summary, tags);
  the server does the visibility, the client does the typing.
- **Filters**: kind, scope, state, tags; **sort**: most installed, newest, name.
- **Publish** opens a form: kind → pick an existing resource of that kind from this
  organization (the common path — "publish my Linear MCP") or paste/upload a JSON file; name,
  summary, description, tags, icon; scope (organization / global, with the sentence "Global
  items are reviewed by <curator org> before everyone sees them"). Publishing from a resource is
  also a **Publish…** action in each Resources panel's row menu.
- **Curation**: curators see a *Pending* filter and a badge on the tab; the item dialog gets
  Approve / Reject (with a required sentence).
- Installed items link to the resource they created (open it in Resources) and the resource's
  panel shows a "from Marketplace · v3" note with *Update* when a newer version exists.
- Design: the Flows page's card grid and chips are the reference; kind colours reuse the
  canvas's node-kind palette so an MCP card is the MCP colour; empty state is one line; every
  explanation is a tooltip.

## 7. Not in this version

- Ratings and comments; screenshots; dependencies between items ("this flow needs these two
  MCPs" is expressed by the flow's own nodes, and install creates what is missing — but no
  separate dependency graph).
- Cross-deployment sharing (a public registry): global here means *this deployment*.
- Custom node kinds — a separate plan (see the HTML plan that accompanies this spec).

## 8. Tests

Backend: store (visibility per scope/status/org, install records, uninstall, seeding
idempotence with hash), controller (each rule in §4, audit rows, credential stripping,
curator setting), migration. Frontend: grid renders and filters, item dialog actions by
role, publish form from a resource, curator badge. E2E: publish an MCP org-scoped, install it
in the same org, see it under Resources → MCP servers, uninstall.
