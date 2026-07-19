/** Joins classNames, skipping falsy values. Replaces manual `${a} ${cond ? b : ''}` concatenation. */
export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ')
}
