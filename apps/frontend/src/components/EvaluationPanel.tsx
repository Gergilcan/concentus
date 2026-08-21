import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import type { EvalCase, EvalRun } from '../api/types.ts'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * The golden set: questions somebody will really ask, and which document answers them.
 *
 * Every change to how a base ranks sounds like an improvement, and the usual evidence is three
 * queries typed after the change. Under that regime a base gets quietly worse while everyone
 * agrees it feels sharper. Twenty questions with known answers turn the feeling into a number,
 * and a number can go down.
 *
 * What is measured is retrieval, not the answer: did the document holding the answer come back,
 * and how near the top. A wrong answer built on the right passage is a prompt problem. A
 * right-sounding answer built on the wrong passage is this one, and it is the one that ships
 * without anybody noticing.
 */
export function EvaluationPanel({ baseId, docs }: { baseId: string; docs: string[] }) {
  const [cases, setCases] = useState<EvalCase[] | null>(null)
  const [question, setQuestion] = useState('')
  const [expected, setExpected] = useState<string[]>([])
  const [run, setRun] = useState<EvalRun | null>(null)
  const [baseline, setBaseline] = useState<EvalRun | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [open, setOpen] = useState(false)

  const load = useCallback(() => {
    api
      .listEvals(baseId)
      .then(setCases)
      .catch(() => setCases([]))
  }, [baseId])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  const add = async () => {
    setNote(null)
    try {
      await api.addEval(baseId, question.trim(), expected)
      setQuestion('')
      setExpected([])
      load()
    } catch (e) {
      setNote(errMessage(e))
    }
  }

  const remove = async (id: string) => {
    await api.deleteEval(baseId, id).catch(() => {})
    // The old numbers described a set that no longer exists; keeping them on screen beside a
    // changed set is how a stale measurement gets quoted as a current one.
    setRun(null)
    setBaseline(null)
    load()
  }

  /**
   * Runs the set, and runs it again with the reranker off.
   *
   * Two numbers rather than one, because "is the reranker worth 282 MB" is a question only these
   * documents can answer — a benchmark on somebody else's corpus cannot, and the honest way to
   * find out is to measure both on yours.
   */
  const measure = async () => {
    setBusy(true)
    setNote(null)
    try {
      const withReranker = await api.runEvals(baseId, 5, true)
      setRun(withReranker)
      const without = await api.runEvals(baseId, 5, false)
      // Identical numbers mean no reranker is installed — there is nothing to compare, and a
      // comparison of a thing with itself would read as "the reranker does nothing".
      setBaseline(sameNumbers(withReranker, without) ? null : without)
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <div className={styles.embedderRow}>
        <button className={styles.textLink} onClick={() => setOpen(true)}>
          Measure this base ▸
        </button>
        <span className={styles.muted} title="Keep a few questions whose answers you know, and see whether retrieval actually finds them. It is the only way to tell an improvement from a feeling.">
          Questions with known answers ⓘ
        </span>
      </div>
    )
  }

  return (
    <div className={styles.evalBox}>
      <div className={styles.evalHead}>
        <h4 className={styles.h4}>Measuring this base</h4>
        <button className={styles.textLink} onClick={() => setOpen(false)}>
          Hide
        </button>
      </div>
      <p className={panels.hint}>
        Write down questions people really ask and the document that answers each. Then every
        change to this base can be checked instead of guessed at.
      </p>

      <div className={styles.evalAdd}>
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="What would somebody ask?"
          aria-label="Question"
        />
        <select
          value=""
          aria-label="Document that answers it"
          onChange={(e) => {
            const doc = e.target.value
            if (doc && !expected.includes(doc)) setExpected([...expected, doc])
          }}
        >
          <option value="">Answered by…</option>
          {docs.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
        <button className={styles.newBtn} disabled={!question.trim() || expected.length === 0} onClick={() => void add()}>
          Add
        </button>
      </div>
      {expected.length > 0 && (
        <div className={styles.evalChips}>
          {expected.map((d) => (
            <button key={d} className={styles.evalChip} onClick={() => setExpected(expected.filter((x) => x !== d))}>
              {d} ×
            </button>
          ))}
        </div>
      )}
      {note && <p className={panels.hint}>{note}</p>}

      {cases?.length === 0 && <div className={styles.muted}>No questions yet.</div>}
      {cases?.map((c) => (
        <div key={c.id} className={styles.evalCase}>
          <span className={styles.evalQuestion}>{c.question}</span>
          <span className={styles.muted}>{c.expectedDocs.join(', ')}</span>
          <button className={styles.textLink} onClick={() => void remove(c.id)}>
            Remove
          </button>
        </div>
      ))}

      {cases && cases.length > 0 && (
        <div className={styles.embedderRow}>
          <button className={styles.newBtn} disabled={busy} onClick={() => void measure()}>
            {busy ? 'Measuring…' : 'Run the questions'}
          </button>
          <span className={styles.muted}>
            {cases.length} question{cases.length === 1 ? '' : 's'} · top 5
          </span>
        </div>
      )}

      {run && <Numbers run={run} baseline={baseline} />}
    </div>
  )
}

/** Two runs are the same measurement when every number matches — no reranker was installed. */
function sameNumbers(a: EvalRun, b: EvalRun): boolean {
  return a.hitRate === b.hitRate && a.recall === b.recall && a.mrr === b.mrr
}

const percent = (n: number) => `${Math.round(n * 100)}%`

function Numbers({ run, baseline }: { run: EvalRun; baseline: EvalRun | null }) {
  return (
    <div className={styles.evalResults}>
      <div className={styles.evalScores}>
        <Score
          label="Answered"
          value={percent(run.hitRate)}
          help="Share of questions where an expected document made the top 5. The headline: how often somebody would have got their answer at all."
        />
        <Score
          label="Documents found"
          value={percent(run.recall)}
          help="Share of every expected document that came back. Lower than Answered whenever a question expects two documents and only one arrived — a half answer, which the headline alone would hide."
        />
        <Score
          label="Rank quality"
          value={run.mrr.toFixed(2)}
          help="1.00 when answers come first, 0.50 when they come second, 0 when they never come. What separates finding it from finding it first — and a passage at rank 5 spends the context budget of four wrong ones to get there."
        />
      </div>
      {baseline && (
        <p className={panels.hint}>
          Without the reranker: {percent(baseline.hitRate)} answered · {percent(baseline.recall)}{' '}
          found · {baseline.mrr.toFixed(2)} rank quality.{' '}
          {run.mrr > baseline.mrr
            ? 'The reranker is earning its disk space on your documents.'
            : 'The reranker is not helping here — these documents may be distinctive enough without it.'}
        </p>
      )}
      <div className={styles.evalRows}>
        {run.results.map((r) => (
          <div key={r.id} className={styles.evalRow}>
            <span className={r.rank > 0 ? styles.okDot : styles.errText}>
              {r.rank > 0 ? `#${r.rank}` : 'miss'}
            </span>
            <span className={styles.evalQuestion}>{r.question}</span>
            <span className={styles.muted} title={`Retrieved: ${r.returned.join(', ') || 'nothing'}`}>
              {/* A miss is only actionable if you can see what came instead. */}
              {r.rank > 0 ? r.found.join(', ') : `got ${r.returned.slice(0, 2).join(', ') || 'nothing'}`}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

function Score({ label, value, help }: { label: string; value: string; help: string }) {
  return (
    <div className={styles.evalScore} title={help}>
      <div className={styles.evalScoreValue}>{value}</div>
      <div className={styles.muted}>{label} ⓘ</div>
    </div>
  )
}
