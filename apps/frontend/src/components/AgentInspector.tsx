import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { FacadeProfile, PluginInfo, SkillInfo } from '../api/types.ts'
import type { AgentNodeData, LibraryAgent } from '../api/types.ts'
import { EFFORT_OPTIONS } from '../constants.ts'
import { aboveCeiling } from '../utils/permissionCeiling.ts'
import { Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import { ModelField } from './ModelField.tsx'
import { PluginPicker } from './PluginPicker.tsx'
import { SkillPicker } from './SkillPicker.tsx'
import styles from './panels.module.scss'

interface Props {
  data: AgentNodeData
  set: (patch: Record<string, unknown>) => void
}

/**
 * The fields a library link governs — what the agent IS. Everything else on the block is what it
 * gets to use in this flow, and stays editable whether the block is linked or not.
 */
const LINKED_FIELDS = ['name', 'model', 'effort', 'maxTokens', 'systemPrompt', 'description'] as const
type LinkedField = (typeof LINKED_FIELDS)[number]

/** The six governed fields as the library agent has them today. */
function definitionOf(a: LibraryAgent): Pick<AgentNodeData, LinkedField> {
  return {
    name: a.name,
    model: a.model,
    effort: a.effort,
    maxTokens: a.maxTokens,
    systemPrompt: a.systemPrompt,
    description: a.description ?? '',
  }
}

/** Enough of a value to recognise it in a diff line; a whole system prompt would be the panel. */
function short(value: unknown): string {
  const s = String(value ?? '')
  return s.length > 60 ? `${s.slice(0, 57)}…` : s
}

export function AgentInspector({ data, set }: Props) {
  const { t } = useTranslation()
  const [library, setLibrary] = useState<LibraryAgent[]>([])
  // Whether the library answered. A linked block whose agent is not in the list is "deleted" only
  // once the list has actually arrived; before that, or after a failed fetch, it is "unknown", and
  // telling someone their agent is gone because the network blinked would send them unlinking.
  const [libraryState, setLibraryState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [skills, setSkills] = useState<SkillInfo[]>([])
  const [facades, setFacades] = useState<FacadeProfile[]>([])
  const [plugins, setPlugins] = useState<PluginInfo[]>([])
  // The organization's permission ceiling, or '' when there is none (or the policy is not
  // enforced): the modes above it are disabled below, because a run would clamp them anyway
  // and a picker that lets you choose what you will not get is a lie with a dropdown.
  const [ceiling, setCeiling] = useState('')

  useEffect(() => {
    api
      .listAgents()
      .then((v) => {
        setLibrary(v)
        setLibraryState('ready')
      })
      .catch(() => {
        setLibrary([])
        setLibraryState('failed')
      })
    api.listSkills().then(setSkills).catch(() => setSkills([]))
    api.listFacadeProfiles().then(setFacades).catch(() => setFacades([]))
    api
      .listPlugins()
      .then((v) => setPlugins(v.plugins))
      .catch(() => setPlugins([]))
    api
      .getOrgPolicy()
      .then((v) => setCeiling(v.enforced ? (v.policy.maxPermissionMode ?? '') : ''))
      .catch(() => setCeiling(''))
  }, [])

  const linked = !!data.libraryAgentId
  const linkedAgent = linked ? library.find((x) => x.id === data.libraryAgentId) : undefined
  const linkedVersion = linkedAgent?.version ?? 1
  // What the library changed since the block took its copy — by value, not only by version
  // number, so the list is the actual difference and an agent re-saved untouched shows none.
  const changes = linkedAgent
    ? LINKED_FIELDS.filter((f) => String(data[f] ?? '') !== String(definitionOf(linkedAgent)[f] ?? ''))
    : []
  const behind = !!linkedAgent && ((data.libraryVersion ?? 0) < linkedVersion || changes.length > 0)

  /** The old behaviour, still here: the fields are copied and nothing remembers where from. */
  const copyOnce = (id: string) => {
    const a = library.find((x) => x.id === id)
    if (!a) return
    set(definitionOf(a))
  }

  /**
   * A link: the id and the version, plus a copy of the fields so the card and this panel can show
   * them without a round trip. The run never reads the copy — the compiler resolves the library.
   */
  const linkTo = (id: string) => {
    const a = library.find((x) => x.id === id)
    if (!a) return
    set({ libraryAgentId: a.id, libraryVersion: a.version ?? 1, ...definitionOf(a) })
  }

  /** The copy the block already holds becomes its own; only the reference is dropped. */
  const unlink = () => {
    const kept: Record<string, unknown> = {}
    for (const f of LINKED_FIELDS) kept[f] = data[f] ?? ''
    set({ libraryAgentId: undefined, libraryVersion: undefined, ...kept })
  }

  const takeCurrent = () => {
    if (!linkedAgent) return
    set({ libraryVersion: linkedVersion, ...definitionOf(linkedAgent) })
  }

  const fieldLabel: Record<LinkedField, string> = {
    name: t('Name'),
    model: t('Model'),
    effort: t('Effort'),
    maxTokens: t('Max tokens'),
    systemPrompt: t('System prompt'),
    description: t('Delegate when… (routing)'),
  }

  return (
    <>
      {linked && (
        <div className={styles.libraryField}>
          <div className={styles.linkedHead}>
            <span
              className={styles.linkedChip}
              title={t("The block follows the library agent: name, model, effort, max tokens, system prompt and routing are read from the library at every run, so an edit under Resources → Agents reaches every flow that links it. Tools, skills, plugins, folders and the rest stay this block's own.")}
            >
              ⛓ {t('linked to library · v{{n}}', { n: data.libraryVersion ?? 1 })}
            </span>
            <strong>{linkedAgent?.name ?? data.name}</strong>
            <button type="button" className={styles.dup} onClick={unlink}>
              {t('Unlink (keep a copy)')}
            </button>
          </div>
          {libraryState === 'ready' && !linkedAgent && (
            <p className={styles.previewErr}>
              {t('This library agent no longer exists ({{id}}). A run refuses to start until the block is unlinked — its copy of the fields stays — or linked to another agent.', { id: data.libraryAgentId })}
            </p>
          )}
          {behind && linkedAgent && (
            <div className={styles.linkedDiff}>
              {t('The library agent changed since this block linked it (v{{from}} → v{{to}}). The next run uses the new version; take it to refresh the copy this block shows.', { from: data.libraryVersion ?? 1, to: linkedVersion })}
              {changes.length > 0 ? (
                <ul>
                  {changes.map((f) => (
                    <li key={f}>
                      <b>{fieldLabel[f]}</b>: <s>{short(data[f])}</s> → {short(definitionOf(linkedAgent)[f])}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className={styles.hint}>{t('No field differs — the library agent was saved again without changes.')}</p>
              )}
              <button type="button" className={styles.dup} onClick={takeCurrent}>
                {t('Take the current version')}
              </button>
            </div>
          )}
          {linkedAgent && !behind && (
            <p className={styles.hint}>
              {t('These fields come from the library agent. Unlink the block to edit them here, or edit the agent under Resources → Agents.')}
            </p>
          )}
        </div>
      )}

      {!linked && library.length > 0 && (
        <div className={styles.libraryField}>
          <SelectField
            label={
              <span title={t("The block follows the library agent: name, model, effort, max tokens, system prompt and routing are read from the library at every run, so an edit under Resources → Agents reaches every flow that links it. Tools, skills, plugins, folders and the rest stay this block's own.")}>
                {t('Link to a library agent ⓘ')}
              </span>
            }
            value=""
            onChange={linkTo}
          >
            <option value="">{t('— choose an agent —')}</option>
            {library.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.model})
              </option>
            ))}
          </SelectField>
          <SelectField
            label={
              <span title={t('Copies the definition fields onto this block once and forgets where they came from — the way "Load from library" always worked. Later edits to the library agent do not reach this block.')}>
                {t('Copy fields once ⓘ')}
              </span>
            }
            value=""
            onChange={copyOnce}
          >
            <option value="">{t('— choose an agent —')}</option>
            {library.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.model})
              </option>
            ))}
          </SelectField>
        </div>
      )}

      <Field label={t('Name')} value={data.name} readOnly={linked} onChange={(v) => set({ name: v })} />
      <ModelField value={data.model} readOnly={linked} onChange={(v) => set({ model: v })} />
      {data.kind === 'agent' && (
        <TextArea
          label={t('Delegate when… (routing)')}
          rows={3}
          placeholder={t('Use PROACTIVELY for backend/Java work. Give it only the backend part of the plan.')}
          value={data.description ?? ''}
          readOnly={linked}
          onChange={(v) => set({ description: v })}
        />
      )}
      {skills.length > 0 && (
        <SkillPicker
          skills={skills}
          selectedIds={data.skillIds ?? []}
          onChange={(skillIds) => set({ skillIds })}
        />
      )}
      {plugins.length > 0 && (
        <PluginPicker
          plugins={plugins}
          selectedIds={data.plugins ?? []}
          onChange={(ids) => set({ plugins: ids })}
        />
      )}
      <TextArea
        label={t('System prompt')}
        rows={6}
        value={data.systemPrompt}
        readOnly={linked}
        onChange={(v) => set({ systemPrompt: v })}
      />

      <FineTuning>
        <SelectField label={t('Effort')} value={data.effort} readOnly={linked} onChange={(v) => set({ effort: v })}>
          {EFFORT_OPTIONS.map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </SelectField>
        <Field
          label={t('Max tokens')}
          type="number"
          value={data.maxTokens}
          readOnly={linked}
          onChange={(v) => set({ maxTokens: Number(v) })}
        />
        {data.kind === 'agent' && (
          <>
            <Field
              label={
                <span title={t("Independent workers only. How many extra launches this block gets after its process fails (a crash, a bad exit — not a timeout, which is never retried, and not a verifier rejection, which is what the escalation model is for). Blank uses the deployment's default; 0 means one attempt and no more. The run log says which attempt each launch was.")}>
                  {t('Retries after a failure (blank = default) ⓘ')}
                </span>
              }
              type="number"
              value={data.retries ?? ''}
              onChange={(v) => set({ retries: v === '' ? undefined : Math.max(0, Number(v)) })}
            />
            <Field
              label={
                <span title={t("Enforced per sub-agent by Claude Code, whatever the flow's permission mode allows. Tool names as the CLI knows them: Read, Edit, Write, Bash, WebFetch, WebSearch… Blank inherits all tools.")}>
                  {t('Allowed tools (blank = all) ⓘ')}
                </span>
              }
              placeholder={t('Read, Grep, Glob')}
              value={(data.tools ?? []).join(', ')}
              onChange={(v) => set({ tools: v.split(',').map((t) => t.trim()).filter(Boolean) })}
            />
            <SelectField
              label={
                <span title={t('Used when the flow runs as independent workers: everything this worker reaches over MCP goes through this profile — allowlist, read-only, dry-run — enforced by the backend on every call, not suggested in its instructions. Leave it empty and the worker reaches the servers wired to this node with nothing filtered, writes included, which is the same reach it has as a sub-agent. Define profiles under Resources → Facades.')}>
                  {t('Facade profile (independent workers) ⓘ')}
                </span>
              }
              value={data.facadeProfileId ?? ''}
              onChange={(v) => set({ facadeProfileId: v })}
            >
              <option value="">{t('— none: everything wired to this node —')}</option>
              {facades.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                  {p.readOnly ? ` ${t('(read-only)')}` : (p.dryRun ?? true) ? ` ${t('(dry-run writes)')}` : ''}
                </option>
              ))}
            </SelectField>
            {/* Cheap first, made safe: it only escalates on a verifier REJECTION, which is the
                one signal that says the answer was actually wrong. Workers only — they are the
                single path with a verifier to reject anything. */}
            <ModelField
              label={
                <span title={t("Independent workers only, and only with a Verifier node in the flow: if the verifier rejects this worker's output, it runs once more on this model and is judged again. Without a verifier nothing escalates — there would be no signal saying the cheap answer was wrong. Both attempts' tokens land on this box, priced at the escalation model.")}>
                  {t('Escalation model (blank = off) ⓘ')}
                </span>
              }
              value={data.fallbackModelId ?? ''}
              onChange={(v) => set({ fallbackModelId: v })}
              allowNone
            />
          </>
        )}

        {/* Coordinator only, and not for tidiness: a local run launches one `claude` process for
            the whole flow and --permission-mode applies to that process. Offering it per sub-agent
            would be a control that silently does nothing, or worse, four controls that contradict. */}
        {data.kind === 'coordinator' && (
          <>
            <SelectField
              label={
                <span title={t('Applies to the whole run: one claude process executes the coordinator and every sub-agent. Bypass is the only mode that works unattended; Ask and Auto-accept stall without someone at the keyboard; Plan proposes without changing anything.')}>
                  {t("Permissions for this flow's agents ⓘ")}
                </span>
              }
              value={data.permissionMode ?? ''}
              onChange={(v) => set({ permissionMode: v })}
            >
              <option value="">{t('Default (bypass — no prompts)')}</option>
              <option value="approval">{t('Ask me to approve the plan, then act')}</option>
              <option value="plan" disabled={aboveCeiling('plan', ceiling)}>
                {t('Plan only — proposes, changes nothing')}
              </option>
              <option value="default" disabled={aboveCeiling('default', ceiling)}>
                {t('Ask — prompts before each sensitive action')}
              </option>
              <option value="acceptEdits" disabled={aboveCeiling('acceptEdits', ceiling)}>
                {t('Auto-accept file edits, ask for the rest')}
              </option>
              <option value="bypassPermissions" disabled={aboveCeiling('bypassPermissions', ceiling)}>
                {t('Bypass all checks')}
              </option>
            </SelectField>
            {ceiling && (
              <p className={styles.hint}>
                {t('Organization policy caps this at')} <code>{ceiling}</code>.{' '}
                {t(
                  'Modes above it are disabled; a run asking for more — the deployment default and an approved plan included — gets the ceiling instead, and its log says so.',
                )}
              </p>
            )}
            <SelectField
              label={
                <span title={t('Subagents: one claude process runs the whole flow; sub-agents share its session, its folders and its MCP list, and run one at a time. Independent workers: one claude process per worker — own workspace and instructions, own model, real parallelism; workers cannot delegate or run shell commands (a Merge node runs the checks). Drawn sub-agents are the plan; with none drawn, the coordinator runs read-only first and submits a plan (plan_submit), and each item becomes a worker. Workers reach MCP through the facade endpoint always; a profile narrows that to an allowlist, read-only or simulated writes, and without one nothing is filtered. Repositories wired to a worker are cloned into its workspace; its changes reach the merge step as patches, and the merge commits and opens the pull request.')}>
                  {t('Execution ⓘ')}
                </span>
              }
              value={data.execution ?? ''}
              onChange={(v) => set({ execution: v })}
            >
              <option value="fanout">{t('Independent workers — one process per sub-agent')}</option>
              <option value="">{t('Subagents — one shared session (legacy)')}</option>
            </SelectField>
            {data.execution === 'fanout' && (
              <SelectField
                label={
                  <span title={t("What the coordinator's own process may do when it runs (it runs only when planning, i.e. with no sub-agents drawn). Auto: read-only exactly when sub-agents are wired to it — a coordinator with workers distributes, a solo one is doing the work and may act. Force either shape here. Delegation is denied in every case, so the fan-out stays one level deep.")}>
                    {t('Coordinator access ⓘ')}
                  </span>
                }
                value={data.coordinatorAccess ?? ''}
                onChange={(v) => set({ coordinatorAccess: v })}
              >
                <option value="">{t('Auto — read-only only if it has workers wired')}</option>
                <option value="read-only">{t('Read-only always — plans, never touches anything')}</option>
                <option value="may-act">{t('May act — can edit files and run commands')}</option>
              </SelectField>
            )}
            <SelectField
              label={
                <span title={t("Anthropic meters non-interactive Claude Code use — the way Concentus runs the CLI — in its own weekly allowance. When the Usage page says it is spent, or the CLI refuses a run for it mid-way, this is what the run does. The API key is billed per token; a local model runs every agent of the flow on that model (Resources → Models). Blank: the run is told and tries anyway.")}>
                  {t('When the weekly allowance is spent ⓘ')}
                </span>
              }
              value={data.allowanceFallback ?? ''}
              onChange={(v) => set({ allowanceFallback: v })}
            >
              <option value="">{t('Say so, and keep trying on the subscription')}</option>
              <option value="api-key">{t('Run on the API key (billed per token)')}</option>
              <option value="local-model">{t('Run every agent on a local model')}</option>
            </SelectField>
            {data.allowanceFallback === 'local-model' && (
              <ModelField
                label={t('Fallback model')}
                value={data.allowanceFallbackModel ?? ''}
                onChange={(v) => set({ allowanceFallbackModel: v })}
                allowNone
              />
            )}
          </>
        )}
        <TextArea
          label={
            <span title={t('Folders this agent treats as its source of truth. Each path must sit under local.context-roots on the backend; rejected paths are reported in the run console. Cloud runs never touch your machine.')}>
              {t('Context folders (one per line) ⓘ')}
            </span>
          }
          rows={3}
          placeholder={t('C:\\Users\\me\\code\\wirej\nC:\\Users\\me\\code\\concentus')}
          value={(data.contextFolders ?? []).join('\n')}
          // Split on write, not on every keystroke's trimmed value — otherwise a half-typed
          // line vanishes as soon as it is momentarily blank.
          onChange={(v) => set({ contextFolders: v.split('\n').map((s) => s.trim()).filter(Boolean) })}
        />
        <Field
          label={
            <span title={t('An existing CLAUDE.md (or a folder holding one) loaded as project context for this agent.')}>
              {t('CLAUDE.md path (file or folder) ⓘ')}
            </span>
          }
          placeholder={t('C:\\Users\\me\\code\\wirej')}
          value={data.claudeMdPath ?? ''}
          onChange={(v) => set({ claudeMdPath: v })}
        />
      </FineTuning>
    </>
  )
}
