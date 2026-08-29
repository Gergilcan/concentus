import { describe, expect, it } from 'vitest'
import type { RunDiff } from '../api/types.ts'
import { hasChanges, parsePatch, patchFileName, sumStats } from './diff.ts'

const PATCH = [
  'diff --git a/README.md b/README.md',
  'index 5626abf..f6a5d8a 100644',
  '--- a/README.md',
  '+++ b/README.md',
  '@@ -1 +1,2 @@',
  ' one',
  '+two',
  'diff --git a/NEW.txt b/NEW.txt',
  'new file mode 100644',
  'index 0000000..a3c1a5e',
  '--- /dev/null',
  '+++ b/NEW.txt',
  '@@ -0,0 +1,2 @@',
  '+brand new',
  '+--dashes',
  '\\ No newline at end of file',
  'diff --git a/old.txt b/old.txt',
  'deleted file mode 100644',
  '--- a/old.txt',
  '+++ /dev/null',
  '@@ -1 +0,0 @@',
  '-gone',
  'diff --git a/a.ts b/b.ts',
  'similarity index 90%',
  'rename from a.ts',
  'rename to b.ts',
  'diff --git a/logo.png b/logo.png',
  'new file mode 100644',
  'GIT binary patch',
  'literal 12',
  'Tc$@lm+xEz0=|',
  '',
  'literal 0',
  'HcmV?d00001',
  '',
].join('\n') + '\n'

describe('parsePatch', () => {
  it('splits the patch into files with their status and counts', () => {
    const files = parsePatch(PATCH)

    expect(files.map((f) => f.path)).toEqual(['README.md', 'NEW.txt', 'old.txt', 'b.ts', 'logo.png'])
    expect(files.map((f) => f.status)).toEqual(['modified', 'added', 'deleted', 'renamed', 'added'])
    expect(files[3].from).toBe('a.ts')
    expect(files[4].binary).toBe(true)
    // The ---/+++ pair is a header, not a change; the content line starting `+--` is a change.
    expect(files.map((f) => [f.additions, f.deletions])).toEqual([[1, 0], [2, 0], [0, 1], [0, 0], [0, 0]])
  })

  it('keeps only what a reader needs of each file: hunks, changes, context, the no-newline mark', () => {
    const [readme, added] = parsePatch(PATCH)

    expect(readme.lines.map((l) => l.kind)).toEqual(['hunk', 'ctx', 'add'])
    expect(readme.lines[2].text).toBe('+two')
    expect(added.lines.map((l) => l.kind)).toEqual(['hunk', 'add', 'add', 'meta'])
    // A binary body never becomes lines: nothing there is readable.
    expect(parsePatch(PATCH)[4].lines).toEqual([])
  })

  it('reads a quoted path and tolerates CRLF', () => {
    const files = parsePatch('diff --git "a/päth.md" "b/päth.md"\r\n--- a/x\r\n+++ b/x\r\n@@ -1 +1 @@\r\n-a\r\n+b\r\n')
    expect(files).toHaveLength(1)
    expect(files[0].path).toBe('päth.md')
    expect(files[0].lines.map((l) => l.text)).toEqual(['@@ -1 +1 @@', '-a', '+b'])
  })

  it('is empty for no patch', () => {
    expect(parsePatch(null)).toEqual([])
    expect(parsePatch('')).toEqual([])
  })
})

const diff = (over: Partial<RunDiff>): RunDiff => ({
  nodeId: 'w1',
  label: 'Worker',
  folder: 'repo',
  patch: null,
  stats: { files: 0, additions: 0, deletions: 0 },
  note: null,
  takenAt: 0,
  ...over,
})

describe('run diff helpers', () => {
  it('sums the backend counts across checkouts', () => {
    expect(
      sumStats([
        diff({ stats: { files: 2, additions: 10, deletions: 3 } }),
        diff({ folder: 'other', stats: { files: 1, additions: 0, deletions: 7 } }),
      ]),
    ).toEqual({ files: 3, additions: 10, deletions: 10 })
  })

  it('has changes when there is a patch or a reason there is none', () => {
    expect(hasChanges(diff({}))).toBe(false)
    expect(hasChanges(diff({ patch: 'diff --git a/x b/x' }))).toBe(true)
    expect(hasChanges(diff({ note: 'The checkout directory no longer exists' }))).toBe(true)
  })

  it('names the download after who made it and which checkout, like the backend', () => {
    expect(patchFileName(diff({ label: 'Data worker', folder: 'concentus' }))).toBe('data-worker--concentus.patch')
    expect(patchFileName(diff({ label: '', nodeId: 'worker:x', folder: 'r' }))).toBe('worker-x--r.patch')
  })
})
