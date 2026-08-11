import { useState } from 'react'
import { api } from '../api/client.ts'
import type { RemoteRepo, RepoNodeData } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { CredentialField } from './CredentialField.tsx'
import { CheckboxField, Field, FineTuning, SelectField } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: RepoNodeData
  set: (patch: Record<string, unknown>) => void
}

export function RepoInspector({ data, set }: Props) {
  const [repos, setRepos] = useState<RemoteRepo[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const isGitlab = data.provider === 'gitlab'
  // Which mode the node is in is derived from what it has, not stored separately — a node with a
  // group set *is* a group node, so the two can never disagree. Only the initial state of this
  // toggle is local, for the moment between choosing "a whole group" and typing its name.
  const [groupMode, setGroupMode] = useState((data.group ?? '').trim() !== '')
  const group = (data.group ?? '').trim()
  const only = data.only ?? []

  const browse = async (group: string) => {
    setBusy(true)
    setError(null)
    setRepos(null)
    try {
      const r = await api.listGroupRepos(data.provider, group, data.credentialId, data.baseUrl)
      if (!r.ok) setError(r.error ?? 'Could not list repositories.')
      setRepos(r.repos)
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  // Picking fills in both the URL and the branch: a repo whose default is `develop` would
  // otherwise be cloned at `main` and fail, with nothing on screen explaining why.
  const pickOne = (r: RemoteRepo) => {
    set({ url: r.cloneUrl, branch: r.defaultBranch, group: '', only: [] })
    setRepos(null)
  }

  const toggle = (r: RemoteRepo) => {
    const next = only.includes(r.fullName)
      ? only.filter((n) => n !== r.fullName)
      : [...only, r.fullName]
    set({ only: next })
  }

  return (
    <>
      <SelectField label="Provider" value={data.provider} onChange={(v) => set({ provider: v })}>
        <option value="github">github</option>
        <option value="gitlab">gitlab</option>
      </SelectField>

      <SelectField
        label="Scope"
        value={groupMode ? 'group' : 'repo'}
        onChange={(v) => {
          setGroupMode(v === 'group')
          // Clearing the other side keeps a node from being half one thing and half the other.
          set(v === 'group' ? { url: '' } : { group: '', only: [] })
          setRepos(null)
        }}
      >
        <option value="repo">One repository</option>
        <option value="group">
          {isGitlab ? 'A whole GitLab group' : 'A whole GitHub organization'}
        </option>
      </SelectField>

      <CredentialField
        label="Provider token"
        value={data.credentialId}
        onChange={(v) => set({ credentialId: v })}
        what="this repository"
      />

      {groupMode ? (
        <>
          <Field
            label={isGitlab ? 'Group path' : 'Organization'}
            value={group}
            placeholder={isGitlab ? 'acme/backend' : 'acme'}
            onChange={(v) => set({ group: v })}
          />
          <p className={styles.hint}>
            Every repository in the {isGitlab ? 'group' : 'organization'} is cloned, resolved when
            the run starts — so a repository added next week is picked up without editing this flow.
            {isGitlab && ' Subgroups are included.'}
          </p>

          <div className={styles.mcpBtns}>
            <button
              className={styles.previewBtn}
              onClick={() => void browse(group)}
              disabled={busy || group === ''}
            >
              {busy ? 'Listing…' : only.length > 0 ? 'Edit selection' : 'Select specific repositories'}
            </button>
          </div>
          <p className={styles.hint}>
            {only.length === 0 ? (
              <>
                <b>All repositories</b> in this {isGitlab ? 'group' : 'organization'}. Tick some
                below to narrow it down.
              </>
            ) : (
              <>
                <b>{only.length} selected:</b> {only.join(', ')}.{' '}
                <button className={styles.linkBtn} onClick={() => set({ only: [] })}>
                  use all instead
                </button>
              </>
            )}
          </p>
        </>
      ) : (
        <>
          <Field
            label="URL"
            value={data.url}
            placeholder="https://github.com/owner/repo"
            onChange={(v) => set({ url: v })}
          />
          <p className={styles.hint}>
            <b>Browse a {isGitlab ? 'group' : 'organization'}</b> instead of pasting a URL. Uses the
            token above.
          </p>
          <Field
            label={isGitlab ? 'Group path' : 'Organization'}
            value={group}
            placeholder={isGitlab ? 'acme/backend' : 'acme'}
            onChange={(v) => set({ group: v })}
          />
          <div className={styles.mcpBtns}>
            <button
              className={styles.previewBtn}
              onClick={() => void browse(group)}
              disabled={busy || group === ''}
            >
              {busy ? 'Listing…' : 'List repositories'}
            </button>
          </div>
          {isGitlab && (
            <p className={styles.hint}>
              Subgroups are included, so a top-level group lists everything beneath it.
            </p>
          )}
        </>
      )}

      {error && <p className={styles.hint}>{error}</p>}
      {repos && repos.length === 0 && !error && (
        <p className={styles.hint}>No repositories found there.</p>
      )}
      {repos && repos.length > 0 && (
        <div className={styles.repoList}>
          {repos.map((r) => (
            <button
              key={r.fullName}
              className={styles.repoItem}
              onClick={() => (groupMode ? toggle(r) : pickOne(r))}
            >
              <span>
                {groupMode && (only.includes(r.fullName) ? '☑ ' : '☐ ')}
                {r.fullName}
              </span>
              <span className={styles.repoMeta}>
                {r.defaultBranch}
                {r.archived ? ' · archived' : ''}
              </span>
            </button>
          ))}
        </div>
      )}

      <Field
        label={groupMode ? 'Branch (blank = each repo’s default)' : 'Branch'}
        value={data.branch}
        placeholder={groupMode ? 'leave blank' : 'main'}
        onChange={(v) => set({ branch: v })}
      />

      <FineTuning>
        <Field
          label="Server URL (self-hosted only)"
          value={data.baseUrl ?? ''}
          placeholder={isGitlab ? 'https://gitlab.empresa.com' : 'https://github.empresa.com/api/v3'}
          onChange={(v) => set({ baseUrl: v })}
        />
        {groupMode && (
          <CheckboxField
            label="Include archived repositories"
            checked={data.includeArchived ?? false}
            onChange={(v) => set({ includeArchived: v })}
          />
        )}
        <Field
          label="Mount path"
          value={data.mountPath}
          placeholder={groupMode ? '/workspace' : '/workspace/repo'}
          onChange={(v) => set({ mountPath: v })}
        />
      </FineTuning>

      <p className={styles.hint}>
        In local mode {groupMode ? 'each repository is' : 'the repository is'} cloned into the run's
        own working directory, so the agent can edit {groupMode ? 'them' : 'it'}, commit, push a
        branch and open a pull/merge request. Pushing is already authenticated — the agent is never
        handed a token to paste anywhere.
      </p>
    </>
  )
}
