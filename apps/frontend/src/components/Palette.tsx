import { useTranslation } from 'react-i18next'
import { useFlowStore } from '../state/store.ts'
import { cx } from '../utils/cx.ts'
import styles from './panels.module.scss'

/** Carries the node kind to the canvas. A private type, so a drag from elsewhere is ignored. */
export const NODE_DRAG_TYPE = 'application/concentus-node'

export function Palette() {
  const { t } = useTranslation()
  const addNode = useFlowStore((s) => s.addNode)
  const hasCoordinator = useFlowStore((s) => s.nodes.some((n) => n.data.kind === 'coordinator'))
  // Dragging places the node where it is dropped; clicking still adds one, because a drag is not
  // available to keyboard users and losing that would be a regression for them.
  const drag = (kind: string) => ({
    draggable: true,
    onDragStart: (e: React.DragEvent) => {
      e.dataTransfer.setData(NODE_DRAG_TYPE, kind)
      e.dataTransfer.effectAllowed = 'copy'
    },
  })
  return (
    <aside className={styles.palette}>
      <h3 className={styles.h3}>{t('Add node')}</h3>
      <button className={cx(styles.addBtn, styles.addInput)} title={t('How a run starts: manual, a fixed prompt, cron, webhook, or incoming mail.')} {...drag('input')} onClick={() => addNode('input')}>
        <span>▶</span> {t('Input / trigger')}
      </button>
      {/* Two blocks, not one with a dropdown: a flow has exactly one lead, and the palette says so
          by refusing a second rather than by letting two boxes claim the role. */}
      <button
        className={cx(styles.addBtn, styles.addCoordinator)}
        title={
          hasCoordinator
            ? t('A flow has one coordinator, and this one already does.')
            : t("The agent the trigger addresses: it receives the prompt, delegates to the agents wired to it and writes the run's final answer. Exactly one per flow.")
        }
        disabled={hasCoordinator}
        {...(hasCoordinator ? {} : drag('coordinator'))}
        onClick={() => addNode('coordinator')}
      >
        <span>★</span> {t('Coordinator')}
      </button>
      <button className={cx(styles.addBtn, styles.addAgent)} title={t("An agent the coordinator may delegate to. Wire it to the coordinator (or behind another agent, for a chain); its 'Delegate when…' text is what routes work to it.")} {...drag('agent')} onClick={() => addNode('agent')}>
        <span>◆</span> {t('Agent')}
      </button>
      <button className={cx(styles.addBtn, styles.addVerifier)} title={t("For flows running as independent workers: an adversarial judge that runs after every worker and BEFORE the merge. Its objective is inverted — find the reason each output should be REJECTED — and its verdict has teeth: a rejected output never reaches the merge. Reads the workers' files, cannot edit or run commands. Its 'on rejected' output hands a flow the full verification report when anything was rejected. At most one per flow; ignored on subagents execution.")} {...drag('verifier')} onClick={() => addNode('verifier')}>
        <span>⚖</span> {t('Verifier')}
      </button>
      <button className={cx(styles.addBtn, styles.addMerge)} title={t('For flows running as independent workers: one more claude process that runs AFTER every worker, reconciles their reports into the final answer, and runs the verification commands (tests, diffs) the workers cannot. At most one per flow; ignored on subagents execution.')} {...drag('merge')} onClick={() => addNode('merge')}>
        <span>⛙</span> {t('Merge')}
      </button>
      <button className={cx(styles.addBtn, styles.addMcp)} title={t('Connect to the agent that should use its tools. Direction of the wire does not matter.')} {...drag('mcp')} onClick={() => addNode('mcp')}>
        <span>⚙</span> {t('MCP server')}
      </button>
      <button className={cx(styles.addBtn, styles.addRepo)} title={t('Clones the repository into the run and enables PRs/MRs. Connect it to an agent.')} {...drag('repo')} onClick={() => addNode('repo')}>
        <span>🐙</span> {t('Repository')}
      </button>
      <button className={cx(styles.addBtn, styles.addSql)} title={t('Runs a SQL query at run start and injects the rows into the connected agent.')} {...drag('sql')} onClick={() => addNode('sql')}>
        <span>🗄</span> {t('SQL source (RAG)')}
      </button>
      <button className={cx(styles.addBtn, styles.addApi)} title={t('Turn a REST API into typed tools. Either load an OpenAPI spec and tick the operations the agent may call, or type a single endpoint — a webhook, an internal service, one endpoint of an API whose document you do not have.')} {...drag('api')} onClick={() => addNode('api')}>
        <span>🌐</span> {t('API / endpoint')}
      </button>
      <button className={cx(styles.addBtn, styles.addFlow)} title={t('Runs another saved flow. Wired to an agent it is a tool the agent may call and wait on; left unconnected it fires when this flow finishes.')} {...drag('flow')} onClick={() => addNode('flow')}>
        <span>🔗</span> {t('Run another flow')}
      </button>
      <button className={cx(styles.addBtn, styles.addMail)} title={t("Sends a mail when this flow finishes. Wire it out of a block's output: from the main output it sends the run's final answer; from a block's 'on error' output it sends that block's failure and log; from the verifier's 'on rejected' output it sends the verification report. What arrives on the wire is the body — there is nothing to write.")} {...drag('mail')} onClick={() => addNode('mail')}>
        <span>✉</span> {t('Send mail')}
      </button>
      <button className={cx(styles.addBtn, styles.addKnowledge)} title={t("Injects the passages most relevant to the run's prompt from a document base (Resources → Knowledge).")} {...drag('knowledge')} onClick={() => addNode('knowledge')}>
        <span>📚</span> {t('Knowledge base')}
      </button>
      <button className={cx(styles.addBtn, styles.addCondition)} title={t("Draw it between an agent and what it hands off to: the branch runs only when the agent's answer passes the test. The same rule written into a prompt is a request; measured here it is a rule, and the run log says which way it went.")} {...drag('condition')} onClick={() => addNode('condition')}>
        <span>⑂</span> {t('Condition')}
      </button>
      <button className={cx(styles.addBtn, styles.addForEach)} title={t("Runs the branch behind it once per item of the agent's answer — one item per line, or a JSON array. Put a condition after it to filter the items.")} {...drag('foreach')} onClick={() => addNode('foreach')}>
        <span>⟳</span> {t('For each')}
      </button>
      {/* Annotations. Last, and greyed: they are for the person reading the canvas, and the
          run never sees them. */}
      <button className={cx(styles.addBtn, styles.addNote)} title={t('A sticky note for whoever reads this flow next. Four colours, no wires — the run never sees it.')} {...drag('note')} onClick={() => addNode('note')}>
        <span>✎</span> {t('Note')}
      </button>
      <button className={cx(styles.addBtn, styles.addGroup)} title={t('A labelled frame. Drop blocks inside and they move with it; drag its corners to resize. Decoration only — the run ignores it.')} {...drag('group')} onClick={() => addNode('group')}>
        <span>▭</span> {t('Group')}
      </button>

      <p
        className={styles.hint}
        title={t('Move: drag. Delete a wire: hover it and click ×, or select it and press Delete. Copy/paste: Ctrl/Cmd+C / V — works across flows. Ctrl/Cmd+D duplicates in place. Shift-drag selects several; wires between copied blocks are kept.')}
      >
        {t('Hover any button or field for help · shortcuts ⓘ')}
      </p>

    </aside>
  )
}
