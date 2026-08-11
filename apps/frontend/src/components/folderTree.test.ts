import { describe, expect, it } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { breadcrumbOf, childFolders, flowsAt, moveFolder, normalizePath } from './folderTree.ts'

const flow = (name: string, folder?: string): BackendFlow =>
  ({ id: name, name, folder }) as BackendFlow

const SET = [
  flow('root-1'),
  flow('root-2', ''),
  flow('a-1', 'A'),
  flow('a-2', 'A'),
  flow('ab-1', 'A/B'),
  flow('c-1', 'C'),
]

describe('normalizePath', () => {
  it('collapses stray slashes and whitespace', () => {
    expect(normalizePath(' A / B ')).toBe('A/B')
    expect(normalizePath('//A//B//')).toBe('A/B')
    expect(normalizePath('')).toBe('')
  })
})

describe('childFolders', () => {
  it('lists direct children at the root with recursive counts', () => {
    expect(childFolders(SET, '')).toEqual([
      { name: 'A', count: 3 }, // a-1, a-2 and ab-1 all live under A
      { name: 'C', count: 1 },
    ])
  })

  it('lists direct children inside a folder', () => {
    expect(childFolders(SET, 'A')).toEqual([{ name: 'B', count: 1 }])
    expect(childFolders(SET, 'A/B')).toEqual([])
  })
})

describe('flowsAt', () => {
  it('returns only the flows directly at the path', () => {
    expect(flowsAt(SET, '').map((f) => f.name)).toEqual(['root-1', 'root-2'])
    expect(flowsAt(SET, 'A').map((f) => f.name)).toEqual(['a-1', 'a-2'])
    expect(flowsAt(SET, 'A/B').map((f) => f.name)).toEqual(['ab-1'])
  })
})

describe('breadcrumbOf', () => {
  it('always starts at the root', () => {
    expect(breadcrumbOf('')).toEqual([{ label: 'All flows', path: '' }])
    expect(breadcrumbOf('A/B')).toEqual([
      { label: 'All flows', path: '' },
      { label: 'A', path: 'A' },
      { label: 'B', path: 'A/B' },
    ])
  })
})

describe('moveFolder', () => {
  it('moves a folder and everything under it', () => {
    expect(moveFolder(SET, 'A', 'C')).toEqual([
      { flow: SET[2], folder: 'C/A' },
      { flow: SET[3], folder: 'C/A' },
      { flow: SET[4], folder: 'C/A/B' },
    ])
  })

  it('moves a nested folder up to the root', () => {
    expect(moveFolder(SET, 'A/B', '')).toEqual([{ flow: SET[4], folder: 'B' }])
  })

  it('refuses to swallow itself', () => {
    expect(moveFolder(SET, 'A', 'A')).toEqual([])
    expect(moveFolder(SET, 'A', 'A/B')).toEqual([])
  })

  it('refuses a no-op move to its current parent', () => {
    expect(moveFolder(SET, 'A/B', 'A')).toEqual([])
  })
})
