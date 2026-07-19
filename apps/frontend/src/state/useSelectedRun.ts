import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import { RUN_POLL_INTERVAL_MS } from '../constants.ts'
import { errMessage } from '../utils/errMessage.ts'
import { useFlowStore } from './store.ts'

const STORAGE_KEY = 'concentus.selectedRun'

/**
 * Tracks which run is currently inspected in Studio, persists the choice across a page
 * refresh (its output/persisted state is reloaded from the API), and polls per-node
 * execution state (Input/Output tabs, status dots, tokens) for it into the flow store.
 */
export function useSelectedRun(pushError: (m: string) => void) {
  const [selectedRun, setSelectedRun] = useState<string | null>(() => localStorage.getItem(STORAGE_KEY))

  useEffect(() => {
    if (selectedRun) localStorage.setItem(STORAGE_KEY, selectedRun)
    else localStorage.removeItem(STORAGE_KEY)
  }, [selectedRun])

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
