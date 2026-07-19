import { useState } from 'react'
import type { InputNodeData } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { Field, SelectField, TextArea } from './fields.tsx'
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
      <SelectField label="Execution type" value={data.mode} onChange={(v) => set({ mode: v })}>
        <option value="manual">Manual — you send the first message</option>
        <option value="prompt">Prompt — auto-start with a fixed prompt</option>
        <option value="cron">Automatic — run on a cron schedule</option>
        <option value="webhook">Webhook — start on an external event</option>
      </SelectField>

      {data.mode !== 'manual' && (
        <TextArea
          label={data.mode === 'webhook' ? 'Instruction (prepended to the event)' : 'Execution prompt'}
          rows={4}
          placeholder={
            data.mode === 'webhook'
              ? 'A Linear issue/comment event arrived. Triage it and take the right action.'
              : 'Build the login page: backend endpoint + React form, wired to the DB.'
          }
          value={data.prompt}
          onChange={(v) => set({ prompt: v })}
        />
      )}

      {data.mode === 'cron' && (
        <Field label="Cron expression" value={data.cron} placeholder="0 9 * * *" onChange={(v) => set({ cron: v })} />
      )}

      {data.mode === 'webhook' && (
        <>
          <Field
            label="Validation parameter"
            value={data.authParam}
            placeholder="Linear-Signature"
            onChange={(v) => set({ authParam: v })}
          />
          <p className={styles.hint}>
            Header (or query parameter) the provider sends the proof in. E.g.{' '}
            <code>Linear-Signature</code>, <code>X-Hub-Signature-256</code> for GitHub, or{' '}
            <code>token</code> for a plain shared token.
          </p>

          <Field
            label="Secret"
            value={data.secret}
            placeholder="Copy from the provider's webhook page"
            onChange={(v) => set({ secret: v })}
          />
          {!data.secret && (
            <p className={styles.hint}>
              Required — without it every delivery is rejected with <b>401</b>.
            </p>
          )}

          <Field
            label="Webhook URL"
            value={webhookUrl ?? 'Save the flow first to generate the URL.'}
            readOnly
            onFocus={webhookUrl ? (e) => e.currentTarget.select() : undefined}
          />
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
