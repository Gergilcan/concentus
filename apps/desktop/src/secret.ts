import { safeStorage } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { dataDir } from './paths'
import { log } from './log'

/**
 * The master key earlier versions encrypted stored credentials with — read, never created.
 *
 * Credentials are no longer encrypted: they are stored in the clear, in the database, where anyone
 * who can read that database can read them. That is a deliberate trade, and what it buys is the
 * failure it removes. The key belonged to a machine and the data belonged to the database, so a
 * second installation connecting to a shared one found every row present and could open none of
 * them — an integration reporting itself "not configured" while its row sat plainly in the table,
 * and a flow that ran, did nothing, and reported success.
 *
 * Still handed to the backend while a key file exists, so values written under the old scheme
 * convert themselves on the next start (see SecretsMigration). Once nothing encrypted is left the
 * file does nothing, and the backend's log says so.
 *
 * It is not deleted here. It is the only thing that can still open an older backup of that
 * database, and removing somebody's last copy of a decryption key is not a side effect an update
 * should have.
 */

const FILE = () => path.join(dataDir(), 'secret.key')
/** How a key was marked when the OS keyring was unavailable and it had to be stored as a file. */
const PLAINTEXT_PREFIX = 'plain:'

/** The key this installation encrypted with, or null when there is nothing left to convert. */
export function legacyMasterSecret(): string | null {
  const file = FILE()
  if (!fs.existsSync(file)) return null

  const raw = fs.readFileSync(file)
  const text = raw.toString('utf8')
  if (text.startsWith(PLAINTEXT_PREFIX)) return text.slice(PLAINTEXT_PREFIX.length)

  try {
    return safeStorage.decryptString(raw)
  } catch (err) {
    // Only values still carrying the old encryption are affected, and the backend names each one
    // in its log — so this is a warning about work to redo, not about data lost silently.
    log.warn('The old master key could not be read; anything still encrypted must be re-entered. '
      + String(err))
    return null
  }
}
