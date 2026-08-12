import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { FlowsPage } from './FlowsPage.tsx'

vi.mock('../api/client.ts', () => ({ api: {} }))

// One flow at the root keeps the page out of its empty state so the folder grid renders.
const flow: BackendFlow = { id: 'f1', name: 'Mail triage', mode: 'local', nodes: [], edges: [] }

const renderPage = () => {
  const onSaveFlow = vi.fn().mockResolvedValue(undefined)
  render(
    <FlowsPage
      flows={[flow]}
      runs={[]}
      onOpen={vi.fn()}
      onRun={vi.fn()}
      onDuplicate={vi.fn()}
      onDelete={vi.fn()}
      onNew={vi.fn()}
      onOpenRun={vi.fn()}
      onSaveFlow={onSaveFlow}
      onRetryRun={vi.fn()}
      pushError={vi.fn()}
    />,
  )
  return { onSaveFlow }
}

/** jsdom has no DataTransfer; the drag tests carry one just real enough for the handlers. */
const makeDataTransfer = () => {
  const data: Record<string, string> = {}
  return {
    setData: (type: string, value: string) => {
      data[type] = value
    },
    getData: (type: string) => data[type] ?? '',
    get types() {
      return Object.keys(data)
    },
    dropEffect: 'none',
    effectAllowed: 'all',
  }
}

// Folder creation happens in an inline input, never window.prompt — Electron's renderer
// throws on prompt(), which made the "+ New folder" button die silently in the desktop app.
describe('FlowsPage folder creation', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('turns the tile into an input and creates the folder on Enter', () => {
    renderPage()
    fireEvent.click(screen.getByText('+ New folder'))

    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: '  Team A  ' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(screen.getByLabelText('Folder Team A')).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('flows.folderDrafts') ?? '[]')).toEqual(['Team A'])
    // The input hands back to the button once the name is committed.
    expect(screen.getByText('+ New folder')).toBeInTheDocument()
  })

  it('cancels on Escape without creating anything', () => {
    renderPage()
    fireEvent.click(screen.getByText('+ New folder'))

    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: 'Nope' } })
    fireEvent.keyDown(input, { key: 'Escape' })

    expect(screen.queryByLabelText('Folder Nope')).not.toBeInTheDocument()
    expect(localStorage.getItem('flows.folderDrafts')).toBeNull()
    expect(screen.getByText('+ New folder')).toBeInTheDocument()
  })

  it('surfaces a nested draft ("A/B") as its first segment at the current level', () => {
    renderPage()
    fireEvent.click(screen.getByText('+ New folder'))

    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: 'Team A/Ops' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    // At the root the draft shows as "Team A"; entering it reveals "Ops".
    fireEvent.click(screen.getByLabelText('Folder Team A'))
    expect(screen.getByLabelText('Folder Ops')).toBeInTheDocument()
  })

  it('moves a flow into a folder when its card is dropped on the tile', () => {
    const { onSaveFlow } = renderPage()
    fireEvent.click(screen.getByText('+ New folder'))
    const input = screen.getByLabelText('New folder name')
    fireEvent.change(input, { target: { value: 'Team A' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    const card = screen.getByText('Mail triage').closest('article') as HTMLElement
    const tile = screen.getByLabelText('Folder Team A')
    const dataTransfer = makeDataTransfer()
    fireEvent.dragStart(card, { dataTransfer })
    fireEvent.dragOver(tile, { dataTransfer })
    fireEvent.drop(tile, { dataTransfer })

    expect(onSaveFlow).toHaveBeenCalledWith(expect.objectContaining({ id: 'f1', folder: 'Team A' }))
  })
})
