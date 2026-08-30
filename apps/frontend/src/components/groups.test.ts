import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { groupName, knownGroups, refreshGroups, resetGroupsCache, useGroups } from './groups.ts'

const groupsStatus = vi.fn()
const listGroups = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    groupsStatus: () => groupsStatus(),
    listGroups: () => listGroups(),
  },
}))

const platform = { id: 'gr_1', organizationId: 'org_1', name: 'platform', description: null, createdAt: 1, createdBy: null, members: 1, resources: 0, manager: true }

/** One answer for the whole app: fetched once however many readers mount, and re-fetched on demand. */
describe('useGroups', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetGroupsCache()
    groupsStatus.mockResolvedValue({ allowed: true, refusal: null, groups: 1, mine: [{ id: 'gr_1', name: 'platform', manager: true }] })
    listGroups.mockResolvedValue({ groups: [platform], allowed: true, refusal: null })
  })

  it('asks for the status once however many readers mount, and every reader sees it', async () => {
    const a = renderHook(() => useGroups())
    const b = renderHook(() => useGroups())

    await waitFor(() => expect(a.result.current.loaded).toBe(true))
    expect(b.result.current.mine).toEqual([{ id: 'gr_1', name: 'platform', manager: true }])
    expect(b.result.current.allowed).toBe(true)
    expect(groupsStatus).toHaveBeenCalledTimes(1)
    expect(listGroups).not.toHaveBeenCalled()
  })

  it('fetches the full list only for a reader that asks, once, and names groups from it', async () => {
    const plain = renderHook(() => useGroups())
    const withAll = renderHook(() => useGroups({ all: true }))
    renderHook(() => useGroups({ all: true }))

    await waitFor(() => expect(withAll.result.current.all).toEqual([platform]))
    expect(listGroups).toHaveBeenCalledTimes(1)
    // The list is shared: the plain reader sees it too, and helpers read from it.
    expect(plain.result.current.all).toEqual([platform])
    expect(knownGroups(plain.result.current).map((g) => g.name)).toEqual(['platform'])
    expect(groupName(plain.result.current, 'gr_1')).toBe('platform')
    expect(groupName(plain.result.current, 'gr_nope')).toBeNull()
  })

  it('refreshGroups asks again and readers see the new answer without remounting', async () => {
    const reader = renderHook(() => useGroups({ all: true }))
    await waitFor(() => expect(reader.result.current.all).toEqual([platform]))

    listGroups.mockResolvedValue({ groups: [platform, { ...platform, id: 'gr_2', name: 'support' }], allowed: true, refusal: null })
    refreshGroups()

    await waitFor(() => expect(reader.result.current.all).toHaveLength(2))
    expect(groupsStatus).toHaveBeenCalledTimes(2)
    expect(listGroups).toHaveBeenCalledTimes(2)
  })

  it('a backend without groups leaves everything unloaded rather than throwing', async () => {
    groupsStatus.mockRejectedValue(new Error('404 Not Found'))
    const reader = renderHook(() => useGroups())

    await waitFor(() => expect(groupsStatus).toHaveBeenCalled())
    expect(reader.result.current.loaded).toBe(false)
    expect(reader.result.current.mine).toEqual([])
  })
})
