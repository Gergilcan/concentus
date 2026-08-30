# Groups inside an organization — design (30 August 2026)

## 1. What it is

An organization can be split into **groups** — the platform team, the support crew, a client's
squad — and two things follow the group rather than the organization:

- **Distribution.** A resource (a flow, an MCP server, an agent, a facade, a knowledge base, a
  skill, a variable, a database, a credential) can be *visible to one group* instead of the
  whole organization. Members of the group and the organization's administrators see it;
  nobody else does. Marketplace items can be published to a group.
- **Settings.** A group carries its own policy (default facade, permission ceiling, monthly
  budget, publish approval) and its own overrides of the settings that apply per run. A flow
  that belongs to a group runs under the group's policy and settings, layered over the
  organization's.

Groups are an **Enterprise** feature — the tenth in `Feature`: `GROUPS`, "Groups inside an
organization". On a Team license the Groups screen shows the refusal sentence and nothing can be
created or changed; what a group already scopes stays scoped (a downgrade never widens who sees
what).

## 2. Model

```
groups             id gr_<12 hex>, organization_id, name (unique per org), description, created_at, created_by
group_memberships  group_id, user_id, manager bool, created_at        pk (group_id, user_id)
group_settings     organization_id, group_id, key, value, updated_at   pk (group_id, key)
group_policies     group_id pk, organization_id, json (same shape as OrgPolicy: defaultFacadeProfileId,
                   requireFacade, maxPermissionMode, monthlyBudgetUsd, publishRequiresApproval — each nullable = inherit)
resources.group_id        text null     (V20: alter table)
credentials.group_id      text null
runs.group_id             text null     (copied from the flow at start; what settings/policy the run resolved against)
marketplace_items.group_id text null    (+ scope value 'group')
```

A **manager** of a group may add and remove its members and edit its settings and policy; an
organization ADMIN may do all of that for every group, and is the only one who creates, renames
or deletes a group. Deleting a group un-scopes what it held (resources return to the
organization) — it never deletes resources.

A person can belong to several groups. Memberships are resolved per request (`GroupContext`,
one query, cached for the request) and exposed on `GET /api/account/session` as
`groups: [{id, name, manager}]`.

## 3. Visibility

For every read of `resources` through `JsonStore` (`list`, `get`, and the organization-scoped
variants) the predicate becomes:

```
organization_id = :org and (group_id is null or group_id in (:callerGroups) or :callerIsAdmin)
```

The cross-organization escapes (`listAcrossOrganizations`, `getAcrossOrganizations`, `getIn`,
`saveIn`) are what schedulers, webhooks, public runs and run threads use and they stay
unfiltered — a cron on a group's flow must fire whether or not anybody is signed in. Credentials
follow the same rule in `CredentialStore`; the resolver a run uses is unfiltered.

Writes: saving a resource with a `group_id` requires the caller to be a member of that group or
an ADMIN, and the license to allow `GROUPS`. Changing a resource's group is one endpoint,
`POST /api/groups/assign {kind, resourceId, groupId|null}`, audited. Duplicating a flow keeps its
group; importing a file lands in the organization; a marketplace install into a group
(`install {groupId}`) creates the resource scoped to it.

Runs list and run detail: a run is visible when its flow would be (by `runs.group_id`).
Audit rows are organization-wide, as today.

## 4. Settings and policy per group

- `Settings.forGroup(organizationId, groupId, key)`: `group_settings` → organization → environment.
  `Settings.get(key)` on a request is unchanged (organization). Readers that run **per run**
  take the run's scope: `settings.forRun(run)` where the run carries `organizationId` and
  `groupId`. The catalog marks the keys a group may override (`SettingDef.groupScoped`): the
  ones read at run time rather than at bean construction — `local.permission-mode`,
  `workers.retries`, `workers.timeout-seconds`, `usage.weekly-allowance-usd` and whichever others
  the backend finds are read per run and not for pool sizing; the settings screen under a group
  shows only those.
- `OrgPolicyService.effective(flow)` becomes a layering: group policy (where the flow has a
  group and the group has a policy) over the organization policy, field by field — a null field
  in the group policy inherits. Enforcement points do not change (compiler, executor clamp,
  budget check, public run 404); they only ask with the flow.
- Group budget: `monthlyBudgetUsd` on a group policy is the group's own ceiling — spend summed
  over runs with that `group_id` — beside the organization's.

## 5. API

```
GET    /api/groups                                   the groups the caller may see (admin: all; else: mine) + counts
POST   /api/groups            {name, description}    admin, Feature.GROUPS
PUT    /api/groups/{id}       {name, description}    admin or manager
DELETE /api/groups/{id}                              admin — un-scopes its resources
GET    /api/groups/{id}/members
POST   /api/groups/{id}/members {userId, manager}    admin or manager
DELETE /api/groups/{id}/members/{userId}
GET/PUT /api/groups/{id}/settings                     {key: value} of the group-scoped keys; PUT replaces
GET/PUT /api/groups/{id}/policy                       the OrgPolicy shape, nullable fields
POST   /api/groups/assign     {kind, resourceId, groupId|null}
GET    /api/groups/status                             {allowed, refusal, groups: n, mine: [...]}
```

Refusals: 403 with the `Feature.GROUPS` refusal sentence on a Team license for every write;
404 for a group the caller may not see. Audit kinds: `group.created`, `group.updated`,
`group.deleted`, `group.member.added`, `group.member.removed`, `group.settings.changed`,
`group.policy.changed`, `resource.group.changed`.

Marketplace: `scope: 'group'` with `groupId`; visible to the group's members and org admins;
born published (like organization scope); `publish-from` accepts `groupId`; `install` accepts
`groupId`.

## 6. Frontend

- **Resources → Groups** (admin, and managers see their own): a roster of groups (name, member
  count, resource count, chip *manager* where the caller manages it); `+ New` (Enterprise gate:
  disabled with the refusal as tooltip, and the same sentence once under the header on Team);
  a group opens as a panel with three tabs — **Members** (the Members pattern: add from the
  organization's accounts, manager toggle, remove), **Settings** (the group-scoped keys, each
  with *inherited* shown muted until overridden), **Policy** (the Policies panel, with *inherit*
  per field).
- **Visible to** on resources: a small select (Organization / each group the caller is in; admins
  see every group) in the MCP servers, Agents, Facades, Knowledge, Skills, Variables, Databases
  and Credentials panels, and *Visible to…* in the flow card menu; the chip *group name* on
  cards and rows that are scoped. Disabled with the refusal when the license withholds groups.
- **Marketplace**: scope option *Group…* in the publish dialog; install dialog gets *Into…*
  (organization or a group) when the caller is in any group; group chip on cards.
- **Studio**: the flow header shows the group chip when the flow is scoped; the flow doctor
  reports "runs under the policy of group X" as an info line.
- All visible strings are i18n keys; explanations are tooltips.

## 7. Tests

Backend: store visibility (member / non-member / admin / unscoped), assign rules and license
gate, group deletion un-scopes, settings layering, policy layering with inherit, group budget,
marketplace group scope, migration on a V1-baselined database, audit rows. Frontend: Groups
panel per role and license, Visible-to select, chips, marketplace scope option. E2E: with the
enterprise test license (the `11-license.spec.ts` pattern): create a group, add a second
account, scope a flow to the group, the second account sees it, a third does not.

## 8. Not in this version

Nested groups; group-level roles beyond manager; per-group seat counts; group-scoped audit
views.
