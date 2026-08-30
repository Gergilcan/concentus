import { useEffect, useSyncExternalStore } from 'react'
import { api } from '../api/client.ts'
import type { Group, SessionGroup } from '../api/types.ts'

/**
 * What the interface knows about groups, shared by everything that asks.
 *
 * A "Visible to" select sits on eight resource forms, a chip on every scoped card and row, and the
 * Marketplace asks twice more — each of them fetching `/groups/status` on mount would be a dozen
 * identical requests for one answer that does not change while the page is open. So the answer is
 * held here, at module level, fetched once and pushed to every mounted reader through
 * useSyncExternalStore. The Groups panel calls {@link refreshGroups} after it creates, renames or
 * deletes one, which is the only way the answer changes short of a reload.
 */
export interface GroupsView {
  /** The status answered. Until it has, nothing group-related is offered — a select with no options is noise. */
  loaded: boolean
  /** The license lets groups be made and resources be scoped here. */
  allowed: boolean
  /** The gate's own sentence when `allowed` is false. */
  refusal: string | null
  /** The groups the caller is in, and which of them they manage. */
  mine: SessionGroup[]
  /** How many groups the organization has. */
  count: number
  /**
   * Every group the caller may see — all of them for an administrator, `mine` with counts for
   * anybody else. Fetched only by readers that ask for it (`useGroups({ all: true })`), because
   * it is a second request and most screens only need `mine`.
   */
  all: Group[] | null
}

const EMPTY: GroupsView = { loaded: false, allowed: false, refusal: null, mine: [], count: 0, all: null }

let view: GroupsView = EMPTY
let statusPromise: Promise<void> | null = null
let allPromise: Promise<void> | null = null
const listeners = new Set<() => void>()

function update(patch: Partial<GroupsView>) {
  view = { ...view, ...patch }
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

const snapshot = () => view

/**
 * One request, whatever asks. Deferred to a microtask so a test that mocks the client without this
 * route sees a rejection rather than a throw during render. A failure leaves `loaded` false: a
 * backend without groups simply shows none of this, which is the right face for it.
 */
function loadStatus(): Promise<void> {
  if (!statusPromise) {
    statusPromise = Promise.resolve()
      .then(() => api.groupsStatus())
      .then((s) => update({ loaded: true, allowed: s.allowed, refusal: s.refusal, mine: s.mine, count: s.groups }))
      .catch(() => {})
  }
  return statusPromise
}

function loadAll(): Promise<void> {
  if (!allPromise) {
    allPromise = Promise.resolve()
      .then(() => api.listGroups())
      .then((l) => update({ all: l.groups, allowed: l.allowed, refusal: l.refusal }))
      .catch(() => {})
  }
  return allPromise
}

/**
 * The shared answer. `all: true` also asks for the full list, which the selects and chips need
 * to name a group the caller administers without being in.
 */
export function useGroups(options: { all?: boolean } = {}): GroupsView {
  const current = useSyncExternalStore(subscribe, snapshot, snapshot)
  const wantAll = options.all === true
  useEffect(() => {
    void loadStatus()
    if (wantAll) void loadAll()
  }, [wantAll])
  return current
}

/** The groups a select may offer, by name: the full list when it was fetched, the caller's own otherwise. */
export function knownGroups(groups: GroupsView): Array<{ id: string; name: string }> {
  return groups.all ?? groups.mine
}

/** A group's name from what is known, or null for one the caller cannot see. */
export function groupName(groups: GroupsView, id: string | null | undefined): string | null {
  if (!id) return null
  return knownGroups(groups).find((g) => g.id === id)?.name ?? null
}

/**
 * Asks again. Called after a group is created, renamed or deleted, so a select opened a moment
 * later lists what the roster shows. What was already known stays on screen while the answer is
 * on its way — a chip that flickers to "Group" and back would be worse than one a second stale.
 */
export function refreshGroups() {
  const hadAll = allPromise !== null
  statusPromise = null
  allPromise = null
  void loadStatus()
  if (hadAll) void loadAll()
}

/** Forgets everything, for tests: each one starts from a client that has not been asked. */
export function resetGroupsCache() {
  statusPromise = null
  allPromise = null
  update(EMPTY)
}
