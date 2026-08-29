/**
 * HTML-escaping for the shell's own pages, which are built as strings: the failure page, the
 * license wall and the splash. Everything interpolated into them — an error message, a log tail,
 * a version string that is ours anyway — goes through this, so a stray `<` in a backend log line
 * stays text. Escaping the trusted values too is cheaper than remembering which ones are.
 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
