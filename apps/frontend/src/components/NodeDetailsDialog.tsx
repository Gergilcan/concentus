import { useTranslation } from 'react-i18next'
import type { AppNodeData } from '../api/types.ts'
import { Inspector } from './Inspector.tsx'
import { Modal } from './Modal.tsx'
import { useFlowStore } from '../state/store.ts'
import flows from './flows.module.scss'
import panels from './panels.module.scss'

/**
 * What to call the block on the dialog's head.
 *
 * <p>Agents carry `name` and every other kind carries `label` — a split that predates this and is
 * not worth unifying from here.
 */
const KIND_NAMES: Record<string, string> = {
  input: 'Input / trigger',
  agent: 'Agent',
  verifier: 'Verifier',
  merge: 'Merge',
  mcp: 'MCP server',
  repo: 'Repository',
  sql: 'SQL source',
  api: 'API endpoint',
  flow: 'Sub-flow',
  knowledge: 'Knowledge base',
  condition: 'Condition',
  foreach: 'For each',
  note: 'Note',
  group: 'Group',
}

function blockName(data: AppNodeData, t: (key: string) => string): string {
  if ('name' in data && data.name) return data.name
  if ('label' in data && data.label) return data.label
  // Its kind, never the word "Block". Input, Condition and For each carry neither a name nor a
  // label, so the most prominent line on the dialog was the only one saying nothing — while the
  // line directly beneath it already read INPUT / TRIGGER.
  return t(KIND_NAMES[data.kind] ?? 'Block')
}

/**
 * The selected block's properties, in a dialog.
 *
 * <p>Opened by double-clicking a node, which is the gesture people already try on a diagram when
 * they mean "open this properly". It renders the same {@link Inspector} the side panel does —
 * not a second editor with its own copy of every field, which would be two places to fix a bug
 * and two places to forget a new node kind.
 *
 * <p>Titled with the block's own name rather than its type: the dialog head answers "which one",
 * the inspector's head right below it answers "what kind", and those are different questions.
 *
 * <p>Rendered only for a node that exists, which is what closes it when the Delete button inside
 * it removes the very thing it was showing.
 */
export function NodeDetailsDialog() {
  const { t } = useTranslation()
  const open = useFlowStore((s) => s.detailsOpen)
  const close = useFlowStore((s) => s.closeNodeDetails)
  const selectedId = useFlowStore((s) => s.selectedId)
  const node = useFlowStore((s) => s.nodes.find((n) => n.id === s.selectedId) ?? null)
  // A box the coordinator's plan created for one run: it is on the canvas and it is worth
  // opening — its input, output and cost are real — but it was never drawn, so it is not in
  // `nodes`. Requiring a stored node would have made those boxes silently unopenable.
  const worker = useFlowStore((s) => (s.selectedId ? s.runExecByNode[s.selectedId] : undefined))

  if (!open || !selectedId) return null
  if (!node && !worker) return null

  const title = node ? blockName(node.data, t) : worker?.label || selectedId

  return (
    <Modal title={title} onClose={close} wide className={flows.modalTall}>
      <div className={panels.inspectorDialog}>
        <Inspector />
      </div>
    </Modal>
  )
}
