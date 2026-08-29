import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { RunEvent } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { NodeLogView } from './NodeLogView.tsx'

const event = (over: Partial<RunEvent>): RunEvent => ({
  type: 'agent_message',
  text: 'line',
  agent: 'Writer',
  agentId: 'writer',
  ts: 0,
  ...over,
})

// One agent's slice of the console, matched on the canvas node id — so two agents that share a
// display name still get their own lines.
describe('NodeLogView', () => {
  beforeEach(() => useFlowStore.setState({ activeRunId: null, runEvents: [] }))

  it('asks for a run while none is selected', () => {
    useFlowStore.setState({ runEvents: [event({ text: 'stale' })] })
    render(<NodeLogView nodeId="writer" label="Writer" />)
    expect(screen.getByText("Select a run below to see this agent's output.")).toBeInTheDocument()
    expect(screen.queryByText('stale')).not.toBeInTheDocument()
  })

  it('names the agent that has said nothing yet', () => {
    useFlowStore.setState({ activeRunId: 'run_1', runEvents: [event({ agentId: 'reviewer', agent: 'Reviewer' })] })
    render(<NodeLogView nodeId="writer" label="Writer" />)
    expect(screen.getByText('No output from Writer yet.')).toBeInTheDocument()
  })

  it('shows only this node’s lines, by node id rather than by name', () => {
    useFlowStore.setState({
      activeRunId: 'run_1',
      runEvents: [
        event({ text: 'mine', agentId: 'writer' }),
        // Same display name, another block: not this node's line.
        event({ text: 'namesake', agentId: 'writer2', agent: 'Writer' }),
        event({ text: 'theirs', agentId: 'reviewer', agent: 'Reviewer' }),
        event({ text: 'mine too', agentId: 'writer', type: 'tool_use' }),
      ],
    })
    render(<NodeLogView nodeId="writer" label="Writer" />)

    expect(screen.getByText('mine')).toBeInTheDocument()
    expect(screen.getByText('mine too')).toBeInTheDocument()
    expect(screen.queryByText('namesake')).not.toBeInTheDocument()
    expect(screen.queryByText('theirs')).not.toBeInTheDocument()
  })

  it('falls back to the display name for lines recorded before ids existed', () => {
    useFlowStore.setState({
      activeRunId: 'run_1',
      runEvents: [event({ text: 'old line', agentId: undefined, agent: 'writer' })],
    })
    render(<NodeLogView nodeId="writer" label="Writer" />)
    expect(screen.getByText('old line')).toBeInTheDocument()
  })
})
