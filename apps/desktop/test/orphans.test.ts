import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { restorePlatform, setPlatform } from './helpers'

/**
 * orphans.ts: what a fresh launch is allowed to kill, and what it must never touch.
 *
 * The selection is pure and tested as such. The sweep around it is tested with the process
 * listing and the kill faked, so the tests can show that a listing that fails, or a kill that
 * fails, leaves the launch to carry on rather than becoming a new way to fail.
 */

const mocks = vi.hoisted(() => ({
  execFile: vi.fn(),
  dataDir: 'C:\\Users\\gerard\\AppData\\Roaming\\Concentus',
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('node:child_process', () => ({ execFile: mocks.execFile }))
vi.mock('../src/paths', () => ({ dataDir: () => mocks.dataDir }))
vi.mock('../src/log', () => ({ log: mocks.log }))

import { killOrphans, selectOrphans } from '../src/orphans'

const DATA = mocks.dataDir

const ours = {
  backend: {
    pid: 101,
    name: 'java.exe',
    commandLine: '"C:\\Program Files\\Concentus\\resources\\jre\\bin\\java.exe" -jar concentus-backend.jar --spring.profiles.active=desktop --server.port=8734',
  },
  postgres: {
    pid: 102,
    name: 'postgres.exe',
    // Different case and forward slashes: Windows command lines are both.
    commandLine: `C:/Users/GERARD/AppData/Roaming/concentus/pg/bin/postgres.exe -D "c:/users/gerard/appdata/roaming/CONCENTUS/pgdata" -p 54329`,
  },
}

const notOurs = {
  devBackend: {
    pid: 201,
    name: 'java.exe',
    commandLine: 'java -classpath C:\\tools\\maven\\boot\\plexus-classworlds.jar org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run',
  },
  systemPostgres: { pid: 202, name: 'postgres.exe', commandLine: '"C:\\Program Files\\PostgreSQL\\16\\bin\\postgres.exe" -D "C:\\Program Files\\PostgreSQL\\16\\data"' },
  otherAppsPostgres: { pid: 203, name: 'postgres.exe', commandLine: `postgres -D ${DATA.replace('Concentus', 'OtherApp')}\\pgdata` },
  someJava: { pid: 204, name: 'java.exe', commandLine: 'java -jar C:\\tools\\jenkins.jar' },
  noCommandLine: { pid: 205, name: 'java.exe', commandLine: '' },
}

describe('selectOrphans — the policy', () => {
  it('takes a java running concentus-backend.jar and a postgres on OUR pgdata, whatever the case or slashes', () => {
    const chosen = selectOrphans([notOurs.someJava, ours.backend, notOurs.systemPostgres, ours.postgres], DATA)
    expect(chosen.map((p) => p.pid)).toEqual([101, 102])
  })

  it('never a dev backend under mvn spring-boot:run — its command line carries a classpath, not the jar', () => {
    expect(selectOrphans([notOurs.devBackend], DATA)).toEqual([])
  })

  it('never a PostgreSQL that is not on our data directory: a real server, or another app\'s embedded one', () => {
    expect(selectOrphans([notOurs.systemPostgres, notOurs.otherAppsPostgres], DATA)).toEqual([])
  })

  it('never any other java, and copes with a process whose command line could not be read', () => {
    expect(selectOrphans([notOurs.someJava, notOurs.noCommandLine], DATA)).toEqual([])
    expect(selectOrphans([{ pid: 1, name: 'java.exe', commandLine: undefined as unknown as string }], DATA)).toEqual([])
  })

  it('our pgdata alone is not enough — a java that merely mentions it (a backup tool, say) is not postgres', () => {
    const backup = { pid: 300, name: 'java.exe', commandLine: `java -jar C:\\tools\\backup.jar --dir ${DATA}\\pgdata` }
    expect(selectOrphans([backup], DATA)).toEqual([])
  })
})

describe('killOrphans on Windows', () => {
  const taskkills = () => mocks.execFile.mock.calls.filter((c) => c[0] === 'taskkill').map((c) => c[1] as string[])

  /** Script the two commands: what the process listing prints, and whether taskkill succeeds. */
  function processes(listing: string | Error, killFails = false): void {
    mocks.execFile.mockImplementation((file: string, _args: string[], _options: unknown, callback: (err: Error | null, stdout?: string) => void) => {
      if (file === 'powershell.exe') {
        if (listing instanceof Error) callback(listing)
        else callback(null, listing)
      } else if (file === 'taskkill') {
        if (killFails) callback(new Error('ERROR: The process "999" not found.'))
        else callback(null, 'SUCCESS')
      } else {
        callback(new Error(`unexpected command ${file}`))
      }
    })
  }

  const row = (p: { pid: number; name: string; commandLine: string }) => ({ ProcessId: p.pid, Name: p.name, CommandLine: p.commandLine })

  beforeEach(() => setPlatform('win32'))
  afterEach(() => restorePlatform())

  it('lists java and postgres through PowerShell (wmic is gone) and kills only the orphans, tree and all', async () => {
    processes(JSON.stringify([row(notOurs.devBackend), row(ours.backend), row(ours.postgres), row(notOurs.systemPostgres)]))

    await killOrphans()

    const [file, args] = mocks.execFile.mock.calls[0] as [string, string[]]
    expect(file).toBe('powershell.exe')
    expect(args.join(' ')).toContain("Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='postgres.exe'\"")
    // /T takes the leftover backend's own postgres with it; /F does not ask.
    expect(taskkills()).toEqual([
      ['/PID', '101', '/T', '/F'],
      ['/PID', '102', '/T', '/F'],
    ])
    expect(mocks.log.warn).toHaveBeenCalledWith('Killing a leftover backend from a previous run (pid 101).')
    expect(mocks.log.warn).toHaveBeenCalledWith('Killing a leftover embedded postgres from a previous run (pid 102).')
  })

  it('copes with ConvertTo-Json unwrapping a single result to a bare object', async () => {
    processes(JSON.stringify(row(ours.backend)))

    await killOrphans()

    expect(taskkills()).toEqual([['/PID', '101', '/T', '/F']])
  })

  it('kills nothing when there is nothing to kill', async () => {
    processes('')
    await killOrphans()
    expect(taskkills()).toEqual([])

    processes(JSON.stringify([row(notOurs.devBackend), row(notOurs.systemPostgres)]))
    await killOrphans()
    expect(taskkills()).toEqual([])
    expect(mocks.log.warn).not.toHaveBeenCalled()
  })

  it('a listing that cannot run is a warning, not a failed launch', async () => {
    processes(new Error('powershell.exe ENOENT'))

    await expect(killOrphans()).resolves.toBeUndefined()

    expect(taskkills()).toEqual([])
    expect(mocks.log.warn).toHaveBeenCalledWith('Could not scan for leftover processes: powershell.exe ENOENT')
  })

  it('a kill that fails is logged and the sweep goes on to the next one', async () => {
    processes(JSON.stringify([row(ours.backend), row(ours.postgres)]), true)

    await expect(killOrphans()).resolves.toBeUndefined()

    expect(taskkills()).toHaveLength(2)
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('Could not kill pid 101'))
    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('Could not kill pid 102'))
  })
})

describe('killOrphans on Unix', () => {
  let kill: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    setPlatform('linux')
    mocks.dataDir = '/home/gerard/.config/Concentus'
    kill = vi.spyOn(process, 'kill').mockImplementation(() => true)
  })
  afterEach(() => {
    restorePlatform()
    mocks.dataDir = DATA
    kill.mockRestore()
  })

  it('parses `ps`, keeps java and postgres only, and SIGKILLs the orphans', async () => {
    mocks.execFile.mockImplementation((file: string, args: string[], _options: unknown, callback: (err: Error | null, stdout?: string) => void) => {
      expect(file).toBe('ps')
      expect(args).toEqual(['-axo', 'pid=,comm=,args='])
      callback(null, [
        '  1 systemd /sbin/init',
        ' 4242 java /opt/Concentus/resources/jre/bin/java -jar concentus-backend.jar --spring.profiles.active=desktop',
        ' 4243 postgres /home/gerard/.config/Concentus/pg/bin/postgres -D /home/gerard/.config/Concentus/pgdata',
        ' 5000 java java -classpath /usr/share/maven/boot/plexus-classworlds.jar org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run',
        ' 5001 postgres /usr/lib/postgresql/16/bin/postgres -D /var/lib/postgresql/16/main',
        // An editor with the jar open mentions it by name; it is not java and must not be touched.
        ' 5002 code /usr/bin/code /work/concentus-backend.jar',
        '',
      ].join('\n'))
    })

    await killOrphans()

    expect(kill.mock.calls).toEqual([[4242, 'SIGKILL'], [4243, 'SIGKILL']])
  })
})
