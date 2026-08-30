/**
 * The top-level views and the header's tabs for them. A module of its own rather than a constant
 * in AppHeader.tsx: the command palette reads the same list, and a component file that exports
 * anything but components loses fast refresh.
 */
export type View = 'flows' | 'studio' | 'marketplace' | 'resources' | 'usage'

export const NAV: Array<{ id: View; label: string }> = [
  { id: 'flows', label: 'Flows' },
  { id: 'studio', label: 'Studio' },
  { id: 'marketplace', label: 'Marketplace' },
  { id: 'resources', label: 'Resources' },
  { id: 'usage', label: 'Usage' },
]
