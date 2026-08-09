/** Wall-clock time for a log line. One definition: Console and NodeLogView had it twice. */
export function clockTime(ts: number): string {
  return new Date(ts).toLocaleTimeString()
}

/** Sub-cent costs are common per block, so don't round them away to $0.00. */
export function money(n: number): string {
  if (!n) return '$0'
  if (n < 0.01) return '<$0.01'
  return `$${n.toFixed(2)}`
}
