import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { SkillCatalogSkill, SkillInfo, SkillRepo } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { cx } from '../utils/cx.ts'
import { Pager } from './fields.tsx'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/** The app's list convention: twenty per page. */
const PAGE_SIZE = 20

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
  const [page, setPage] = useState(0)
  const fileRef = useRef<HTMLInputElement>(null)

  const refresh = () => {
    api.listSkills().then(setSkills).catch((e) => setNote(errMessage(e)))
  }
  useEffect(refresh, [])

  // Clamped rather than reset on change: deleting the last item of the last page must land on
  // the page that still exists, not on an empty one.
  const pages = Math.max(1, Math.ceil(skills.length / PAGE_SIZE))
  const safePage = Math.min(page, pages - 1)

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
      {skills
        .slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)
        .map((s) => (
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
      <Pager page={safePage} pages={pages} total={skills.length} unit="skill" onPage={setPage} />

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
  // One search over both levels: it narrows the repository rows by name/description, and inside
  // an open repository it narrows the skills. Client-side on purpose — a cross-repo server-side
  // search would mean downloading every repository's archive up front. A repo whose skills are
  // not loaded yet cannot be searched inside; opening it is what loads them, and its row says so.
  const [query, setQuery] = useState('')
  const [skillPage, setSkillPage] = useState(0)

  useEffect(() => {
    if (!open || repos !== null) return
    api.skillCatalog().then(setRepos).catch((e) => setReposError(errMessage(e)))
  }, [open, repos])

  const toggleRepo = (fullName: string) => {
    const next = openRepo === fullName ? null : fullName
    setOpenRepo(next)
    setSkillPage(0)
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
        <input
          className={panels.pickerSearch}
          value={query}
          placeholder="Search repositories and their skills…"
          aria-label="Search skill catalog"
          title="Narrows the repositories by name and description, and the skills inside any repository that is open. A repository must be opened once for its skills to be searchable."
          onChange={(e) => {
            setQuery(e.target.value)
            setSkillPage(0)
          }}
        />
      )}
      {open && repos !== null && (
        <div className={styles.repoList}>
          {visibleRepos(repos, repoSkills, query).map((repo) => (
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
                  {Array.isArray(repoSkills[repo.fullName]) && (() => {
                    const matching = filterSkills(
                      repoSkills[repo.fullName] as SkillCatalogSkill[], query)
                    const pages = Math.max(1, Math.ceil(matching.length / PAGE_SIZE))
                    const safePage = Math.min(skillPage, pages - 1)
                    if (matching.length === 0) {
                      return (
                        <div className={styles.muted}>
                          {query.trim() ? 'No skill here matches that search.' : 'No SKILL.md found in this repository.'}
                        </div>
                      )
                    }
                    return (
                      <>
                        {matching
                          .slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)
                          .map((skill) => {
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
                        <Pager
                          page={safePage}
                          pages={pages}
                          total={matching.length}
                          unit="skill"
                          onPage={setSkillPage}
                        />
                      </>
                    )
                  })()}
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

/**
 * The repositories the search leaves visible: matched by their own name/description, OR by any
 * already-loaded skill inside them — so searching "pdf" keeps the repo whose pdf skill you saw a
 * moment ago, even though "pdf" is nowhere in the repo's description.
 */
function visibleRepos(
  repos: SkillRepo[],
  repoSkills: Record<string, SkillCatalogSkill[] | 'loading' | string>,
  query: string,
): SkillRepo[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return repos
  return repos.filter((repo) => {
    if (repo.fullName.toLowerCase().includes(needle)) return true
    if ((repo.description ?? '').toLowerCase().includes(needle)) return true
    const loaded = repoSkills[repo.fullName]
    return Array.isArray(loaded) && filterSkills(loaded, query).length > 0
  })
}

function filterSkills(skills: SkillCatalogSkill[], query: string): SkillCatalogSkill[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return skills
  return skills.filter(
    (s) =>
      s.name.toLowerCase().includes(needle) ||
      (s.description ?? '').toLowerCase().includes(needle) ||
      s.path.toLowerCase().includes(needle),
  )
}
