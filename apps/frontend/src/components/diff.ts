import type { PatchStats, RunDiff } from '../api/types.ts'

/**
 * A unified diff taken apart for display: one section per file, each line tagged with what it
 * is. Nothing here is git-aware beyond the header lines `git diff` writes — the patch is the
 * backend's `--binary` output, which is what makes the binary and rename cases worth handling.
 */

export type DiffLineKind = 'add' | 'del' | 'ctx' | 'hunk' | 'meta'

export interface DiffLine {
  kind: DiffLineKind
  text: string
}

export type DiffFileStatus = 'modified' | 'added' | 'deleted' | 'renamed'

export interface DiffFile {
  /** The path after the change (the `b/` side); for a deletion, the path that was deleted. */
  path: string
  /** For a rename, where it came from. */
  from?: string
  status: DiffFileStatus
  binary: boolean
  additions: number
  deletions: number
  lines: DiffLine[]
}

// git quotes a path with non-ASCII or special characters unless core.quotepath says otherwise;
// both forms arrive here.
const HEADER = /^diff --git a\/(.+?) b\/(.+)$/
const QUOTED_HEADER = /^diff --git "a\/(.+?)" "b\/(.+)"$/

/** The patch as file sections; an empty list for no patch at all. */
export function parsePatch(patch: string | null | undefined): DiffFile[] {
  if (!patch) return []
  const rows = patch.split('\n')
  // A patch ends with a newline, which split turns into one empty trailing row that would
  // otherwise land as a blank context line at the end of the last file.
  if (rows.length > 0 && rows[rows.length - 1] === '') rows.pop()

  const files: DiffFile[] = []
  let file: DiffFile | null = null
  let inHunk = false
  for (const raw of rows) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    const head = HEADER.exec(line) ?? QUOTED_HEADER.exec(line)
    if (head) {
      file = { path: head[2], status: 'modified', binary: false, additions: 0, deletions: 0, lines: [] }
      files.push(file)
      inHunk = false
      continue
    }
    if (!file) continue
    if (!inHunk) {
      if (line.startsWith('@@')) {
        inHunk = true
        file.lines.push({ kind: 'hunk', text: line })
      } else if (line.startsWith('new file mode')) {
        file.status = 'added'
      } else if (line.startsWith('deleted file mode')) {
        file.status = 'deleted'
      } else if (line.startsWith('rename from ')) {
        file.status = 'renamed'
        file.from = line.slice('rename from '.length)
      } else if (line.startsWith('rename to ')) {
        file.path = line.slice('rename to '.length)
      } else if (line.startsWith('GIT binary patch') || line.startsWith('Binary files ')) {
        file.binary = true
      }
      // Everything else before the first hunk — index, mode, the ---/+++ pair, a binary body —
      // says nothing a reader needs beyond what is already on the file's head line.
      continue
    }
    if (line.startsWith('@@')) file.lines.push({ kind: 'hunk', text: line })
    else if (line.startsWith('+')) {
      file.additions++
      file.lines.push({ kind: 'add', text: line })
    } else if (line.startsWith('-')) {
      file.deletions++
      file.lines.push({ kind: 'del', text: line })
    } else if (line.startsWith('\\')) file.lines.push({ kind: 'meta', text: line })
    else file.lines.push({ kind: 'ctx', text: line })
  }
  return files
}

/** The totals of several diffs, from the backend's own counts (present even when a patch was capped). */
export function sumStats(diffs: RunDiff[]): PatchStats {
  return diffs.reduce(
    (acc, d) => ({
      files: acc.files + (d.stats?.files ?? 0),
      additions: acc.additions + (d.stats?.additions ?? 0),
      deletions: acc.deletions + (d.stats?.deletions ?? 0),
    }),
    { files: 0, additions: 0, deletions: 0 },
  )
}

/**
 * Whether a checkout has anything to show: a patch, or a reason there is none. A registered
 * checkout that was read and found unchanged has neither — that is the run-level view's to say,
 * not something to open a tab for on the block.
 */
export function hasChanges(d: RunDiff): boolean {
  return !!d.patch || !!d.note
}

/** A file name for the download, the way the backend names it: who, then which checkout. */
export function patchFileName(d: RunDiff): string {
  const who = (d.label || d.nodeId).trim().toLowerCase().replace(/[^a-z0-9_-]+/g, '-')
  return `${who || 'agent'}--${d.folder}.patch`
}
