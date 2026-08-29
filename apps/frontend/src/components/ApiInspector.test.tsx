import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiNodeData, ApiOperationView } from '../api/types.ts'
import { ApiInspector } from './ApiInspector.tsx'

const previewMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    previewApiSpec: (...args: unknown[]) => previewMock(...args),
  },
}))

const base: ApiNodeData = { kind: 'api', label: 'petstore', specUrl: 'https://x/openapi.json', ops: [] }

function op(key: string, write = false): ApiOperationView {
  const [method, path] = key.split(' ')
  return { key, method, path, description: '', paramCount: 0, hasBody: write, write }
}

/** The label wraps the checkbox, so the tick lives inside the row titled by the operation key. */
const tickOf = (key: string) => screen.getByTitle(key).querySelector('input') as HTMLInputElement

// The API node turns a spec into an allowlist of operations, or — in endpoint mode — is one URL
// typed by hand. The allowlist is what the run enforces, so what the checkboxes write matters.
describe('ApiInspector', () => {
  afterEach(() => vi.clearAllMocks())

  it('starts in spec mode: the spec URL with its explanation, the credential, no endpoint fields', () => {
    const set = vi.fn()
    render(<ApiInspector data={base} set={set} />)

    const url = screen.getByLabelText(/OpenAPI spec URL/)
    expect(url).toHaveValue('https://x/openapi.json')
    expect(screen.getByTitle(/URL of the API's OpenAPI 3.x document/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Method')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Load operations' })).toBeEnabled()

    fireEvent.change(url, { target: { value: 'https://y/spec.yaml' } })
    expect(set).toHaveBeenCalledWith({ specUrl: 'https://y/spec.yaml' })
    fireEvent.change(screen.getByLabelText(/Credential id/), { target: { value: 'cred_1' } })
    expect(set).toHaveBeenCalledWith({ credentialId: 'cred_1' })
  })

  it('switches mode through the "This node calls" select', () => {
    const set = vi.fn()
    render(<ApiInspector data={base} set={set} />)

    fireEvent.change(screen.getByLabelText(/This node calls/), { target: { value: 'endpoint' } })
    expect(set).toHaveBeenCalledWith({ mode: 'endpoint' })
  })

  it('endpoint mode swaps the spec for method, URL, description and the body switch', () => {
    const set = vi.fn()
    render(<ApiInspector data={{ ...base, mode: 'endpoint', method: 'POST', url: 'https://h/x/{id}' }} set={set} />)

    expect(screen.queryByLabelText(/OpenAPI spec URL/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load operations' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Method')).toHaveValue('POST')
    expect(screen.getByLabelText(/^URL/)).toHaveValue('https://h/x/{id}')
    // The description is the only thing the model reads, and the tooltip says so.
    expect(screen.getByTitle(/the only thing telling the model when to call this endpoint/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Method'), { target: { value: 'DELETE' } })
    expect(set).toHaveBeenCalledWith({ method: 'DELETE' })
    fireEvent.change(screen.getByLabelText(/What this endpoint does/), { target: { value: 'Deletes a thing' } })
    expect(set).toHaveBeenCalledWith({ description: 'Deletes a thing' })
    fireEvent.click(screen.getByLabelText('The agent may send a JSON body'))
    expect(set).toHaveBeenCalledWith({ sendsBody: true })
  })

  it('loads the operations and ticking one adds it to — or removes it from — the allowlist', async () => {
    previewMock.mockResolvedValue({ baseUrl: '', operations: [op('GET /pets'), op('POST /pets', true)] })
    const set = vi.fn()
    const { rerender } = render(<ApiInspector data={base} set={set} />)

    fireEvent.click(screen.getByRole('button', { name: 'Load operations' }))
    expect(await screen.findByTitle('GET /pets')).toBeInTheDocument()
    expect(previewMock).toHaveBeenCalledWith('https://x/openapi.json', undefined)
    expect(tickOf('GET /pets')).not.toBeChecked()

    fireEvent.click(tickOf('GET /pets'))
    expect(set).toHaveBeenCalledWith({ ops: ['GET /pets'] })

    rerender(<ApiInspector data={{ ...base, ops: ['GET /pets', 'POST /pets'] }} set={set} />)
    expect(tickOf('POST /pets')).toBeChecked()
    fireEvent.click(tickOf('POST /pets'))
    expect(set).toHaveBeenCalledWith({ ops: ['GET /pets'] })
  })

  it('"Allow all reads" ticks every read and never a write, keeping what was already allowed', async () => {
    previewMock.mockResolvedValue({
      baseUrl: '',
      operations: [op('GET /pets'), op('GET /pets/{id}'), op('POST /pets', true), op('DELETE /pets/{id}', true)],
    })
    const set = vi.fn()
    render(<ApiInspector data={{ ...base, ops: ['POST /pets'] }} set={set} />)

    // Not offered until there is a loaded spec to read the methods from.
    expect(screen.queryByRole('button', { name: 'Allow all reads' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load operations' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Allow all reads' }))

    expect(set).toHaveBeenCalledWith({ ops: ['POST /pets', 'GET /pets', 'GET /pets/{id}'] })
  })

  it("takes the spec's own base URL as a default, and leaves one the author already typed alone", async () => {
    previewMock.mockResolvedValue({ baseUrl: 'https://api.example.com/v1', operations: [] })
    const set = vi.fn()
    render(<ApiInspector data={base} set={set} />)
    fireEvent.click(screen.getByRole('button', { name: 'Load operations' }))
    await waitFor(() => expect(set).toHaveBeenCalledWith({ baseUrl: 'https://api.example.com/v1' }))

    set.mockClear()
    render(<ApiInspector data={{ ...base, baseUrl: 'https://sandbox.example.com' }} set={set} />)
    fireEvent.click(screen.getAllByRole('button', { name: 'Load operations' })[1])
    await waitFor(() => expect(previewMock).toHaveBeenCalledTimes(2))
    expect(set).not.toHaveBeenCalled()
  })

  it('says why the spec could not be read instead of showing an empty list', async () => {
    previewMock.mockRejectedValue(new Error('404 from https://x/openapi.json'))
    render(<ApiInspector data={base} set={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Load operations' }))
    expect(await screen.findByText('404 from https://x/openapi.json')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('counts the operations already allowed while the spec is not loaded, so they are not invisible', () => {
    render(<ApiInspector data={{ ...base, ops: ['GET /pets', 'POST /pets'] }} set={vi.fn()} />)
    expect(screen.getByText('2 operation(s) currently allowed. Load the spec to review them.')).toBeInTheDocument()
  })
})
