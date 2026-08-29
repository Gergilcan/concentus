import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DatabaseDef, SqlNodeData, SqlPreview } from '../api/types.ts'
import { SqlInspector } from './SqlInspector.tsx'

const listDatabasesMock = vi.fn<() => Promise<DatabaseDef[]>>()
const ragPreviewMock = vi.fn<(...args: unknown[]) => Promise<SqlPreview>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listDatabases: () => listDatabasesMock(),
    ragPreview: (...args: unknown[]) => ragPreviewMock(...args),
    // The credential picker loads the stored list; empty keeps it out of these tests.
    listCredentials: () => Promise.resolve([]),
  },
}))

const data: SqlNodeData = {
  kind: 'sql',
  label: 'sales',
  jdbcUrl: 'jdbc:postgresql://db:5432/shop',
  username: 'reader',
  credentialId: '',
  query: 'select id, total from orders',
  maxRows: 50,
}

// The SQL node runs a query at run start and hands the rows to the agent. The panel's own
// promise is the preview: the same query, the same connection, before anyone starts a run.
describe('SqlInspector', () => {
  beforeEach(() => {
    listDatabasesMock.mockResolvedValue([])
  })
  afterEach(() => vi.clearAllMocks())

  it('seeds the connection fields and the query, and forwards edits', () => {
    const set = vi.fn()
    render(<SqlInspector data={data} set={set} />)

    expect(screen.getByLabelText('Label')).toHaveValue('sales')
    expect(screen.getByLabelText('JDBC URL')).toHaveValue('jdbc:postgresql://db:5432/shop')
    expect(screen.getByLabelText('Username')).toHaveValue('reader')
    expect(screen.getByLabelText('SQL query')).toHaveValue('select id, total from orders')

    fireEvent.change(screen.getByLabelText('JDBC URL'), { target: { value: 'jdbc:mysql://x/y' } })
    expect(set).toHaveBeenCalledWith({ jdbcUrl: 'jdbc:mysql://x/y' })
    fireEvent.change(screen.getByLabelText('SQL query'), { target: { value: 'select 1' } })
    expect(set).toHaveBeenCalledWith({ query: 'select 1' })
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'app' } })
    expect(set).toHaveBeenCalledWith({ username: 'app' })
  })

  it('keeps max rows behind Fine-tuning and writes it as a number', () => {
    const set = vi.fn()
    render(<SqlInspector data={data} set={set} />)

    expect(screen.queryByLabelText('Max rows')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Fine-tuning'))
    expect(screen.getByLabelText('Max rows')).toHaveValue(50)
    fireEvent.change(screen.getByLabelText('Max rows'), { target: { value: '200' } })
    expect(set).toHaveBeenCalledWith({ maxRows: 200 })
  })

  it('offers the stored connections only when some exist, and choosing one fills the three connection fields at once', async () => {
    listDatabasesMock.mockResolvedValue([
      { id: 'db1', label: 'Warehouse', jdbcUrl: 'jdbc:postgresql://wh/dw', username: 'etl', credentialId: 'cred_wh' },
    ])
    const set = vi.fn()
    render(<SqlInspector data={data} set={set} />)

    expect(screen.queryByLabelText('Use database (from Resources)')).not.toBeInTheDocument()
    const picker = await screen.findByLabelText('Use database (from Resources)')
    fireEvent.change(picker, { target: { value: 'db1' } })
    expect(set).toHaveBeenCalledWith({ jdbcUrl: 'jdbc:postgresql://wh/dw', username: 'etl', credentialId: 'cred_wh' })
  })

  it('shows no connection picker at all when there are none stored', async () => {
    render(<SqlInspector data={data} set={vi.fn()} />)
    await waitFor(() => expect(listDatabasesMock).toHaveBeenCalled())
    expect(screen.queryByLabelText('Use database (from Resources)')).not.toBeInTheDocument()
  })

  it('previews the query as the node holds it and renders the rows', async () => {
    ragPreviewMock.mockResolvedValue({
      columns: ['id', 'total'],
      rows: [['1', '9.99'], ['2', '15.00']],
      rowCount: 2,
      truncated: false,
    })
    render(<SqlInspector data={data} set={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: '▷ Preview query' }))
    expect(ragPreviewMock).toHaveBeenCalledWith({
      label: 'sales',
      jdbcUrl: 'jdbc:postgresql://db:5432/shop',
      username: 'reader',
      credentialId: '',
      query: 'select id, total from orders',
      maxRows: 50,
    })
    expect(await screen.findByText('2 row(s)')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'total' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '15.00' })).toBeInTheDocument()
  })

  it('says when the preview was cut short', async () => {
    ragPreviewMock.mockResolvedValue({ columns: ['id'], rows: [['1']], rowCount: 1, truncated: true })
    render(<SqlInspector data={data} set={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '▷ Preview query' }))
    expect(await screen.findByText('1 row(s) (truncated)')).toBeInTheDocument()
  })

  it('shows the database error where the rows would be, and lets you try again', async () => {
    ragPreviewMock.mockRejectedValue(new Error('relation "orders" does not exist'))
    render(<SqlInspector data={data} set={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: '▷ Preview query' }))
    expect(await screen.findByText('relation "orders" does not exist')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '▷ Preview query' })).toBeEnabled()
  })
})
