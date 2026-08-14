import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { PluginInfo } from '../api/types.ts'
import { PluginPicker } from './PluginPicker.tsx'

const plugins: PluginInfo[] = [
  { id: 'caveman@caveman', version: '1.2.0', scope: 'user', enabled: true },
  { id: 'superpowers@claude-plugins-official', version: '3.0.1', scope: 'user', enabled: true },
  { id: 'code-simplifier@claude-plugins-official', scope: 'user', enabled: false },
]

const openDialog = () => fireEvent.click(screen.getByText('Choose plugins…'))

describe('PluginPicker', () => {
  it('says that nothing selected means nothing loaded', () => {
    render(<PluginPicker plugins={plugins} selectedIds={[]} onChange={vi.fn()} />)

    // The old contract was the CLI's defaults, which made an empty list a lie about the run.
    expect(screen.getByText(/runs load no plugins at all/)).toBeInTheDocument()
  })

  it('picks in a dialog and commits on Load', () => {
    const onChange = vi.fn()
    render(<PluginPicker plugins={plugins} selectedIds={[]} onChange={onChange} />)

    openDialog()
    fireEvent.click(screen.getByText('☐ caveman@caveman'))
    expect(onChange).not.toHaveBeenCalled()

    fireEvent.click(screen.getByText('Load these 1'))
    expect(onChange).toHaveBeenCalledWith(['caveman@caveman'])
  })

  it('leaves the agent alone when the dialog is cancelled', () => {
    const onChange = vi.fn()
    render(<PluginPicker plugins={plugins} selectedIds={['caveman@caveman']} onChange={onChange} />)

    openDialog()
    fireEvent.click(screen.getByText('☑ caveman@caveman'))
    fireEvent.click(screen.getByText('Cancel'))

    expect(onChange).not.toHaveBeenCalled()
  })

  it('keeps a selection this machine cannot see, and says so', () => {
    // The machine that runs the flow may have it; dropping it here would edit the flow just by
    // opening the inspector.
    render(<PluginPicker plugins={plugins} selectedIds={['gone@somewhere']} onChange={vi.fn()} />)

    expect(screen.getByText(/Not installed here: gone@somewhere/)).toBeInTheDocument()
    expect(screen.getByText('1 loaded:')).toBeInTheDocument()
  })

  it('offers a plugin the CLI has switched off, marked as such', () => {
    // A run enables exactly the selection, so the CLI's own on/off is not what decides.
    render(<PluginPicker plugins={plugins} selectedIds={[]} onChange={vi.fn()} />)
    openDialog()

    expect(screen.getByText('☐ code-simplifier@claude-plugins-official')).toBeInTheDocument()
    expect(screen.getByText(/disabled in the CLI/)).toBeInTheDocument()
  })

  it('shows a version the CLI reports, whatever shape it is in', () => {
    render(
      <PluginPicker
        plugins={[
          { id: 'semver@m', version: '1.2.0', enabled: true },
          { id: 'sha@m', version: 'a0109974ea3288', enabled: true },
          { id: 'nothing@m', version: 'unknown', enabled: true },
        ]}
        selectedIds={[]}
        onChange={vi.fn()}
      />,
    )
    openDialog()

    expect(screen.getByText('v1.2.0')).toBeInTheDocument()
    // A sha is not a version: no "v", and short enough for the row.
    expect(screen.getByText('a0109974')).toBeInTheDocument()
    expect(screen.queryByText(/unknown/)).not.toBeInTheDocument()
  })

  it('clears everything from the sidebar', () => {
    const onChange = vi.fn()
    render(
      <PluginPicker plugins={plugins} selectedIds={['caveman@caveman', 'x@y']} onChange={onChange} />,
    )

    fireEvent.click(screen.getByText('Clear (2)'))

    expect(onChange).toHaveBeenCalledWith([])
  })
})
