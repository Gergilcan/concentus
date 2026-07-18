import { useState } from 'react'
import type { InputNodeData } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import styles from './panels.module.scss'

interface Props {
  data: InputNodeData
  set: (patch: Record<string, unknown>) => void
}

export function InputInspector({ data, set }: Props) {
  const flowId = useFlowStore((s) => s.flowId)
  const [copied, setCopied] = useState(false)

  // No token in the URL: Linear authenticates by signing the body, not by echoing a secret back.
  const webhookUrl = flowId ? `${location.origin}/api/webhooks/${flowId}` : null

  const copy = async () => {
    if (!webhookUrl) return
    try {
      await navigator.clipboard.writeText(webhookUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard blocked — the field is selectable anyway */
    }
  }

  return (
    <>
      <label className={styles.field}>
        <span>Execution type</span>
        <select value={data.mode} onChange={(e) => set({ mode: e.target.value })}>
          <option value="manual">Manual — you send the first message</option>
          <option value="prompt">Prompt — auto-start with a fixed prompt</option>
          <option value="cron">Automatic — run on a cron schedule</option>
          <option value="webhook">Webhook — start on an external event</option>
        </select>
      </label>

      {data.mode !== 'manual' && (
        <label className={styles.field}>
          <span>{data.mode === 'webhook' ? 'Instruction (prepended to the event)' : 'Execution prompt'}</span>
          <textarea
            rows={4}
            placeholder={
              data.mode === 'webhook'
                ? 'A Linear issue/comment event arrived. Triage it and take the right action.'
                : 'Build the login page: backend endpoint + React form, wired to the DB.'
            }
            value={data.prompt}
            onChange={(e) => set({ prompt: e.target.value })}
          />
        </label>
      )}

      {data.mode === 'cron' && (
        <label className={styles.field}>
          <span>Cron expression</span>
          <input value={data.cron} placeholder="0 9 * * *" onChange={(e) => set({ cron: e.target.value })} />
        </label>
      )}

      {data.mode === 'webhook' && (
        <>
          <label className={styles.field}>
            <span>Validation parameter</span>
            <input
              value={data.authParam}
              placeholder="Linear-Signature"
              onChange={(e) => set({ authParam: e.target.value })}
            />
          </label>
          <p className={styles.hint}>
            Header (or query parameter) the provider sends the proof in. E.g.{' '}
            <code>Linear-Signature</code>, <code>X-Hub-Signature-256</code> for GitHub, or{' '}
            <code>token</code> for a plain shared token.
          </p>

          <label className={styles.field}>
            <span>Secret</span>
            <input
              value={data.secret}
              placeholder="Copy from the provider's webhook page"
              onChange={(e) => set({ secret: e.target.value })}
            />
          </label>
          {!data.secret && (
            <p className={styles.hint}>
              Required — without it every delivery is rejected with <b>401</b>.
            </p>
          )}

          <label className={styles.field}>
            <span>Webhook URL</span>
            {webhookUrl ? (
              <input readOnly value={webhookUrl} onFocus={(e) => e.currentTarget.select()} />
            ) : (
              <input readOnly value="Save the flow first to generate the URL." />
            )}
          </label>
          {webhookUrl && (
            <div className={styles.mcpBtns}>
              <button className={styles.previewBtn} onClick={() => void copy()}>
                {copied ? 'Copied ✓' : 'Copy URL'}
              </button>
            </div>
          )}

          <p className={styles.hint}>
            The value is accepted if it's an HMAC-SHA256 of the request body signed with the secret, or
            the secret itself — so signed and plain-token providers both work with no extra setup.
          </p>
          <p className={styles.hint}>
            <b>Linear:</b> Settings → API → Webhooks → New webhook. Paste this URL and enable the events
            you want (e.g. <b>Issues</b>, <b>Comments</b>). Linear then shows a <b>signing secret</b> on
            the webhook's page — copy it into the Secret field. The URL must be reachable from the
            internet (deploy it, or tunnel with ngrok for local testing).
          </p>
        </>
      )}

      {data.mode !== 'webhook' && (
        <p className={styles.hint}>
          {data.mode === 'manual' && 'The run starts idle — type the first instruction in the console.'}
          {data.mode === 'prompt' && 'Pressing Run auto-sends this prompt as the first turn.'}
          {data.mode === 'cron' && (
            <>
              Runs automatically on this schedule with the prompt above (saved flows only). 5-field
              (<code>min hour day month weekday</code>) or 6-field cron. E.g. <code>0 9 * * *</code>{' '}
              daily 09:00.
            </>
          )}
        </p>
      )}
      <p className={styles.hint}>Connect this node's output to your coordinator agent.</p>
    </>
  )
}
