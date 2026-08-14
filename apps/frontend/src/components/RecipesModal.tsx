import { useState } from 'react'
import { api } from '../api/client.ts'
import type { BackendFlow } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { CredentialField } from './CredentialField.tsx'
import { Modal } from './Modal.tsx'
import { Spinner } from './Spinner.tsx'
import { applyRecipe, fieldKey, RECIPES, type Recipe } from './recipes.ts'
import styles from './flows.module.scss'
import panels from './panels.module.scss'

/**
 * Start from an outcome: pick what you want to happen, answer the two or three things only you
 * can know, and get a configured flow.
 *
 * The gap this closes is the one between "the app can clearly do this" and "I have it working".
 * The Samples folder already shows the first; opening one lands you on a canvas of empty fields
 * with a system prompt telling you to fill them, which is where people stop.
 *
 * Nothing is invented for you: every question maps to a real field on a real node of the bundled
 * sample, and the flow is saved paused, because each of these has a trigger and starting one is a
 * decision about spending money.
 */
export function RecipesModal({
  onClose,
  onSaved,
  pushError,
}: {
  onClose: () => void
  /** The saved flow, so the page can refresh and open it. */
  onSaved: (flow: BackendFlow) => void
  pushError: (m: string) => void
}) {
  const [recipe, setRecipe] = useState<Recipe | null>(null)
  const [step, setStep] = useState(0)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [enabled, setEnabled] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const start = (r: Recipe) => {
    setRecipe(r)
    setStep(0)
    setAnswers({})
    setEnabled(false)
    setError(null)
  }

  const save = async () => {
    if (!recipe) return
    setSaving(true)
    setError(null)
    try {
      // Read the sample from the store rather than shipping a copy: it is the maintained graph,
      // and a copy here would be the one that goes stale.
      const sample = await api.getFlow(recipe.sampleId)
      const saved = await api.saveFlow(applyRecipe(sample, recipe, answers, enabled))
      onSaved(saved)
      onClose()
    } catch (e) {
      // Most likely cause by far: the sample was deleted. Say that, rather than the raw 404.
      setError(
        `${errMessage(e)} — this recipe builds on the bundled "${recipe.sampleId}" sample; if it was deleted, the recipe cannot be assembled.`,
      )
      pushError(errMessage(e))
    } finally {
      setSaving(false)
    }
  }

  if (!recipe) {
    return (
      <Modal title="Start from a recipe" onClose={onClose}>
        <p className={styles.describeHint}>
          Pick what you want to happen. Each one asks only for the pieces it cannot know, then saves
          a flow you can open and edit like any other.
        </p>
        <ul className={styles.recipeList} aria-label="Recipes">
          {RECIPES.map((r) => (
            <li key={r.id}>
              <button className={styles.recipeItem} onClick={() => start(r)}>
                <span className={styles.recipeTitle}>{r.title}</span>
                <span className={styles.recipeBlurb}>{r.blurb}</span>
              </button>
            </li>
          ))}
        </ul>
      </Modal>
    )
  }

  const question = recipe.questions[step]
  const last = step === recipe.questions.length - 1

  return (
    <Modal title={recipe.title} onClose={onClose}>
      <p className={panels.hint}>
        Question {step + 1} of {recipe.questions.length} — {question.title}
      </p>

      {question.fields.map((field) => {
        const key = fieldKey(field)
        const value = answers[key] ?? ''
        const set = (v: string) => setAnswers((prev) => ({ ...prev, [key]: v }))
        return (
          <div key={key}>
            {field.control === 'credential' ? (
              <CredentialField label={field.label} value={value} onChange={set} what="this mailbox" />
            ) : (
              <label className={styles.field}>
                <span>{field.label}</span>
                {field.control === 'textarea' ? (
                  <textarea
                    className={styles.describeInput}
                    rows={4}
                    placeholder={field.placeholder}
                    value={value}
                    onChange={(e) => set(e.target.value)}
                  />
                ) : (
                  <input
                    placeholder={field.placeholder}
                    value={value}
                    onChange={(e) => set(e.target.value)}
                  />
                )}
              </label>
            )}
            {field.help && <p className={panels.hint}>{field.help}</p>}
          </div>
        )
      })}

      {last && (
        <label className={styles.toggleRow}>
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          <span>Start it now — {recipe.enableHint}</span>
        </label>
      )}

      {error && <p className={styles.describeError}>{error}</p>}

      <div className={styles.modalActions}>
        <button
          className={styles.ghost}
          disabled={saving}
          onClick={() => (step === 0 ? setRecipe(null) : setStep(step - 1))}
        >
          Back
        </button>
        {last ? (
          <button className={styles.primary} disabled={saving} onClick={() => void save()}>
            {saving ? <Spinner label="Saving" /> : 'Create the flow'}
          </button>
        ) : (
          <button className={styles.primary} onClick={() => setStep(step + 1)}>
            Next
          </button>
        )}
      </div>
    </Modal>
  )
}
