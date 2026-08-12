/**
 * Stable hue per agent name — the product's signature: every agent is a voice with its own
 * colour, and that colour is the SAME wherever the agent appears (flow-card dots, console
 * chips, log authors). Lightness is themed separately (--hue-l / --hue-border-l), so the same
 * voice reads on a dark panel and on paper-white alike.
 */
const hueCache = new Map<string, number>()

export function hueOf(name: string): number {
  let h = hueCache.get(name)
  if (h === undefined) {
    h = 0
    for (let i = 0; i < name.length; i += 1) h = (h * 31 + name.charCodeAt(i)) % 360
    // A handful of agent names per session; the map never grows meaningfully.
    hueCache.set(name, h)
  }
  return h
}
