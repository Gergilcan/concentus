import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { FacadeProfile, SkillInfo } from '../api/types.ts'
import type { AgentNodeData, LibraryAgent } from '../api/types.ts'
import { EFFORT_OPTIONS } from '../constants.ts'
import { Field, SelectField, TextArea } from './fields.tsx'
import { ModelField } from './ModelField.tsx'
import styles from './panels.module.scss'

interface Props {
  data: AgentNodeData
  set: (patch: Record<string, unknown>) => void
}

export function AgentInspector({ data, set }: Props) {
  const [library, setLibrary] = useState<LibraryAgent[]>([])
  const [skills, setSkills] = useState<SkillInfo[]>([])
  const [facades, setFacades] = useState<FacadeProfile[]>([])

  useEffect(() => {
    api
      .listAgents()
      .then(setLibrary)
      .catch(() => setLibrary([]))
    api.listSkills().then(setSkills).catch(() => setSkills([]))
    api.listFacadeProfiles().then(setFacades).catch(() => setFacades([]))
  }, [])

  const applyLibrary = (id: string) => {
    const a = library.find((x) => x.id === id)
    if (!a) return
    set({
      name: a.name,
      model: a.model,
      effort: a.effort,
      maxTokens: a.maxTokens,
      systemPrompt: a.systemPrompt,
      description: a.description ?? '',
    })
  }

  return (
    <>
      {library.length > 0 && (
        <SelectField label="Load from library" value="" onChange={applyLibrary} className={styles.libraryField}>
          <option value="">— choose an agent —</option>
          {library.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name} ({a.model})
            </option>
          ))}
        </SelectField>
      )}

      <Field label="Name" value={data.name} onChange={(v) => set({ name: v })} />
      <SelectField label="Role" value={data.role} onChange={(v) => set({ role: v })}>
        <option value="coordinator">coordinator</option>
        <option value="subagent">subagent</option>
      </SelectField>
      <ModelField value={data.model} onChange={(v) => set({ model: v })} />
      <SelectField label="Effort" value={data.effort} onChange={(v) => set({ effort: v })}>
        {EFFORT_OPTIONS.map((v) => (
          <option key={v} value={v}>
            {v}
          </option>
        ))}
      </SelectField>
      <Field
        label="Max tokens"
        type="number"
        value={data.maxTokens}
        onChange={(v) => set({ maxTokens: Number(v) })}
      />
      {data.role === 'subagent' && (
        <>
          <TextArea
            label="Delegate when… (routing)"
            rows={3}
            placeholder="Use PROACTIVELY for backend/Java work. Give it only the backend part of the plan."
            value={data.description ?? ''}
            onChange={(v) => set({ description: v })}
          />
          <Field
            label={
              <span title="Enforced per sub-agent by Claude Code, whatever the flow's permission mode allows. Tool names as the CLI knows them: Read, Edit, Write, Bash, WebFetch, WebSearch… Blank inherits all tools.">
                Allowed tools (blank = all) ⓘ
              </span>
            }
            placeholder="Read, Grep, Glob"
            value={(data.tools ?? []).join(', ')}
            onChange={(v) => set({ tools: v.split(',').map((t) => t.trim()).filter(Boolean) })}
          />
          <SelectField
            label={
              <span title="Used when the flow runs as independent workers: everything this worker reaches over MCP goes through this profile — allowlist, read-only, dry-run — enforced by the backend on every call. Without one, a worker with MCP nodes gets NO MCP tools. Define profiles under Resources → Facades.">
                Facade profile (independent workers) ⓘ
              </span>
            }
            value={data.facadeProfileId ?? ''}
            onChange={(v) => set({ facadeProfileId: v })}
          >
            <option value="">— none: no MCP tools as a worker —</option>
            {facades.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
                {p.readOnly ? ' (read-only)' : (p.dryRun ?? true) ? ' (dry-run writes)' : ''}
              </option>
            ))}
          </SelectField>
        </>
      )}

      {/* Coordinator only, and not for tidiness: a local run launches one `claude` process for the
          whole flow and --permission-mode applies to that process. Offering it per sub-agent would
          be a control that silently does nothing, or worse, four controls that contradict. */}
      {data.role === 'coordinator' && (
        <>
          <SelectField
            label={
              <span title="Applies to the whole run: one claude process executes the coordinator and every sub-agent. Bypass is the only mode that works unattended; Ask and Auto-accept stall without someone at the keyboard; Plan proposes without changing anything.">
                Permissions for this flow's agents ⓘ
              </span>
            }
            value={data.permissionMode ?? ''}
            onChange={(v) => set({ permissionMode: v })}
          >
            <option value="">Default (bypass — no prompts)</option>
            <option value="approval">Ask me to approve the plan, then act</option>
            <option value="plan">Plan only — proposes, changes nothing</option>
            <option value="default">Ask — prompts before each sensitive action</option>
            <option value="acceptEdits">Auto-accept file edits, ask for the rest</option>
            <option value="bypassPermissions">Bypass all checks</option>
          </SelectField>
          <SelectField
            label={
              <span title="Subagents: one claude process runs the whole flow; sub-agents share its session, its folders and its MCP list, and run one at a time. Independent workers: one claude process per worker — own workspace and instructions, own model, real parallelism; workers cannot delegate or run shell commands (a Merge node runs the checks). Drawn sub-agents are the plan; with none drawn, the coordinator runs read-only first and submits a plan (plan_submit), and each item becomes a worker. Workers reach MCP only through a facade profile. Repositories are not cloned into workers yet.">
                Execution ⓘ
              </span>
            }
            value={data.execution ?? ''}
            onChange={(v) => set({ execution: v })}
          >
            <option value="">Subagents — one shared session</option>
            <option value="fanout">Independent workers — one process per sub-agent (experimental)</option>
          </SelectField>
        </>
      )}
      {skills.length > 0 && (
        <div
          className={styles.field}
          title="Installed under Resources → Skills. Assigned skills are put into the run's workspace and this agent is told they are its own — Claude Code does the discovering."
        >
          <span>Skills ⓘ</span>
          {skills.map((sk) => (
            <label key={sk.id} className={styles.checkField} title={sk.description}>
              <input
                type="checkbox"
                checked={(data.skillIds ?? []).includes(sk.id)}
                onChange={() => {
                  const has = (data.skillIds ?? []).includes(sk.id)
                  set({
                    skillIds: has
                      ? (data.skillIds ?? []).filter((id) => id !== sk.id)
                      : [...(data.skillIds ?? []), sk.id],
                  })
                }}
              />
              {sk.name}
            </label>
          ))}
        </div>
      )}
      <TextArea label="System prompt" rows={6} value={data.systemPrompt} onChange={(v) => set({ systemPrompt: v })} />

      <TextArea
        label={
          <span title="Folders this agent treats as its source of truth. Each path must sit under local.context-roots on the backend; rejected paths are reported in the run console. Cloud runs never touch your machine.">
            Context folders (one per line) ⓘ
          </span>
        }
        rows={3}
        placeholder={'C:\\Users\\me\\code\\wirej\nC:\\Users\\me\\code\\concentus'}
        value={(data.contextFolders ?? []).join('\n')}
        // Split on write, not on every keystroke's trimmed value — otherwise a half-typed
        // line vanishes as soon as it is momentarily blank.
        onChange={(v) => set({ contextFolders: v.split('\n').map((s) => s.trim()).filter(Boolean) })}
      />
      <Field
        label={
          <span title="An existing CLAUDE.md (or a folder holding one) loaded as project context for this agent.">
            CLAUDE.md path (file or folder) ⓘ
          </span>
        }
        placeholder="C:\Users\me\code\wirej"
        value={data.claudeMdPath ?? ''}
        onChange={(v) => set({ claudeMdPath: v })}
      />
    </>
  )
}
