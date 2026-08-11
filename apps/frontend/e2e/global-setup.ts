import { sweepLeftoverPostgres } from './backend'

/**
 * Runs once, before any worker exists — the only moment the sweep is safe. It matches every
 * concentus-e2e postmaster on the machine, so running it while workers are alive would shoot
 * their databases out from under them.
 */
export default function globalSetup(): void {
  sweepLeftoverPostgres()
}
