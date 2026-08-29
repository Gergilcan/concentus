import { type Dispatch, type SetStateAction, useCallback, useEffect, useRef, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'

/**
 * A panel's first fetch, the way every admin panel does it: one request on mount, nothing but a
 * spinner until it answers (`value` is null), and on failure the error toast plus `fallback`, so
 * the panel shows its empty state instead of spinning forever. No fallback keeps the spinner.
 *
 * `reload` repeats the request after a mutation whose full result the panel does not get back (a
 * create, a revoke); `setValue` is for one whose answer IS the new state.
 */
export function usePanelLoad<T>(
  fetch: () => Promise<T>,
  pushError: (message: string) => void,
  fallback?: T,
): { value: T | null; setValue: Dispatch<SetStateAction<T | null>>; reload: () => void } {
  const [value, setValue] = useState<T | null>(null)
  // Read at request time rather than listed as dependencies: the fetch is a closure and the
  // fallback usually a literal, and depending on them would repeat the request on every render.
  const latest = useRef({ fetch, pushError, fallback })
  latest.current = { fetch, pushError, fallback }

  const reload = useCallback(() => {
    const { fetch, pushError, fallback } = latest.current
    fetch()
      .then(setValue)
      .catch((e) => {
        if (fallback !== undefined) setValue(fallback)
        pushError(errMessage(e))
      })
  }, [])

  useEffect(reload, [reload])

  return { value, setValue, reload }
}
