import {
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type RefObject,
} from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { RunSummary } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { money } from '../utils/format.ts'
import { CompareRunsModal } from './CompareRunsModal.tsx'
import { ComparePickerModal } from './ComparePickerModal.tsx'
import { Console } from './Console.tsx'
import { Spinner } from './Spinner.tsx'
import { useFlowStore } from '../state/store.ts'
import styles from './runs.module.scss'

/** Every trigger that isn't `manual` gets a badge; an unknown one falls back to its own name. */
const TRIGGER_LABEL: Record<string, string> = {
  cron: '⏱ auto',
  prompt: '▶ prompt',
  webhook: '⚡ hook',
  mail: '✉ mail',
  watch: '📁 watch',
  api: '🔌 api',
  golden: '⭐ golden',
  eval: '✓ eval',
}

/** Where the run list's width is remembered — per browser, like the dock's own height (App.tsx). */
const LIST_WIDTH_KEY = 'studio.runs.listWidth'
/** Matches the node palette's DEFAULT width (App.tsx), so the two left edges start aligned. */
const LIST_WIDTH_DEFAULT = 260
const LIST_WIDTH_MIN = 200
/** The list may take this share of the dock at most: the console is what the dock is for. */
const LIST_WIDTH_MAX_SHARE = 0.6
/** How far one arrow key moves the split. */
const LIST_WIDTH_STEP = 16

/** The remembered width, or null when there is none or it is not a width. */
function readStoredListWidth(): number | null {
  try {
    const n = Number(localStorage.getItem(LIST_WIDTH_KEY) ?? NaN)
    return Number.isFinite(n) && n > 0 ? n : null
  } catch {
    return null
  }
}

/** Remembers the width; null forgets it, so a future default applies. */
function storeListWidth(width: number | null) {
  try {
    if (width === null) localStorage.removeItem(LIST_WIDTH_KEY)
    else localStorage.setItem(LIST_WIDTH_KEY, String(width))
  } catch {
    // Storage refused (private mode, quota): the width still holds for this session.
  }
}

/** The widest the list may be in a dock this wide — unbounded while the dock has no width yet. */
function maxListWidth(dockWidth: number): number {
  return dockWidth > 0 ? Math.max(LIST_WIDTH_MIN, dockWidth * LIST_WIDTH_MAX_SHARE) : Infinity
}

function clampListWidth(width: number, max: number): number {
  return Math.min(max, Math.max(LIST_WIDTH_MIN, Math.round(width)))
}

/**
 * How wide the run list is, dragged on the splitter between it and the console and remembered
 * per browser — same home and same reason as the dock's height (App.tsx): how much of the dock
 * the list deserves is a fact about this screen, not about any flow.
 *
 * The stored width is read in the state initialiser so the first paint already has it; one
 * wider than the dock now allows is pulled back in a layout effect, before that paint. Written
 * on release and on each key press, not on every move — a drag is dozens of values a second and
 * only the last one is worth keeping.
 */
function useRunListWidth(dock: RefObject<HTMLElement | null>) {
  const [width, setWidth] = useState(() => {
    const stored = readStoredListWidth()
    return stored === null ? LIST_WIDTH_DEFAULT : Math.max(LIST_WIDTH_MIN, stored)
  })
  // The upper bound as last measured, for the handle's aria-valuemax.
  const [max, setMax] = useState(Infinity)
  const [dragging, setDragging] = useState(false)
  const dragActive = useRef(false)
  const lastDragged = useRef<number | null>(null)

  /** The dock as it is now: where the list starts, and how wide it may get. */
  const measure = () => {
    const rect = dock.current?.getBoundingClientRect()
    const bound = maxListWidth(rect?.width ?? 0)
    setMax(bound)
    return { left: rect?.left ?? 0, max: bound }
  }

  useLayoutEffect(() => {
    const bound = maxListWidth(dock.current?.getBoundingClientRect().width ?? 0)
    setMax(bound)
    setWidth((w) => Math.min(w, bound))
  }, [dock])

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return
    // Otherwise the drag selects the console's text as the pointer crosses it.
    e.preventDefault()
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // No active pointer to capture (a synthetic event); the handle still follows the moves it gets.
    }
    dragActive.current = true
    lastDragged.current = null
    setDragging(true)
  }
  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!dragActive.current) return
    const { left, max } = measure()
    const next = clampListWidth(e.clientX - left, max)
    lastDragged.current = next
    setWidth(next)
  }
  const endDrag = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!dragActive.current) return
    dragActive.current = false
    setDragging(false)
    try {
      e.currentTarget.releasePointerCapture(e.pointerId)
    } catch {
      // Nothing was captured.
    }
    if (lastDragged.current !== null) storeListWidth(lastDragged.current)
  }
  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const step = e.key === 'ArrowLeft' ? -LIST_WIDTH_STEP : e.key === 'ArrowRight' ? LIST_WIDTH_STEP : 0
    if (step === 0) return
    e.preventDefault()
    const next = clampListWidth(width + step, measure().max)
    setWidth(next)
    storeListWidth(next)
  }
  const reset = () => {
    setWidth(clampListWidth(LIST_WIDTH_DEFAULT, measure().max))
    storeListWidth(null)
  }

  return {
    width,
    max,
    dragging,
    handleProps: {
      onPointerDown,
      onPointerMove,
      onPointerUp: endDrag,
      onPointerCancel: endDrag,
      onKeyDown,
      onDoubleClick: reset,
    },
  }
}

interface Props {
  runs: RunSummary[]
  loading?: boolean
  selected: string | null
  onSelect: (id: string) => void
  /** The flow currently open on the canvas; null for an unsaved one. */
  flowId?: string | null
}

export function RunsPanel({ runs, loading = false, selected, onSelect, flowId = null }: Props) {
  const { t } = useTranslation()
  // Only this flow's executions. The panel sits under the flow you are editing, so a list mixing
  // in every other flow's runs is noise you have to read past — and worse, it makes the run you
  // just started hard to find. An unsaved flow has no id, so its ad-hoc runs (which have none
  // either) are what it shows.
  const mine = runs.filter((r) => (r.flowId ?? null) === flowId)

  // The polled list is a few seconds behind a click; overriding locally keeps the star honest
  // until the next poll agrees. Keyed by run id, so the override survives list refreshes.
  const [goldenOverride, setGoldenOverride] = useState<Record<string, boolean>>({})
  // The run the selected one is compared AGAINST. Null while nothing is being compared.
  const [compareWith, setCompareWith] = useState<string | null>(null)
  const [picking, setPicking] = useState(false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const isGolden = (r: RunSummary) => goldenOverride[r.id] ?? r.golden ?? false
  const goldenRun = mine.find(isGolden) ?? null

  const toggleGolden = async (r: RunSummary) => {
    setErr(null)
    try {
      const updated = await api.setGoldenRun(r.id, !isGolden(r))
      setGoldenOverride((prev) => {
        const next: Record<string, boolean> = { ...prev, [r.id]: updated.golden ?? false }
        // One reference per flow: promoting this run demoted whichever held the star.
        if (updated.golden) for (const other of mine) if (other.id !== r.id) next[other.id] = false
        return next
      })
    } catch (e) {
      setErr(errMessage(e))
    }
  }

  const rerunGolden = async () => {
    if (!goldenRun) return
    setBusy(true)
    setErr(null)
    try {
      const started = await api.goldenRerun(goldenRun.id)
      onSelect(started.id)
    } catch (e) {
      setErr(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const selectedRun = mine.find((r) => r.id === selected) ?? null
  const setReplay = useFlowStore((s) => s.setReplay)
  const replayShown = useFlowStore((s) => s.replay !== null)

  // Replay walks the run's recorded outputs through the flow as saved NOW and paints where the
  // path would diverge — on the canvas, which is where the flow being asked about is visible.
  const replay = async () => {
    if (!selectedRun) return
    setErr(null)
    try {
      setReplay(await api.replayRun(selectedRun.id))
    } catch (e) {
      setErr(errMessage(e))
    }
  }
  // Anything the selected run can be read against: this flow's other executions. Comparing used
  // to be available only against the golden reference, which is the right default and the wrong
  // only option — two runs of the same block on different models is the pair worth reading now.
  const others = mine.filter((r) => selectedRun && r.id !== selectedRun.id)
  const canCompare = selectedRun !== null && others.length > 0

  const startCompare = () => {
    if (!canCompare) return
    // One other run means there is no choice to present, and a dialog offering a single option is
    // a click that teaches nothing.
    if (others.length === 1) setCompareWith(others[0].id)
    else setPicking(true)
  }

  const dock = useRef<HTMLElement>(null)
  const list = useRunListWidth(dock)

  return (
    <section
      ref={dock}
      className={styles.runs}
      style={{ '--run-list-w': `${list.width}px` } as CSSProperties}
    >
      <div className={styles.runList}>
        <h3 className={styles.h3}>{t('Executions')}</h3>
        {goldenRun && (
          <div className={styles.goldenBar}>
            <button
              className={styles.goldenAction}
              disabled={busy}
              title={t("Re-run the golden reference's input against the flow as it is saved now — then compare the two runs")}
              onClick={() => void rerunGolden()}
            >
              {t('⭐▶ Test current flow')}
            </button>
          </div>
        )}
        {/* Outside the golden bar on purpose: comparing two ordinary runs is the common case, and
            it used to be unreachable for a flow that had never marked a reference. */}
        <div className={styles.goldenBar}>
          <button
            className={styles.goldenAction}
            disabled={!canCompare}
            title={
              canCompare
                ? t('Compare the selected execution with another one — the golden reference, or any other run of this flow')
                : t('Select an execution, with at least one other to read it against')
            }
            onClick={startCompare}
          >
            {t('⇄ Compare')}
          </button>
          <button
            className={styles.goldenAction}
            disabled={!selectedRun || !selectedRun.flowId}
            title={
              selectedRun?.flowId
                ? t('Walk this run’s recorded outputs through the flow as saved now, and paint on the canvas where the path would diverge. Nothing is executed.')
                : t('Select an execution of a saved flow — an ad-hoc run has no current flow to replay against.')
            }
            onClick={() => void (replayShown ? setReplay(null) : replay())}
          >
            {replayShown ? t('⟲ Hide replay') : t('⟲ Replay vs current')}
          </button>
        </div>
        {err && <div className={styles.err}>{err}</div>}
        {loading ? (
          <Spinner />
        ) : mine.length === 0 ? (
          <div className={styles.muted}>
            {runs.length > 0
              ? t('No executions for this flow yet. Press Run, or wait for its trigger.')
              : t('No executions yet. Design a flow and press Run.')}
          </div>
        ) : null}
        {!loading &&
          mine.map((r) => (
            // A div, not a button: the golden star nests inside, and a button may not contain
            // another button.
            <div
              key={r.id}
              role="button"
              tabIndex={0}
              className={cx(styles.runItem, selected === r.id && styles.active)}
              onClick={() => onSelect(r.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') onSelect(r.id)
              }}
            >
              <span className={cx(styles.dot, styles['s_' + r.status])} />
              <span className={styles.runName}>{r.flowName || t('flow')}</span>
              {r.trigger && r.trigger !== 'manual' && (
                <span className={styles.trigger}>{t(TRIGGER_LABEL[r.trigger] ?? r.trigger)}</span>
              )}
              {!!r.flowVersion && (
                <span
                  className={styles.version}
                  title={t('Ran flow version {{n}}. Opening this execution puts exactly that revision on the canvas.', { n: r.flowVersion })}
                >
                  v{r.flowVersion}
                </span>
              )}
              <span className={styles.runStatus}>{r.status}</span>
              {/* Who started it, where an argument about who started it would be had. Absent for a
                  schedule or a webhook: the trigger badge beside this already says what they
                  were, and a name invented for them would be worse than the gap. */}
              {r.startedBy && (
                <span className={styles.runCost} title={t('Started by {{name}}', { name: r.startedBy })}>
                  {r.startedBy.split('@')[0]}
                </span>
              )}
              {/* What this execution cost, where the choice to run another one is made. It was
                  only visible inside a comparison, which is one click and one decision too late
                  for the question it answers. */}
              {!!r.estimatedCostUsd && (
                <span
                  className={styles.runCost}
                  title={t("Estimated at this run's own model rates. On a Claude subscription there is no per-token bill — read it as equivalent usage.")}
                >
                  {money(r.estimatedCostUsd)}
                </span>
              )}
              {r.flowId && (
                <button
                  className={cx(styles.goldStar, isGolden(r) && styles.goldStarOn)}
                  title={
                    isGolden(r)
                      ? t('Golden reference — click to unmark')
                      : t('Mark as this flow’s golden reference')
                  }
                  aria-label={isGolden(r) ? t('Unmark golden reference') : t('Mark as golden reference')}
                  onClick={(e) => {
                    e.stopPropagation()
                    void toggleGolden(r)
                  }}
                >
                  {isGolden(r) ? '★' : '☆'}
                </button>
              )}
            </div>
          ))}
      </div>
      <div
        role="separator"
        aria-orientation="vertical"
        aria-label={t('Drag to resize')}
        aria-valuemin={LIST_WIDTH_MIN}
        aria-valuemax={Number.isFinite(list.max) ? Math.round(list.max) : undefined}
        aria-valuenow={list.width}
        tabIndex={0}
        title={t('Drag to resize')}
        className={cx(styles.runSplit, list.dragging && styles.runSplitOn)}
        {...list.handleProps}
      />
      <div className={styles.runMain}>
        {selected ? (
          <Console
            runId={selected}
            status={selectedRun?.status}
            flowVersion={selectedRun?.flowVersion}
          />
        ) : (
          <div className={styles.runEmpty}>{t('Select a run to see its output and send commands.')}</div>
        )}
      </div>
      {picking && selectedRun && (
        <ComparePickerModal
          runs={others}
          goldenId={goldenRun?.id ?? null}
          onPick={(id) => {
            setPicking(false)
            setCompareWith(id)
          }}
          onClose={() => setPicking(false)}
        />
      )}
      {compareWith && selectedRun && (
        <CompareRunsModal
          referenceId={compareWith}
          candidateId={selectedRun.id}
          onClose={() => setCompareWith(null)}
        />
      )}
    </section>
  )
}
