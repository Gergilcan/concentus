/** Renders a caught value (usually an Error, but not guaranteed) as a user-facing string. */
export function errMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
