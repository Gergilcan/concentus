import type { RepoNodeData } from '../api/types.ts'
import { Field, SelectField } from './fields.tsx'

interface Props {
  data: RepoNodeData
  set: (patch: Record<string, unknown>) => void
}

export function RepoInspector({ data, set }: Props) {
  return (
    <>
      <SelectField label="Provider" value={data.provider} onChange={(v) => set({ provider: v })}>
        <option value="github">github</option>
        <option value="gitlab">gitlab</option>
      </SelectField>
      <Field
        label="URL"
        value={data.url}
        placeholder="https://github.com/owner/repo"
        onChange={(v) => set({ url: v })}
      />
      <Field label="Token env var" value={data.tokenEnv} onChange={(v) => set({ tokenEnv: v })} />
      <Field
        label="Mount path"
        value={data.mountPath}
        placeholder="/workspace/repo"
        onChange={(v) => set({ mountPath: v })}
      />
      <Field label="Branch" value={data.branch} onChange={(v) => set({ branch: v })} />
    </>
  )
}
