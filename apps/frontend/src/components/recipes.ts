import type { BackendFlow } from '../api/types.ts'

/**
 * Outcomes, not templates.
 *
 * The Samples folder answers "what can this thing do" and stops there: opening one drops you on a
 * canvas with empty fields and a system prompt telling you to go and fill them. A recipe is the
 * same flow with the holes named — the two or three things only you can know — asked as questions
 * and filled in for you. Empty install to a configured flow without opening the inspector once.
 *
 * Each recipe points at a BUNDLED sample by its id rather than carrying its own copy of the graph.
 * The samples are the maintained thing; a second copy here would be the one that goes stale, and
 * it would drift silently because nothing runs it.
 */

export type RecipeControl = 'text' | 'textarea' | 'credential' | 'cron'

export interface RecipeField {
  /** Node in the sample whose data this fills. */
  nodeId: string
  field: string
  label: string
  control: RecipeControl
  placeholder?: string
  /** Shown under the field — what the value is for, in the user's terms. */
  help?: string
}

export interface RecipeQuestion {
  title: string
  fields: RecipeField[]
}

export interface Recipe {
  id: string
  /** The outcome, phrased as what you get — not as what the flow is made of. */
  title: string
  blurb: string
  /** The bundled sample this builds on. */
  sampleId: string
  questions: RecipeQuestion[]
  /**
   * Why this flow stays paused until you say otherwise. Every recipe here has a TRIGGER, so
   * saving it enabled would start it spending on a schedule the user has not looked at yet.
   */
  enableHint: string
}

export const RECIPES: Recipe[] = [
  {
    id: 'inbox-triage',
    title: 'Triage my inbox and draft the replies',
    blurb: 'Reads each new message, says what it needs, and writes a draft answer when one is due.',
    sampleId: 'mailbox-assistant',
    enableHint:
      'It watches the mailbox on a timer once started. Leave it off to open it on the canvas first.',
    questions: [
      {
        title: 'Where is your mailbox?',
        fields: [
          {
            nodeId: 'in-1',
            field: 'mailHost',
            label: 'IMAP server',
            control: 'text',
            placeholder: 'imap.gmail.com',
            help: 'Your provider’s IMAP host. Gmail: imap.gmail.com · Microsoft 365: outlook.office365.com',
          },
          {
            nodeId: 'in-1',
            field: 'mailUsername',
            label: 'Mailbox address',
            control: 'text',
            placeholder: 'you@example.com',
          },
          {
            nodeId: 'in-1',
            field: 'mailCredentialId',
            label: 'Password or app password',
            control: 'credential',
            help: 'Stored encrypted under Resources → Credentials; the flow keeps only its id.',
          },
        ],
      },
      {
        title: 'Which messages should it look at?',
        fields: [
          {
            nodeId: 'in-1',
            field: 'mailFolder',
            label: 'Folder',
            control: 'text',
            placeholder: 'INBOX',
          },
          {
            nodeId: 'in-1',
            field: 'mailSubjectContains',
            label: 'Only subjects containing (optional)',
            control: 'text',
            placeholder: 'invoice',
            help: 'Leave empty to triage everything that arrives unread.',
          },
        ],
      },
    ],
  },
  {
    id: 'morning-briefing',
    title: 'Send me a briefing every morning',
    blurb: 'Researches the topics you name and writes a short, sourced summary on a schedule.',
    sampleId: 'daily-briefing',
    enableHint: 'It runs on a schedule once started, and every run costs tokens.',
    questions: [
      {
        title: 'What should it track?',
        fields: [
          {
            nodeId: 'in-1',
            field: 'prompt',
            label: 'Topics',
            control: 'textarea',
            placeholder:
              'Write today’s briefing on: our competitors (Acme, Globex), EU AI regulation, and Postgres releases.',
            help: 'This is the message the flow sends itself each morning. Name the topics plainly.',
          },
        ],
      },
      {
        title: 'When?',
        fields: [
          {
            nodeId: 'in-1',
            field: 'cron',
            label: 'Schedule',
            control: 'cron',
            placeholder: '0 7 * * 1-5',
            help: 'Five fields: minute hour day month weekday. 0 7 * * 1-5 is 07:00 on weekdays.',
          },
        ],
      },
    ],
  },
]

/** Every field a recipe asks about, in order. */
export function fieldsOf(recipe: Recipe): RecipeField[] {
  return recipe.questions.flatMap((q) => q.fields)
}

/**
 * The sample with the answers filled in, ready to save as a new flow.
 *
 * The id is dropped so saving CREATES a flow rather than overwriting the sample — the sample stays
 * where it is, which is the whole point of it being a sample. The folder goes with it: a flow the
 * user just configured belongs in their own list, not filed under "Samples" with the starters.
 *
 * An answer left blank is not written at all, so the sample's own default survives instead of
 * being overwritten with an empty string — the difference between "INBOX" and no folder at all.
 */
export function applyRecipe(
  sample: BackendFlow,
  recipe: Recipe,
  answers: Record<string, string>,
  enabled: boolean,
): BackendFlow {
  const byKey = new Map(fieldsOf(recipe).map((f) => [fieldKey(f), f]))
  const nodes = sample.nodes.map((node) => {
    const patch: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(answers)) {
      const field = byKey.get(key)
      if (!field || field.nodeId !== node.id || value.trim() === '') continue
      patch[field.field] = value.trim()
    }
    return Object.keys(patch).length === 0 ? node : { ...node, data: { ...node.data, ...patch } }
  })

  return {
    ...sample,
    id: undefined,
    name: recipe.title,
    folder: '',
    enabled,
    nodes,
  }
}

/** Stable key for an answer: a recipe may fill the same field name on two different nodes. */
export function fieldKey(field: RecipeField): string {
  return `${field.nodeId}.${field.field}`
}
