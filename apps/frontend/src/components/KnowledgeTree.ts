import type { KnowledgeDoc } from '../api/types.ts'

/**
 * The folder tree behind the knowledge panel's document list: pure functions over a page of
 * documents, kept out of the component file so they can be tested on their own and so the panel
 * keeps fast refresh.
 */

/** A folder in the document tree. Files hold the full stored name, which is what rows display. */
export interface TreeNode {
  name: string
  path: string
  folders: Map<string, TreeNode>
  files: KnowledgeDoc[]
  /** Documents anywhere under this folder — filled by buildTree, so no render walks the subtree. */
  count: number
}

function emptyNode(name: string, path: string): TreeNode {
  return { name, path, folders: new Map(), files: [], count: 0 }
}

/**
 * Builds the nested folder tree for a page of documents.
 *
 * <p>Nested rather than grouped by full folder string: "apps/backend/docs" was one flat header,
 * which is a list of paths wearing a tree's clothes. Each segment is now its own level.
 */
export function buildTree(docs: KnowledgeDoc[]): TreeNode {
  const root = emptyNode('', '')
  for (const doc of docs) {
    let node = root
    for (const segment of doc.name.split('/').slice(0, -1)) {
      const path = node.path ? `${node.path}/${segment}` : segment
      let next = node.folders.get(segment)
      if (!next) {
        next = emptyNode(segment, path)
        node.folders.set(segment, next)
      }
      // Every ancestor counts this document; the render used to re-walk each visible subtree.
      next.count += 1
      node = next
    }
    node.files.push(doc)
  }
  return root
}

/**
 * Folder names excluded by default when importing a folder.
 *
 * Dropping a repository on a knowledge base should index the repository, not its dependencies:
 * node_modules alone can be tens of thousands of files whose contents nobody wants an agent
 * retrieving. Matched by path segment, so a nested apps/backend/target is caught as readily as a
 * top-level one, and every entry is de-selectable — this is a default, not a policy.
 */
export const DEFAULT_EXCLUDES = [
  'node_modules', 'target', 'dist', 'build', 'out', 'bin', 'obj', 'vendor', 'coverage',
  '.git', '.next', '.nuxt', '.gradle', '.idea', '.vs', '.cache', '.venv', 'venv', '__pycache__',
]

/** One line of the rendered tree: a folder header or a document, at a nesting depth. */
export type TreeRow =
  | { kind: 'folder'; node: TreeNode; depth: number }
  | { kind: 'file'; doc: KnowledgeDoc; depth: number }

/**
 * Flattens the tree into the rows actually on screen, honouring what is expanded.
 *
 * <p>This is what pagination counts. Paginating files alone made a page of twenty files render as
 * anything from twenty to sixty lines depending on how many folder headers came with them, and a
 * collapsed folder still consumed its files' worth of the page while showing one line. Twenty
 * <em>rows</em> is the thing the user can actually see and count.
 */
export function flattenTree(node: TreeNode, expanded: Set<string>, depth = 0): TreeRow[] {
  const rows: TreeRow[] = []
  for (const child of node.folders.values()) {
    rows.push({ kind: 'folder', node: child, depth })
    if (expanded.has(child.path)) rows.push(...flattenTree(child, expanded, depth + 1))
  }
  for (const doc of node.files) rows.push({ kind: 'file', doc, depth })
  return rows
}

/** Documents anywhere under a folder — what deleting it would actually take. */
export function countUnder(node: TreeNode): number {
  return node.count
}

/** Every folder name along a file's path, minus the chosen root and the file itself. */
export function pathSegments(relativePath: string): string[] {
  const parts = relativePath.split('/')
  return parts.slice(1, -1)
}
