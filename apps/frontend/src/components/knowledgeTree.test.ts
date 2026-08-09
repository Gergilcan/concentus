import { describe, expect, it } from 'vitest'
import { DEFAULT_EXCLUDES, buildTree, pathSegments } from './KnowledgePanel.tsx'
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
