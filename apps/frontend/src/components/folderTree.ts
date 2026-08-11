import type { BackendFlow } from '../api/types.ts'

/**
 * The dashboard's folder model: a flow's `folder` is a `/`-separated path ("Clients/ACME"), and
 * folders exist exactly as long as some flow lives under them. There is no folder table to sync,
 * nothing to migrate, and an empty folder cannot linger — it simply stops being derivable.
 * The price is that a folder cannot be created empty; it is born by moving the first flow in.
 */

export function folderOf(flow: BackendFlow): string {
  return normalizePath(flow.folder ?? '')
}

/** Collapses stray slashes and whitespace so "A / B//" and "A/B" are the same folder. */
export function normalizePath(path: string): string {
  return path
    .split('/')
    .map((s) => s.trim())
    .filter(Boolean)
    .join('/')
}

/** The direct subfolders visible at `path`, each with its RECURSIVE flow count. */
export function childFolders(flows: BackendFlow[], path: string): { name: string; count: number }[] {
  const prefix = path === '' ? '' : path + '/'
  const counts = new Map<string, number>()
  for (const flow of flows) {
    const folder = folderOf(flow)
    if (folder === '' || (prefix !== '' && !folder.startsWith(prefix)) || folder === path) continue
    const rest = folder.slice(prefix.length)
    const name = rest.split('/')[0]
    if (!name) continue
    counts.set(name, (counts.get(name) ?? 0) + 1)
  }
  return [...counts.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => a.name.localeCompare(b.name))
}

/** The flows that live directly at `path` (not the ones inside its subfolders). */
export function flowsAt(flows: BackendFlow[], path: string): BackendFlow[] {
  return flows.filter((f) => folderOf(f) === path)
}

/** "A/B/C" → [["All flows",""], ["A","A"], ["B","A/B"], ["C","A/B/C"]] — the breadcrumb's data. */
export function breadcrumbOf(path: string): { label: string; path: string }[] {
  const crumbs = [{ label: 'All flows', path: '' }]
  if (path === '') return crumbs
  const parts = path.split('/')
  for (let i = 0; i < parts.length; i++) {
    crumbs.push({ label: parts[i], path: parts.slice(0, i + 1).join('/') })
  }
  return crumbs
}

/**
 * Moving folder `from` under `toParent`: which flows change, and to what.
 *
 * Refused (empty result) when the target IS the source or lives inside it — a folder swallowing
 * itself would orphan every flow under a path nothing can reach.
 */
export function moveFolder(
  flows: BackendFlow[],
  from: string,
  toParent: string,
): { flow: BackendFlow; folder: string }[] {
  const src = normalizePath(from)
  const dst = normalizePath(toParent)
  if (src === '' || dst === src || dst.startsWith(src + '/')) return []
  const name = src.split('/').pop() as string
  const moved = dst === '' ? name : dst + '/' + name
  if (moved === src) return []
  const changes: { flow: BackendFlow; folder: string }[] = []
  for (const flow of flows) {
    const folder = folderOf(flow)
    if (folder === src) changes.push({ flow, folder: moved })
    else if (folder.startsWith(src + '/')) changes.push({ flow, folder: moved + folder.slice(src.length) })
  }
  return changes
}
