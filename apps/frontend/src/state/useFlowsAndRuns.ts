import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import type { View } from '../components/AppNav.ts'
import { FLOWS_POLL_INTERVAL_MS } from '../constants.ts'
import { errMessage } from '../utils/errMessage.ts'

/**
 * How many run ticks pass between two flow-list refreshes.
 *
 * Runs change second to second — one is starting, streaming, or finishing while somebody watches.
 * The set of flows changes when a person saves one, or when an agent writes one through the MCP
 * server, which is rare by comparison. Asking for both at the same rate would spend most of the
 * requests confirming a list nobody touched.
 */
const FLOW_REFRESH_EVERY = 5

/**
 * Loads flows and runs on mount, then polls the run list every few seconds while it's
 * actually useful: skip ticks while the tab is hidden, the Studio view isn't active, or a
 * previous poll is still in flight (never overlap requests).
 *
 * <p>The flow list is polled too, slower, and refetched whenever the window is brought back to
 * the front. It used to be fetched once at mount, which was fine while this screen was the only
 * way to create a flow. It stopped being fine the day the backend became an MCP server: an agent
 * writes a flow from outside, the store has it, and the window goes on showing a dashboard that
 * quietly predates it — until somebody reloads the page and wonders where that flow came from.
 */
export function useFlowsAndRuns(view: View, pushError: (m: string) => void) {
  const [flows, setFlows] = useState<BackendFlow[]>([])
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [runsLoading, setRunsLoading] = useState(true)

  // Returns its promise, so a poll can wait for the previous one instead of stacking requests.
  const refreshFlows = useCallback(() => {
    return api.listFlows().then(setFlows).catch((e) => pushError(errMessage(e)))
  }, [pushError])

  const refreshRuns = useCallback(() => {
    return api.listRuns().then(setRuns).catch((e) => pushError(errMessage(e)))
  }, [pushError])

  const pollingRef = useRef(false)
  const flowsPollingRef = useRef(false)
  useEffect(() => {
    refreshFlows()
    refreshRuns().finally(() => setRunsLoading(false))

    // Worth asking at all: the tab is on screen and this is the view the answer is drawn on.
    const worthAsking = () => document.visibilityState !== 'hidden' && view === 'studio'

    const refreshFlowList = () => {
      if (flowsPollingRef.current) return
      flowsPollingRef.current = true
      refreshFlows().finally(() => {
        flowsPollingRef.current = false
      })
    }

    let ticks = 0
    const tick = () => {
      if (!worthAsking()) return
      // Counted only on ticks that ran, so a spell with the tab hidden delays the flow refresh
      // rather than banking up ticks that all come due the moment somebody returns.
      if (++ticks % FLOW_REFRESH_EVERY === 0) refreshFlowList()
      if (pollingRef.current) return
      pollingRef.current = true
      refreshRuns().finally(() => {
        pollingRef.current = false
      })
    }

    // Coming back to the window is the cheap case the interval would otherwise make people wait
    // out: alt-tab away, save a flow from somewhere else, come back to a list that already knows.
    const onFocus = () => {
      if (worthAsking()) refreshFlowList()
    }
    window.addEventListener('focus', onFocus)

    const t = setInterval(tick, FLOWS_POLL_INTERVAL_MS)
    return () => {
      clearInterval(t)
      window.removeEventListener('focus', onFocus)
    }
  }, [refreshFlows, refreshRuns, view])

  return { flows, runs, runsLoading, refreshFlows, refreshRuns }
}
