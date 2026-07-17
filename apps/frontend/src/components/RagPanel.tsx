import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { RagStatus } from '../api/types.ts'
import styles from './panels.module.scss'

export function RagPanel() {
  const [status, setStatus] = useState<RagStatus | null>(null)

  useEffect(() => {
    api.ragStatus().then(setStatus).catch(() => setStatus(null))
  }, [])

  return (
    <div className={styles.rag}>
      <h3 className={styles.h3}>
        RAG context <span className={styles.soon}>soon</span>
      </h3>
      <p className={styles.ragMsg}>
        {status?.message ?? 'Retrieve documents and inject them into agent context. Coming next.'}
      </p>
      <button className={styles.addBtn} disabled>
        + Add source
      </button>
    </div>
  )
}
