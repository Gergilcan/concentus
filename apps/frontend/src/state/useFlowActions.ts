import { api } from '../api/client.ts'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import type { View } from '../components/AppHeader.tsx'
import { errMessage } from '../utils/errMessage.ts'
import { useFlowStore } from './store.ts'

/** Flow-dashboard and Studio actions: everything that loads/mutates a flow or starts/opens a run. */
export function useFlowActions({
  flows,
  runs,
  refreshFlows,
  refreshRuns,
  setView,
  setSelectedRun,
  pushError,
}: {
  flows: BackendFlow[]
  runs: RunSummary[]
  refreshFlows: () => void
  refreshRuns: () => Promise<void>
  setView: (v: View) => void
  setSelectedRun: (id: string | null) => void
  pushError: (m: string) => void
}) {
  const onRunStarted = (r: RunSummary) => {
    refreshRuns()
    setSelectedRun(r.id)
  }

  const openFlow = async (id: string) => {
    try {
      useFlowStore.getState().loadBackendFlow(await api.getFlow(id))
      setView('studio')
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const runFlow = async (id: string) => {
    try {
      useFlowStore.getState().loadBackendFlow(await api.getFlow(id))
      setView('studio')
      onRunStarted(await api.runSavedFlow(id))
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const duplicateFlow = async (flow: BackendFlow) => {
    try {
      await api.saveFlow({ ...flow, id: undefined, name: `${flow.name} (copy)` })
      refreshFlows()
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const deleteFlow = async (id: string) => {
    const flow = flows.find((f) => f.id === id)
    if (!confirm(`Delete "${flow?.name ?? 'this flow'}"? This cannot be undone.`)) return
    try {
      await api.deleteFlow(id)
      refreshFlows()
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const newFlow = () => {
    useFlowStore.getState().newFlow()
    setView('studio')
  }

  /** Persists a flow edited from the dashboard (favourite, tags, pause, webhook, import). */
  const saveFlowFromDashboard = async (flow: BackendFlow) => {
    const saved = await api.saveFlow(flow)
    // Keep the canvas in sync if this is the flow currently open in Studio.
    if (saved.id && useFlowStore.getState().flowId === saved.id) {
      useFlowStore.getState().loadBackendFlow(saved)
    }
    refreshFlows()
  }

  /**
   * Opens an execution: puts the exact flow that ran onto the canvas first, so every block lines
   * up with the run's recorded input/output and status. Without this the inspector would be
   * looking at whatever flow happened to be open (or none), and show nothing.
   */
  const openRun = async (runId: string) => {
    const current = useFlowStore.getState().flowId
    const run = runs.find((r) => r.id === runId)
    if (!run?.flowId || run.flowId !== current) {
      try {
        useFlowStore.getState().loadBackendFlow(await api.getRunFlow(runId))
      } catch {
        // No stored snapshot (older run) — fall back to the saved flow if there is one.
        if (run?.flowId) {
          try {
            useFlowStore.getState().loadBackendFlow(await api.getFlow(run.flowId))
          } catch (e) {
            pushError(errMessage(e))
          }
        }
      }
    }
    setSelectedRun(runId)
    setView('studio')
  }

  const retryRun = async (runId: string) => {
    try {
      onRunStarted(await api.retryRun(runId))
      setView('studio')
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  return { onRunStarted, openFlow, runFlow, duplicateFlow, deleteFlow, newFlow, saveFlowFromDashboard, openRun, retryRun }
}
