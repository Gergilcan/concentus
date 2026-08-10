import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { SkillInfo } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
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
      {note && <p className={panels.hint}>{note}</p>}
    </div>
  )
}
