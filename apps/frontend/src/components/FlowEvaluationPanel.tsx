import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { api } from '../api/client.ts'
import type { FlowEvalCase, FlowEvalCaseInput, FlowEvalJudge, FlowEvalResult } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { versionHeadline } from './flowEvaluation.ts'
import { timeAgo } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import styles from './flowEvaluation.module.scss'

/** How often a running evaluation is re-read. A case is a whole agent run; seconds are invisible. */
const POLL_MS = 3_000

const JUDGES: FlowEvalJudge[] = ['contains', 'regex', 'exact', 'llm']

/** What each judge is called in the select, and what its Expected field asks for. */
function judgeLabel(judge: FlowEvalJudge, t: TFunction): string {
  switch (judge) {
    case 'regex':
      return t('matches a regular expression')
    case 'exact':
      return t('is exactly this text')
    case 'llm':
      return t('LLM judge — a model call per case')
    default:
      return t('contains text (ignoring case)')
  }
}

function expectedPlaceholder(judge: FlowEvalJudge, t: TFunction): string {
  switch (judge) {
    case 'regex':
      return t('A regular expression the answer must match')
    case 'exact':
      return t('The whole answer, exactly')
    case 'llm':
      return t('What the answer must satisfy, in a sentence — a model reads it and the answer, and says PASS or FAIL')
    default:
      return t('Text that must appear in the answer')
  }
}

const EMPTY_DRAFT: FlowEvalCaseInput = { name: '', input: '', expected: '', judge: 'contains' }

/**
 * A flow's evaluation dataset and its scores.
 *
 * <p>A golden run says whether an edit changed the answer; it cannot say whether the answer is
 * right. Cases carry the measure — an input, an expectation, a judge — and every evaluation runs
 * the flow once per case and stamps the score with the version it ran, so two versions can be
 * read side by side instead of two transcripts.
 *
 * <p>Three judges are free string checks; the fourth asks a model, which is the only way to judge
 * meaning and also a model call per case. The select says so where the choice is made.
 */
export function FlowEvaluationPanel({
  flowId,
  onOpenRun,
  pushError,
}: {
  /** Null for an unsaved flow: cases belong to a flow id, so there is nothing to keep them under. */
  flowId: string | null
  /** Opens an execution in the console — a failed case is only actionable if its run can be read. */
  onOpenRun?: (runId: string) => void
  pushError: (m: string) => void
}) {
  const { t } = useTranslation()
  const [cases, setCases] = useState<FlowEvalCase[] | null>(null)
  const [results, setResults] = useState<FlowEvalResult[] | null>(null)
  // The case being written or edited; null while the form is closed.
  const [draft, setDraft] = useState<FlowEvalCaseInput | null>(null)
  const [busy, setBusy] = useState(false)
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})

  const load = useCallback(() => {
    if (!flowId) {
      setCases([])
      setResults([])
      return
    }
    api
      .listEvalCases(flowId)
      .then(setCases)
      .catch(() => setCases([]))
    api
      .listEvalResults(flowId)
      .then(setResults)
      .catch(() => setResults([]))
  }, [flowId])

  useEffect(load, [load])

  // A running evaluation is re-read until it is done. Polling one id per running result rather
  // than the whole list: the list is every evaluation ever run, and only the live ones change.
  const running = results?.filter((r) => r.status === 'running') ?? []
  const runningIds = running.map((r) => r.id).join(',')
  useEffect(() => {
    if (!flowId || !runningIds) return
    const timer = setInterval(() => {
      for (const id of runningIds.split(',')) {
        api
          .getEvalResult(flowId, id)
          .then((fresh) => setResults((prev) => prev?.map((r) => (r.id === fresh.id ? fresh : r)) ?? prev))
          .catch(() => {})
      }
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [flowId, runningIds])

  const saveCase = async () => {
    if (!flowId || !draft) return
    setBusy(true)
    try {
      await api.saveEvalCase(flowId, draft)
      setDraft(null)
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const deleteCase = async (c: FlowEvalCase) => {
    if (!flowId) return
    if (!confirm(t('Delete the case "{{name}}"? Past results keep their verdicts.', { name: c.name }))) return
    try {
      await api.deleteEvalCase(flowId, c.id)
      load()
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const runEvaluation = async () => {
    if (!flowId) return
    setBusy(true)
    try {
      const started = await api.runEvaluation(flowId)
      // At the top at once, open, so the first verdict lands somewhere the eye already is.
      setResults((prev) => [started, ...(prev ?? [])])
      setExpanded((prev) => ({ ...prev, [started.id]: true }))
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (cases === null || results === null) {
    return (
      <div className={styles.empty}>
        <Spinner />
      </div>
    )
  }

  const canSave = !!draft && draft.name.trim() !== '' && draft.input.trim() !== '' && draft.expected.trim() !== ''
  const headline = versionHeadline(results)

  return (
    <div className={styles.wrap}>
      <p className={styles.hint}>
        {t(
          'Cases run this flow with an input of your choosing and judge its final answer. The score is stamped with the flow version it ran, so an edit can be measured against the version before it.',
        )}
      </p>

      <section className={styles.section}>
        <div className={styles.head}>
          <h4 className={styles.h4}>{t('Cases')}</h4>
          <button className={styles.ghost} disabled={!!draft || busy} onClick={() => setDraft({ ...EMPTY_DRAFT })}>
            {t('+ Add case')}
          </button>
        </div>

        {draft && (
          <div className={styles.form}>
            <label className={styles.field}>
              <span>{t('Case name')}</span>
              <input
                value={draft.name}
                aria-label={t('Case name')}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </label>
            <label className={styles.field}>
              <span>{t('Input')}</span>
              <textarea
                value={draft.input}
                rows={3}
                aria-label={t('Input')}
                placeholder={t('What the flow is run with — the same text you would type as its first instruction.')}
                onChange={(e) => setDraft({ ...draft, input: e.target.value })}
              />
            </label>
            <label className={styles.field}>
              <span>{t('Judge')}</span>
              <select
                value={draft.judge}
                aria-label={t('Judge')}
                onChange={(e) => setDraft({ ...draft, judge: e.target.value as FlowEvalJudge })}
              >
                {JUDGES.map((j) => (
                  <option key={j} value={j}>
                    {judgeLabel(j, t)}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.field}>
              <span>{t('Expected')}</span>
              <textarea
                value={draft.expected}
                rows={2}
                aria-label={t('Expected')}
                placeholder={expectedPlaceholder(draft.judge, t)}
                onChange={(e) => setDraft({ ...draft, expected: e.target.value })}
              />
            </label>
            <div className={styles.formActions}>
              <button className={styles.primary} disabled={!canSave || busy} onClick={() => void saveCase()}>
                {t('Save case')}
              </button>
              <button className={styles.ghost} disabled={busy} onClick={() => setDraft(null)}>
                {t('Cancel')}
              </button>
            </div>
          </div>
        )}

        {cases.length === 0 && !draft ? (
          <div className={styles.empty}>{t('No cases yet. Add one with an input and what its answer must contain.')}</div>
        ) : (
          <ul className={styles.list} aria-label={t('Evaluation cases')}>
            {cases.map((c) => (
              <li key={c.id} className={styles.row}>
                <span className={styles.name} title={c.input}>
                  {c.name}
                </span>
                <span className={styles.chip} title={judgeLabel(c.judge, t)}>
                  {c.judge}
                </span>
                <span className={styles.expected} title={c.expected}>
                  {c.expected}
                </span>
                <button
                  className={styles.ghost}
                  disabled={!!draft || busy}
                  onClick={() => setDraft({ id: c.id, name: c.name, input: c.input, expected: c.expected, judge: c.judge })}
                >
                  {t('Edit')}
                </button>
                <button className={styles.ghost} disabled={busy} onClick={() => void deleteCase(c)}>
                  {t('Delete')}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className={styles.section}>
        <div className={styles.head}>
          <h4 className={styles.h4}>{t('Results')}</h4>
          <button
            className={styles.primary}
            disabled={busy || cases.length === 0 || running.length > 0}
            title={
              cases.length === 0
                ? t('Add a case first — an evaluation with no cases has nothing to score.')
                : t(
                    'Runs the flow once per case — {{count}} real runs, each priced and listed like any other — and judges every answer. Cases with the LLM judge cost one extra model call each.',
                    { count: cases.length },
                  )
            }
            onClick={() => void runEvaluation()}
          >
            {running.length > 0 ? t('Running…') : t('Run evaluation')}
          </button>
        </div>

        {headline.length > 0 && (
          <div className={styles.headline} aria-label={t('Score per version')}>
            <span className={styles.headlineLabel}>{t('Score per version')}</span>
            {headline.map((r, i) => (
              <span key={r.id} className={styles.headlineScore}>
                {i > 0 && <span className={styles.arrow}>→</span>}
                <span className={styles.num}>v{r.flowVersion}</span>{' '}
                <span className={cx(styles.score, r.passed === r.total ? styles.allPassed : styles.someFailed)}>
                  {r.passed}/{r.total}
                </span>
              </span>
            ))}
          </div>
        )}

        {results.length === 0 ? (
          <div className={styles.empty}>{t('No evaluations yet.')}</div>
        ) : (
          <ul className={styles.list} aria-label={t('Evaluation results')}>
            {results.map((r) => {
              const open = expanded[r.id] ?? false
              return (
                <li key={r.id} className={styles.result}>
                  <div className={styles.row}>
                    <button
                      className={styles.toggle}
                      aria-label={open ? t('Hide cases') : t('Show cases')}
                      aria-expanded={open}
                      onClick={() => setExpanded((prev) => ({ ...prev, [r.id]: !open }))}
                    >
                      {open ? '▾' : '▸'}
                    </button>
                    <span className={styles.num}>v{r.flowVersion}</span>
                    <span className={cx(styles.score, r.status === 'done' && (r.passed === r.total ? styles.allPassed : styles.someFailed))}>
                      {r.passed}/{r.total}
                    </span>
                    {r.status === 'running' && (
                      <span className={styles.running}>
                        {t('judged {{done}} of {{total}}…', { done: r.cases.length, total: r.total })}
                      </span>
                    )}
                    <span className={styles.time}>{timeAgo(r.startedAt)}</span>
                  </div>
                  {open && (
                    <ul className={styles.caseList}>
                      {r.cases.map((c) => (
                        <li key={c.caseId} className={styles.caseRow}>
                          <span
                            className={c.passed ? styles.pass : styles.fail}
                            aria-label={c.passed ? t('passed') : t('failed')}
                          >
                            {c.passed ? '✓' : '✗'}
                          </span>
                          <span className={styles.name}>{c.name}</span>
                          <span className={styles.why} title={c.output ?? undefined}>
                            {c.why}
                          </span>
                          {c.runId && onOpenRun && (
                            <button className={styles.ghost} onClick={() => onOpenRun(c.runId!)}>
                              {t('Open run')}
                            </button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </section>
    </div>
  )
}
