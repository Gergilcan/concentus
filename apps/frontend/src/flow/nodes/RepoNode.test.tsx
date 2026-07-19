import { ReactFlowProvider } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RepoNodeData } from '../../api/types.ts'
import type { RepoRFNode } from '../nodeTypes.ts'
import { RepoNode } from './RepoNode.tsx'
import styles from './nodes.module.scss'

function repoData(overrides: Partial<RepoNodeData> = {}): RepoNodeData {
  return {
    kind: 'repo',
    provider: 'github',
    url: 'https://github.com/acme/widgets',
    tokenEnv: 'GH_TOKEN',
    mountPath: '/repo',
    branch: 'main',
    ...overrides,
  }
}

// RepoNode renders through the shared card scaffold; <Handle> needs a <ReactFlowProvider>.
function renderRepoNode(props: Partial<NodeProps<RepoRFNode>> = {}) {
  const base = {
    id: 'repo-1',
    data: repoData(),
    selected: false,
    type: 'repo',
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<RepoRFNode>
  return render(
    <ReactFlowProvider>
      <RepoNode {...base} {...props} />
    </ReactFlowProvider>,
  )
}

describe('RepoNode', () => {
  it('shows the octopus icon and the provider badge for github', () => {
    renderRepoNode({ data: repoData({ provider: 'github' }) })
    expect(screen.getByText('🐙')).toBeInTheDocument()
    expect(screen.getByText('github')).toBeInTheDocument()
  })

  it('shows the fox icon for gitlab', () => {
    renderRepoNode({ data: repoData({ provider: 'gitlab' }) })
    expect(screen.getByText('🦊')).toBeInTheDocument()
    expect(screen.getByText('gitlab')).toBeInTheDocument()
  })

  it('derives the title from the last two path segments of the url', () => {
    renderRepoNode({ data: repoData({ url: 'https://github.com/acme/widgets' }) })
    expect(screen.getByText('acme/widgets')).toBeInTheDocument()
  })

  it('strips a trailing slash before deriving the title', () => {
    renderRepoNode({ data: repoData({ url: 'https://github.com/acme/widgets/' }) })
    expect(screen.getByText('acme/widgets')).toBeInTheDocument()
  })

  it('falls back to "repo" when url is empty', () => {
    renderRepoNode({ data: repoData({ url: '' }) })
    expect(screen.getByText('repo')).toBeInTheDocument()
    expect(screen.getByText('no url')).toBeInTheDocument()
  })

  it('applies the repo variant class to the root', () => {
    const { container } = renderRepoNode()
    expect(container.firstChild).toHaveClass(styles.repo)
  })

  it('applies the selected class when selected is true', () => {
    const { container } = renderRepoNode({ selected: true })
    expect(container.firstChild).toHaveClass(styles.selected)
  })
})
