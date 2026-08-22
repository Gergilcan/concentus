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

/** Used as a field's {@code nodeId} when the answer belongs to the flow rather than to a node. */
export const FLOW = '@flow'

export interface RecipeField {
  /**
   * Node in the sample whose data this fills, or {@link FLOW} for the flow itself.
   *
   * <p>Recipes could only fill node data, which left everything a flow knows about itself out of
   * reach — where to shout when a run fails, which Slack channel answers its questions. Those are
   * the settings somebody discovers a week later, from a run that stopped at three in the morning
   * with nobody watching, and they are exactly the kind a recipe should offer while the person is
   * already thinking about this flow.
   */
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

/**
 * Where a run goes when it stops for a person.
 *
 * <p>Shared, because it is the same question for every flow that can stop — and every flow here
 * can. Asked while somebody is still thinking about this flow rather than discovered a week later
 * from a run that waited all night for an answer nobody knew it wanted.
 */
const TELL_ME_WHEN_IT_STOPS: RecipeQuestion = {
  title: 'Where should it ask you?',
  fields: [
    {
      nodeId: FLOW,
      field: 'approvalSlackChannel',
      label: 'Slack channel (optional)',
      control: 'text',
      placeholder: 'C0123456789',
      help:
        'A ✅ there approves, and a reply in the thread becomes the run\'s next instruction. Needs a Slack token in the flow\'s settings. Leave it blank to answer in the app.',
    },
    {
      nodeId: FLOW,
      field: 'approvalTeamsWebhook',
      label: 'Teams webhook (optional)',
      control: 'text',
      placeholder: 'https://…webhook.office.com/…',
      help: 'Tells you, but cannot carry an answer back. Fine on its own if you just want to know.',
    },
  ],
}

/** Where a failure goes. The one thing nobody sets up until the night it would have mattered. */
const TELL_ME_WHEN_IT_BREAKS: RecipeQuestion = {
  title: 'Where should it shout if it fails?',
  fields: [
    {
      nodeId: FLOW,
      field: 'notifyWebhook',
      label: 'Failure webhook (optional)',
      control: 'text',
      placeholder: 'https://hooks.slack.com/services/…',
      help:
        'POSTed with the run and the reason whenever an execution fails. Any Slack-compatible endpoint.',
    },
  ],
}

export const RECIPES: Recipe[] = [
  {
    id: 'support-triage',
    title: 'Sort what is urgent from what can wait',
    blurb:
      'One classifier reads each request; the urgent path and the routine path are two branches of the same if — so no request can fall between them.',
    sampleId: 'support-triage-if-else',
    enableHint: 'It runs when you press Run — nothing fires on its own until you wire the two branches to real flows.',
    questions: [
      {
        title: 'What should count as urgent?',
        fields: [
          {
            nodeId: 'if-1',
            field: 'value',
            label: 'The word the classifier uses',
            control: 'text',
            placeholder: 'URGENT',
            help: 'The condition checks the classifier\'s verdict for this word. The else branch takes everything that does not carry it — the two branches cannot drift apart, because they are one test read from both sides.',
          },
        ],
      },
    ],
  },
  {
    id: 'report-with-net',
    title: 'A scheduled report that tells you when it breaks',
    blurb:
      'The report goes out on the main output; the branch on the error output runs only when something failed — so silence means it worked.',
    sampleId: 'report-with-fallback',
    enableHint: 'It runs on the schedule once started. Leave it off to open it on the canvas first.',
    questions: [
      {
        title: 'When should it run?',
        fields: [
          {
            nodeId: 'in-1',
            field: 'cron',
            label: 'Schedule',
            control: 'cron',
            help: 'Monday at 08:00 by default. The safety net costs nothing on the weeks everything works.',
          },
        ],
      },
    ],
  },
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
      TELL_ME_WHEN_IT_BREAKS,
    ],
  },
  {
    id: 'issue-triage',
    title: 'Triage every issue as it arrives',
    blurb:
      'A webhook from GitHub or Linear wakes the flow, which reads the issue, labels it, and says what it thinks should happen next.',
    sampleId: 'webhook-issue-triage',
    enableHint:
      'It answers a public URL once started. Leave it off until you have pasted that URL into the other side.',
    questions: [
      {
        title: 'What should it do with an issue?',
        fields: [
          {
            nodeId: 'agent-1',
            field: 'systemPrompt',
            control: 'textarea',
            label: 'House rules',
            placeholder:
              'Label by area (billing, auth, imports). Anything with a stack trace is a bug. Say who should look at it and why, in two sentences.',
            help:
              'What you would tell somebody on their first week doing this. The issue itself arrives as the message.',
          },
        ],
      },
      TELL_ME_WHEN_IT_STOPS,
    ],
  },
  {
    id: 'review-crew',
    title: 'Review every pull request before I do',
    blurb:
      'Several agents read the diff at once — correctness, security, tests — and one merges what they found into a single review.',
    sampleId: 'pr-review-crew',
    enableHint:
      'It runs when a pull request arrives, and a review costs tokens per agent. Open it first and see what it says on one you already know.',
    questions: [
      {
        title: 'Which repository?',
        fields: [
          {
            nodeId: 'repo-1',
            field: 'url',
            label: 'Repository',
            control: 'text',
            placeholder: 'https://github.com/tecnovent/facturacion',
            help: 'Cloned read-only when a run starts. A private one needs a credential under Resources → Credentials.',
          },
        ],
      },
      {
        title: 'What matters in this codebase?',
        fields: [
          {
            nodeId: 'agent-1',
            field: 'systemPrompt',
            control: 'textarea',
            label: 'What to look for',
            placeholder:
              'This is a billing service: money is in cents, never floats. Flag anything that writes to the ledger without a test.',
            help:
              'The things a competent stranger would not know. Generic review advice the model already has.',
          },
        ],
      },
      TELL_ME_WHEN_IT_STOPS,
    ],
  },
  {
    id: 'docs-from-code',
    title: 'Keep the docs honest about the code',
    blurb:
      'Reads the repository and rewrites the pages that no longer describe what it does, saying what changed.',
    sampleId: 'docs-from-code',
    enableHint: 'It reads a repository and writes with a model on every run; start it by hand first.',
    questions: [
      {
        title: 'Which repository?',
        fields: [
          {
            nodeId: 'repo-1',
            field: 'url',
            label: 'Repository',
            control: 'text',
            placeholder: 'https://github.com/tecnovent/facturacion',
            help: 'Cloned read-only when a run starts.',
          },
        ],
      },
      {
        title: 'Which docs, and for whom?',
        fields: [
          {
            nodeId: 'in-1',
            field: 'prompt',
            control: 'textarea',
            label: 'What to bring up to date',
            placeholder:
              'Update docs/api.md and docs/getting-started.md for someone integrating for the first time. Say what changed at the top.',
            help: 'Name the files, and who is reading them. Both change what comes back.',
          },
        ],
      },
      TELL_ME_WHEN_IT_BREAKS,
    ],
  },
  {
    id: 'quotes-to-holded',
    title: 'Turn quote requests in my mailbox into drafts',
    blurb:
      'Reads a request that arrived by mail, works out what is being asked for, and prepares the quote — stopping for your approval before anything is issued.',
    sampleId: 'outlook-quote-to-holded',
    enableHint:
      'It watches a mailbox and prepares real documents. Leave it off until you have watched one go through.',
    questions: [
      {
        title: 'Which mailbox?',
        fields: [
          {
            nodeId: 'in-1',
            field: 'mailAccount',
            label: 'Mail account',
            control: 'credential',
            help: 'The mailbox quote requests arrive in. Added under Resources → Credentials.',
          },
        ],
      },
      {
        title: 'How do you price?',
        fields: [
          {
            nodeId: 'agent-1',
            field: 'systemPrompt',
            control: 'textarea',
            label: 'Pricing rules',
            placeholder:
              'Catalogue prices, 21% VAT, 5% off above 5.000 €. Delivery is 3 weeks unless the customer says otherwise. Never invent a price — ask.',
            help:
              'The rules you would tell a new salesperson. "Never invent a price — ask" is worth writing down: it is what turns a wrong quote into a question.',
          },
        ],
      },
      TELL_ME_WHEN_IT_STOPS,
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

  // Answers about the flow itself — where it shouts, where it asks. Collected first so the object
  // below can carry them without a second pass.
  const flowPatch: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(answers)) {
    const field = byKey.get(key)
    if (!field || field.nodeId !== FLOW || value.trim() === '') continue
    flowPatch[field.field] = value.trim()
  }

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
    ...flowPatch,
  }
}

/** Stable key for an answer: a recipe may fill the same field name on two different nodes. */
export function fieldKey(field: RecipeField): string {
  return `${field.nodeId}.${field.field}`
}
