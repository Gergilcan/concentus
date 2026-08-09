import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { RunSummary } from '../api/types.ts'
import { RUN_POLL_INTERVAL_MS } from '../constants.ts'
import { errMessage } from '../utils/errMessage.ts'
import { useFlowStore } from './store.ts'

const STORAGE_KEY = 'concentus.selectedRun'

/**
 * Whether a selected run still belongs on screen now that a flow is open.
 *
 * <p>Only a run positively known to belong to a different flow is dropped. An unknown run — the
 * runs list has not arrived yet, or it was deleted — is kept, because clearing on absence would
 * wipe the selection during the load race that happens on every page refresh, which is exactly
 * what the persistence exists to survive.
 */
export function runBelongsToOpenFlow(
  runFlowId: string | null | undefined,
  openFlowId: string | null,
  known: boolean,
): boolean {
  if (!known) return true
  // Both null is a match: an ad-hoc run started from an unsaved canvas belongs to that canvas.
  return (runFlowId ?? null) === openFlowId
}

/**
 * Tracks which run is currently inspected in Studio, persists the choice across a page
 * refresh (its output/persisted state is reloaded from the API), and polls per-node
 * execution state (Input/Output tabs, status dots, tokens) for it into the flow store.
 */
export function useSelectedRun(pushError: (m: string) => void, runs: RunSummary[] = []) {
  const [selectedRun, setSelectedRun] = useState<string | null>(() => localStorage.getItem(STORAGE_KEY))

  useEffect(() => {
    if (selectedRun) localStorage.setItem(STORAGE_KEY, selectedRun)
    else localStorage.removeItem(STORAGE_KEY)
  }, [selectedRun])

  // Opening another flow drops a run belonging to the previous one. The selection is persisted
  // and lives above the canvas, so without this it followed the user from flow to flow, painting
  // one flow's node executions onto another's nodes — and the run's own console kept streaming
  // beside a canvas it had nothing to do with.
  const openFlowId = useFlowStore((s) => s.flowId)
  useEffect(() => {
    if (!selectedRun) return
    const run = runs.find((r) => r.id === selectedRun)
    if (!runBelongsToOpenFlow(run?.flowId, openFlowId, !!run)) setSelectedRun(null)
  }, [selectedRun, openFlowId, runs])

  const setActiveRun = useFlowStore((s) => s.setActiveRun)
  const setRunExec = useFlowStore((s) => s.setRunExec)
  useEffect(() => {
    setActiveRun(selectedRun)
    if (!selectedRun) {
      setRunExec(null)
      return
    }
    let alive = true
    const tick = () =>
      api
        .getRunNodes(selectedRun)
        .then((r) => alive && setRunExec(r))
        .catch((e) => alive && pushError(errMessage(e)))
    tick()
    const t = setInterval(tick, RUN_POLL_INTERVAL_MS)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [selectedRun, setActiveRun, setRunExec, pushError])

  return [selectedRun, setSelectedRun] as const
}
