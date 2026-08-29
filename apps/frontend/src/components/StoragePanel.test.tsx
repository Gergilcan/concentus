import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { StoragePanel } from './StoragePanel.tsx'

const storageContents = vi.fn()
const migrateStorage = vi.fn()
const getStorage = vi.fn()
const exportBackup = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    getStorage: () => getStorage(),
    saveStorage: vi.fn(),
    testStorage: vi.fn(),
    storageContents: (from: string) => storageContents(from),
    migrateStorage: (body: unknown) => migrateStorage(body),
    exportBackup: (includeSecrets?: boolean) => exportBackup(includeSecrets),
    importBackup: vi.fn(),
  },
}))

/**
 * Moving an installation onto a company database.
 *
 * The whole point of the feature is that it is safe to press, so what this screen must get right is
 * what it promises: that the copy goes to the database written above it, that leaving a heavy table
 * behind is one tick, and that a copy which stopped halfway invites being repeated rather than
 * looking like data loss.
 */
describe('StoragePanel — moving data to another database', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getStorage.mockResolvedValue({
      mode: 'embedded',
      url: '',
      username: '',
      hasPassword: false,
      activeMode: 'embedded',
    })
    storageContents.mockResolvedValue({
      tables: [
        { table: 'runs', rows: 4200 },
        { table: 'resources', rows: 31 },
        { table: 'knowledge_chunks', rows: 0 },
      ],
      totalRows: 4231,
    })
    migrateStorage.mockResolvedValue({
      ok: true,
      copied: [
        { table: 'runs', rows: 4200 },
        { table: 'resources', rows: 31 },
      ],
      warnings: [],
      totalRows: 4231,
    })
  })

  const open = async () => {
    render(<StoragePanel pushError={vi.fn()} />)
    await screen.findByText(/Move my data/i)
  }

  /** The screen somebody sees after switching, restarting, and finding nothing there. */
  const openAlreadySwitched = async () => {
    getStorage.mockResolvedValue({
      mode: 'external',
      url: 'jdbc:postgresql://db.internal:5432/concentus',
      username: 'concentus',
      hasPassword: true,
      activeMode: 'external',
    })
    render(<StoragePanel pushError={vi.fn()} />)
    await screen.findByText(/Bring my old data here/i)
  }

  /** The URL field only exists once the storage is set to an external server. */
  const pointAtACompanyDatabase = () => {
    fireEvent.change(screen.getByLabelText(/Where Concentus stores its data/i), {
      target: { value: 'external' },
    })
    fireEvent.change(screen.getByLabelText(/JDBC URL/i), {
      target: { value: 'jdbc:postgresql://db.internal:5432/concentus' },
    })
  }

  // There is nowhere to copy to until the external database is filled in, and a button that
  // answers "which database?" with an error is worse than one that cannot be pressed yet.
  it('cannot be pressed until an external database has been given', async () => {
    await open()

    expect(screen.getByRole('button', { name: /Move it now/i })).toBeDisabled()
  })

  it('shows what is here, biggest first, before anything is copied', async () => {
    await open()

    fireEvent.click(screen.getByRole('button', { name: /See what would move/i }))

    expect(await screen.findByText('runs')).toBeInTheDocument()
    // Loose on the thousands separator: it is the browser's, and the test runner has no locale data.
    expect(screen.getByText(/4[,.  ]?200/)).toBeInTheDocument()
    // Empty tables are noise in an estimate: they answer neither "how long" nor "what travels".
    expect(screen.queryByText('knowledge_chunks')).not.toBeInTheDocument()
  })

  it('leaves a table behind when its tick is cleared', async () => {
    await open()
    pointAtACompanyDatabase()
    fireEvent.click(screen.getByRole('button', { name: /See what would move/i }))
    fireEvent.click(await screen.findByLabelText('runs'))

    fireEvent.click(screen.getByRole('button', { name: /Move it now/i }))

    await waitFor(() => expect(migrateStorage).toHaveBeenCalled())
    expect(migrateStorage.mock.calls[0][0]).toMatchObject({
      mode: 'external',
      url: 'jdbc:postgresql://db.internal:5432/concentus',
      skip: ['runs'],
    })
  })

  // The sentence that decides whether somebody panics: a dropped connection halfway through is
  // normal, costs nothing, and the fix is the same button again.
  it('says a stopped copy destroyed nothing and can be repeated', async () => {
    migrateStorage.mockRejectedValue(new Error('Connection reset.'))
    await open()
    pointAtACompanyDatabase()

    fireEvent.click(screen.getByRole('button', { name: /Move it now/i }))

    expect(await screen.findByText(/Nothing was deleted/i)).toBeInTheDocument()
  })

  // The way most people arrive at this feature: they switched, restarted, and their work is not
  // there. The copy has to run the other way without asking them to switch back first.
  it('reads the embedded database instead when the app already runs on the company one', async () => {
    await openAlreadySwitched()

    fireEvent.click(screen.getByRole('button', { name: /See what is still there/i }))

    await waitFor(() => expect(storageContents).toHaveBeenCalled())
    expect(storageContents.mock.calls[0][0]).toBe('embedded')
  })

  // Nothing to type: the destination is the database it is running on, which is the only reason
  // the person is looking at this screen.
  it('needs no target typed in when it is pulling data back', async () => {
    await openAlreadySwitched()

    fireEvent.click(screen.getByRole('button', { name: /Bring it across/i }))

    await waitFor(() => expect(migrateStorage).toHaveBeenCalled())
    expect(migrateStorage.mock.calls[0][0]).toMatchObject({ from: 'embedded' })
    expect(await screen.findByText(/Reload the page/i)).toBeInTheDocument()
  })

  // Copying and switching are two decisions on purpose, and the screen has to say so — otherwise
  // somebody copies, sees "done", and believes the app is now running on the company database.
  it('says the copy changed nothing here and a restart is still needed', async () => {
    await open()
    pointAtACompanyDatabase()

    fireEvent.click(screen.getByRole('button', { name: /Move it now/i }))

    expect(await screen.findByText(/restart when you are ready/i)).toBeInTheDocument()
  })
})

/**
 * The export, and the one choice on it that matters: whether credential values go in the file.
 *
 * Off is the default and must stay so — the plain file is safe to pass around. On is a deliberate
 * tick by an administrator, and the note afterwards has to say the file now holds passwords,
 * because the person downloading it is exactly the person who will forward it a week later.
 */
describe('StoragePanel — exporting everything', () => {
  beforeEach(() => {
    getStorage.mockResolvedValue({
      mode: 'embedded', url: '', username: '', hasPassword: false, activeMode: 'embedded',
    })
    exportBackup.mockResolvedValue(new Blob(['{}'], { type: 'application/json' }))
    // jsdom has neither object URLs nor navigation; the download link is not what is under test.
    URL.createObjectURL = vi.fn(() => 'blob:export')
    URL.revokeObjectURL = vi.fn()
    HTMLAnchorElement.prototype.click = vi.fn()
  })

  it('exports without secrets unless the box is ticked, and says so', async () => {
    render(<StoragePanel pushError={vi.fn()} />)
    await screen.findByText(/Export everything/i)

    fireEvent.click(screen.getByRole('button', { name: /Export everything/i }))

    await waitFor(() => expect(exportBackup).toHaveBeenCalledWith(false))
    expect(await screen.findByText(/values never leave this machine/i)).toBeInTheDocument()
  })

  it('exports with secrets when an administrator ticks the box, and warns about the file', async () => {
    render(<StoragePanel pushError={vi.fn()} />)
    await screen.findByText(/Export everything/i)

    fireEvent.click(screen.getByLabelText(/Include credential values/i))
    fireEvent.click(screen.getByRole('button', { name: /Export everything/i }))

    await waitFor(() => expect(exportBackup).toHaveBeenCalledWith(true))
    expect(await screen.findByText(/keep the file like the passwords it holds/i)).toBeInTheDocument()
  })
})
