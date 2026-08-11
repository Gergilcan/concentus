import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { SkillCatalogSkill, SkillInfo, SkillRepo } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { cx } from '../utils/cx.ts'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/**
 * Agent Skills: zip a folder with a SKILL.md, upload it here, assign it on any agent node.
 *
 * Concentus runs on Claude Code, and Claude Code already knows how to discover and use skills —
 * this panel only has to store them and put them into each run's workspace. That inheritance is
 * the moat: a competitor without the CLI underneath has to build the whole mechanism.
 */
export function SkillsPanel() {
  const [skills, setSkills] = useState<SkillInfo[]>([])
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  const refresh = () => {
    api.listSkills().then(setSkills).catch((e) => setNote(errMessage(e)))
  }
  useEffect(refresh, [])

  const upload = async (file: File) => {
    setBusy(true)
    setNote(null)
    try {
      const saved = await api.uploadSkill(file)
      setNote(`"${saved.name}" installed (${saved.fileCount} file(s)). Assign it on an agent node.`)
      refresh()
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.crudForm}>
      <h3 className={styles.h3}>Agent Skills</h3>
      <p
        className={panels.hint}
        title="A skill is a folder with a SKILL.md (name + description in its frontmatter) plus any files it needs — playbooks, templates, scripts. Zip the folder and upload it. Assigned skills are installed into each run's workspace, where Claude Code discovers them by itself."
      >
        Upload a zipped skill folder, then assign it on agent nodes. ⓘ
      </p>

      {skills.length === 0 && <div className={styles.muted}>No skills installed yet.</div>}
      {skills.map((s) => (
        <div key={s.id} className={styles.kbDoc}>
          <span className={styles.kbDocName} title={s.description}>{s.name}</span>
          <span className={styles.muted}>{s.fileCount} file(s)</span>
          <button
            className={styles.delBtn}
            onClick={() => void api.deleteSkill(s.id).then(refresh)}
          >
            Delete
          </button>
        </div>
      ))}

      <div className={styles.crudActions}>
        <button className={styles.newBtn} disabled={busy} onClick={() => fileRef.current?.click()}>
          {busy ? 'Installing…' : 'Upload skill (.zip)'}
        </button>
        <input
          ref={fileRef}
          type="file"
          hidden
          accept=".zip"
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) void upload(f)
            e.target.value = ''
          }}
        />
      </div>

      <SkillCatalog installedNames={skills.map((s) => s.name)} onInstalled={refresh} setNote={setNote} />
      {note && <p className={panels.hint}>{note}</p>}
    </div>
  )
}

/**
 * The GitHub catalog: the most-starred Claude-skill collections, browsed and installed in place.
 *
 * Collapsed by default like the MCP catalog, and for the same reason — it is for setup moments,
 * and the user's own installed skills are this tab's actual content. Everything under it is
 * fetched lazily on first expand: the repository list is one GitHub search, and each repository's
 * skills are read from its archive only when that repository is opened.
 */
function SkillCatalog({ installedNames, onInstalled, setNote }: {
  installedNames: string[]
  onInstalled: () => void
  setNote: (note: string | null) => void
}) {
  const [open, setOpen] = useState(false)
  const [repos, setRepos] = useState<SkillRepo[] | null>(null)
  const [reposError, setReposError] = useState<string | null>(null)
  const [openRepo, setOpenRepo] = useState<string | null>(null)
  const [repoSkills, setRepoSkills] = useState<Record<string, SkillCatalogSkill[] | 'loading' | string>>({})
  const [installing, setInstalling] = useState<string | null>(null)

  useEffect(() => {
    if (!open || repos !== null) return
    api.skillCatalog().then(setRepos).catch((e) => setReposError(errMessage(e)))
  }, [open, repos])

  const toggleRepo = (fullName: string) => {
    const next = openRepo === fullName ? null : fullName
    setOpenRepo(next)
    if (!next || repoSkills[fullName] !== undefined) return
    setRepoSkills((prev) => ({ ...prev, [fullName]: 'loading' }))
    const [owner, repo] = fullName.split('/')
    api
      .skillCatalogRepo(owner, repo)
      .then((found) => setRepoSkills((prev) => ({ ...prev, [fullName]: found })))
      .catch((e) => setRepoSkills((prev) => ({ ...prev, [fullName]: errMessage(e) })))
  }

  const install = async (fullName: string, skill: SkillCatalogSkill) => {
    const [owner, repo] = fullName.split('/')
    setInstalling(fullName + '/' + skill.path)
    setNote(null)
    try {
      const saved = await api.installCatalogSkill(owner, repo, skill.path)
      setNote(`"${saved.name}" installed (${saved.fileCount} file(s)). Assign it on an agent node.`)
      onInstalled()
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setInstalling(null)
    }
  }

  // The backend sanitises names on install the same way, so this is how "PDF Tools" in a repo
  // matches the installed "pdf-tools" without asking the server.
  const installed = new Set(installedNames)
  const sanitized = (name: string) =>
    name.trim().toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '')

  return (
    <div className={styles.catalog}>
      <button
        className={styles.collapseHead}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        title="Skill collections on GitHub, ranked by stars. A skill is installed straight from its repository — same rules as a zip upload. Installing one means trusting its instructions, exactly as if you had written them."
      >
        <span className={styles.collapseArrow}>{open ? '▾' : '▸'}</span>
        Browse catalog — popular on GitHub
      </button>

      {open && repos === null && !reposError && <div className={styles.muted}>Asking GitHub…</div>}
      {open && reposError && <div className={styles.muted}>{reposError}</div>}
      {open && repos !== null && (
        <div className={styles.repoList}>
          {repos.map((repo) => (
            <div key={repo.fullName}>
              <button
                className={styles.repoRow}
                onClick={() => toggleRepo(repo.fullName)}
                aria-expanded={openRepo === repo.fullName}
                title={repo.description || repo.fullName}
              >
                <span className={styles.collapseArrow}>{openRepo === repo.fullName ? '▾' : '▸'}</span>
                <span className={styles.repoName}>{repo.fullName}</span>
                {repo.stars >= 0 && <span className={styles.repoStars}>★ {formatStars(repo.stars)}</span>}
                <span className={cx(styles.muted, styles.repoBlurb)}>{repo.description}</span>
              </button>

              {openRepo === repo.fullName && (
                <div className={styles.repoSkills}>
                  {repoSkills[repo.fullName] === 'loading' && (
                    <div className={styles.muted}>Reading the repository…</div>
                  )}
                  {typeof repoSkills[repo.fullName] === 'string' &&
                    repoSkills[repo.fullName] !== 'loading' && (
                      <div className={styles.muted}>{repoSkills[repo.fullName] as string}</div>
                    )}
                  {Array.isArray(repoSkills[repo.fullName]) &&
                    (repoSkills[repo.fullName] as SkillCatalogSkill[]).length === 0 && (
                      <div className={styles.muted}>No SKILL.md found in this repository.</div>
                    )}
                  {Array.isArray(repoSkills[repo.fullName]) &&
                    (repoSkills[repo.fullName] as SkillCatalogSkill[]).map((skill) => {
                      const done = installed.has(sanitized(skill.name))
                      const busyKey = repo.fullName + '/' + skill.path
                      return (
                        <div key={skill.path} className={styles.kbDoc}>
                          <span className={styles.kbDocName} title={skill.description}>
                            {skill.name}
                          </span>
                          {done ? (
                            <span className={cx(styles.authPill, styles.authAdded)}>✓ installed</span>
                          ) : (
                            <button
                              className={styles.newBtn}
                              disabled={installing !== null}
                              onClick={() => void install(repo.fullName, skill)}
                            >
                              {installing === busyKey ? 'Installing…' : 'Install'}
                            </button>
                          )}
                        </div>
                      )
                    })}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** 12345 → "12.3k": the number is a signal of adoption, not a figure anyone reads exactly. */
function formatStars(stars: number): string {
  return stars >= 1000 ? (stars / 1000).toFixed(1) + 'k' : String(stars)
}
