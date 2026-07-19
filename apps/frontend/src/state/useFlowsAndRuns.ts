import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import type { View } from '../components/AppHeader.tsx'
import { FLOWS_POLL_INTERVAL_MS } from '../constants.ts'
import { errMessage } from '../utils/errMessage.ts'

/**
 * Loads flows and runs on mount, then polls the run list every few seconds while it's
 * actually useful: skip ticks while the tab is hidden, the Studio view isn't active, or a
 * previous poll is still in flight (never overlap requests).
 */
export function useFlowsAndRuns(view: View, pushError: (m: string) => void) {
  const [flows, setFlows] = useState<BackendFlow[]>([])
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [runsLoading, setRunsLoading] = useState(true)

  const refreshFlows = useCallback(() => {
    api.listFlows().then(setFlows).catch((e) => pushError(errMessage(e)))
  }, [pushError])

  const refreshRuns = useCallback(() => {
    return api.listRuns().then(setRuns).catch((e) => pushError(errMessage(e)))
  }, [pushError])

  const pollingRef = useRef(false)
  useEffect(() => {
    refreshFlows()
    refreshRuns().finally(() => setRunsLoading(false))

    const tick = () => {
      if (document.visibilityState === 'hidden') return
      if (view !== 'studio') return
      if (pollingRef.current) return
      pollingRef.current = true
      refreshRuns().finally(() => {
        pollingRef.current = false
      })
    }

    const t = setInterval(tick, FLOWS_POLL_INTERVAL_MS)
    return () => clearInterval(t)
  }, [refreshFlows, refreshRuns, view])

  return { flows, runs, runsLoading, refreshFlows, refreshRuns }
}
