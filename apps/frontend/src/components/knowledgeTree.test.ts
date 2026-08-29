import { describe, expect, it } from 'vitest'
import { DEFAULT_EXCLUDES, buildTree, countUnder, flattenTree, pathSegments } from './KnowledgeTree.ts'
import type { KnowledgeDoc } from '../api/types.ts'

const doc = (name: string): KnowledgeDoc => ({ name, chunks: 1, embedded: true, createdAt: 0 })

describe('buildTree', () => {
  it('nests each path segment as its own level', () => {
    const tree = buildTree([doc('apps/backend/docs/intro.pdf')])

    const apps = tree.folders.get('apps')!
    const backend = apps.folders.get('backend')!
    const docs = backend.folders.get('docs')!
    expect(apps.path).toBe('apps')
    expect(backend.path).toBe('apps/backend')
    // The path, not just the name: collapsing keys on it, so two folders both called "docs"
    // under different parents must not collapse together.
    expect(docs.path).toBe('apps/backend/docs')
    expect(docs.files.map((f) => f.name)).toEqual(['apps/backend/docs/intro.pdf'])
  })

  it('keeps root-level files out of any folder', () => {
    const tree = buildTree([doc('readme.md'), doc('manuals/a.pdf')])

    expect(tree.files.map((f) => f.name)).toEqual(['readme.md'])
    expect([...tree.folders.keys()]).toEqual(['manuals'])
  })

  it('merges files that share a folder instead of repeating it', () => {
    const tree = buildTree([doc('m/a.pdf'), doc('m/b.pdf'), doc('m/deep/c.pdf')])

    const m = tree.folders.get('m')!
    expect(m.files).toHaveLength(2)
    expect(m.folders.get('deep')!.files).toHaveLength(1)
  })

  it('distinguishes same-named folders under different parents', () => {
    const tree = buildTree([doc('a/docs/x.md'), doc('b/docs/y.md')])

    expect(tree.folders.get('a')!.folders.get('docs')!.path).toBe('a/docs')
    expect(tree.folders.get('b')!.folders.get('docs')!.path).toBe('b/docs')
  })
})

describe('pathSegments', () => {
  it('drops the chosen root folder and the filename', () => {
    // webkitRelativePath always starts with the picked folder, which is never worth offering as
    // an exclusion: ticking it off would exclude everything.
    expect(pathSegments('myrepo/apps/backend/pom.xml')).toEqual(['apps', 'backend'])
  })

  it('returns nothing for a file sitting directly in the picked folder', () => {
    expect(pathSegments('myrepo/readme.md')).toEqual([])
  })

  it('matches a junk folder at any depth', () => {
    const excluded = new Set(DEFAULT_EXCLUDES)
    const nested = pathSegments('myrepo/apps/backend/target/classes/x.txt')

    expect(nested.some((s) => excluded.has(s))).toBe(true)
    expect(pathSegments('myrepo/src/main/x.txt').some((s) => excluded.has(s))).toBe(false)
  })
})

describe('flattenTree', () => {
  const docs = [doc('a/1.txt'), doc('a/deep/2.txt'), doc('b/3.txt'), doc('root.txt')]

  it('shows only top-level folders and root files when nothing is expanded', () => {
    const rows = flattenTree(buildTree(docs), new Set())

    // Everything starts folded, so a base with thousands of files opens as a handful of rows.
    expect(rows.map((r) => (r.kind === 'folder' ? `[${r.node.path}]` : r.doc.name))).toEqual([
      '[a]',
      '[b]',
      'root.txt',
    ])
  })

  it('reveals a folder’s contents when it is expanded, and only that folder’s', () => {
    const rows = flattenTree(buildTree(docs), new Set(['a']))

    expect(rows.map((r) => (r.kind === 'folder' ? `[${r.node.path}]` : r.doc.name))).toEqual([
      '[a]',
      '[a/deep]', // present but still folded — expanding a parent must not expand its children
      'a/1.txt',
      '[b]',
      'root.txt',
    ])
  })

  it('indents each level so depth reflects nesting', () => {
    const rows = flattenTree(buildTree(docs), new Set(['a', 'a/deep']))
    const deepFile = rows.find((r) => r.kind === 'file' && r.doc.name === 'a/deep/2.txt')

    expect(deepFile?.depth).toBe(2)
  })
})

describe('countUnder', () => {
  it('counts documents at every depth, which is what deleting a folder takes', () => {
    const tree = buildTree([doc('a/1.txt'), doc('a/deep/2.txt'), doc('a/deep/deeper/3.txt'), doc('b/4.txt')])

    // The count shown in the confirm must be the true total, not the rows currently visible.
    expect(countUnder(tree.folders.get('a')!)).toBe(3)
    expect(countUnder(tree.folders.get('b')!)).toBe(1)
  })
})
