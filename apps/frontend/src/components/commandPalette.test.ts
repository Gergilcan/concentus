import { describe, expect, it, vi } from 'vitest'
import { filterCommands, type Command } from './commandPalette.ts'

function command(label: string, group = 'Flows'): Command {
  return { id: `${group}:${label}`, group, label, run: vi.fn() }
}

describe('filterCommands', () => {
  it('keeps the caller’s order when nothing has been typed', () => {
    // The palette opens on what its caller thought was most useful, not on an alphabetical list.
    const commands = [command('Zebra'), command('Apple')]

    expect(filterCommands(commands, '').map((c) => c.label)).toEqual(['Zebra', 'Apple'])
    expect(filterCommands(commands, '   ').map((c) => c.label)).toEqual(['Zebra', 'Apple'])
  })

  it('matches initials, not just prefixes', () => {
    // The promise is "reach anything in a few keystrokes", and that only holds if the keystrokes
    // can be the initials of what you want.
    const commands = [command('Mail triage'), command('Docs from code')]

    expect(filterCommands(commands, 'mtr').map((c) => c.label)).toEqual(['Mail triage'])
    expect(filterCommands(commands, 'dfc').map((c) => c.label)).toEqual(['Docs from code'])
  })

  it('ranks the tighter match first', () => {
    const commands = [command('Monthly transfer report'), command('Mail triage')]

    expect(filterCommands(commands, 'mtr')[0].label).toEqual('Mail triage')
  })

  it('matches the group too, so "flow d" finds a flow called D', () => {
    const commands = [command('Daily briefing', 'Flows'), command('Dark', 'Theme')]

    expect(filterCommands(commands, 'flow d').map((c) => c.label)).toEqual(['Daily briefing'])
  })

  it('ignores case and drops what does not match at all', () => {
    const commands = [command('Mail triage'), command('Usage')]

    expect(filterCommands(commands, 'MAIL').map((c) => c.label)).toEqual(['Mail triage'])
    expect(filterCommands(commands, 'zzz')).toEqual([])
  })

  it('needs the letters in order, which is what makes the ranking mean anything', () => {
    expect(filterCommands([command('Mail triage')], 'egairt')).toEqual([])
  })
})
