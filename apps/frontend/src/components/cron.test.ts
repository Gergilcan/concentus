import { describe, expect, it } from 'vitest'
import { buildCron, describeCron, parseCron, type CronPreset } from './cron.ts'

describe('buildCron', () => {
  it.each<[CronPreset, string]>([
    [{ kind: 'daily', time: '09:00', days: 'every' }, '0 9 * * *'],
    [{ kind: 'daily', time: '18:30', days: 'weekdays' }, '30 18 * * 1-5'],
    [{ kind: 'daily', time: '08:15', days: 'weekends' }, '15 8 * * 0,6'],
    [{ kind: 'minutes', n: 15, days: 'every' }, '*/15 * * * *'],
    [{ kind: 'minutes', n: 5, days: 'weekdays' }, '*/5 * * * 1-5'],
    [{ kind: 'hours', n: 2, days: 'weekends' }, '0 */2 * * 0,6'],
    [{ kind: 'weekly', days: [1, 4], time: '08:30' }, '30 8 * * 1,4'],
    [{ kind: 'monthly', day: 1, time: '07:00' }, '0 7 1 * *'],
    [{ kind: 'custom', expression: '0 0 29 2 *' }, '0 0 29 2 *'],
  ])('builds %j as "%s"', (preset, expected) => {
    expect(buildCron(preset)).toBe(expected)
  })

  it('clamps out-of-range values instead of emitting broken cron', () => {
    expect(buildCron({ kind: 'minutes', n: 900, days: 'every' })).toBe('*/59 * * * *')
    expect(buildCron({ kind: 'monthly', day: 45, time: '09:00' })).toBe('0 9 31 * *')
  })
})

describe('parseCron', () => {
  it('round-trips everything the presets can say', () => {
    const presets: CronPreset[] = [
      { kind: 'daily', time: '09:00', days: 'every' },
      { kind: 'daily', time: '18:30', days: 'weekdays' },
      { kind: 'minutes', n: 15, days: 'every' },
      { kind: 'hours', n: 2, days: 'weekends' },
      { kind: 'weekly', days: [1, 4], time: '08:30' },
      { kind: 'monthly', day: 1, time: '07:00' },
    ]
    for (const preset of presets) {
      expect(parseCron(buildCron(preset))).toEqual(preset)
    }
  })

  it('reads the seeded samples the way a person would', () => {
    // The daily-briefing starter: 07:00 on working days.
    expect(parseCron('0 7 * * 1-5')).toEqual({ kind: 'daily', time: '07:00', days: 'weekdays' })
    // Top of every hour.
    expect(parseCron('0 * * * *')).toEqual({ kind: 'hours', n: 1, days: 'every' })
  })

  it('accepts the 6-field Spring form when the seconds are zero', () => {
    expect(parseCron('0 0 9 * * *')).toEqual({ kind: 'daily', time: '09:00', days: 'every' })
    expect(parseCron('30 0 9 * * *').kind).toBe('custom')
  })

  it('normalises Sunday-as-7 to the same checkbox as Sunday-as-0', () => {
    expect(parseCron('0 9 * * 7')).toEqual({ kind: 'weekly', days: [0], time: '09:00' })
  })

  it('leaves anything beyond the presets untouched as custom', () => {
    for (const raw of ['0 0 29 2 *', '0 9 * 6 *', '*/10 9-17 * * *', 'not cron at all', '']) {
      expect(parseCron(raw)).toEqual({ kind: 'custom', expression: raw })
    }
  })
})

describe('describeCron', () => {
  it.each([
    ['0 9 * * *', 'Every day at 09:00'],
    ['30 18 * * 1-5', 'Working days (Mon–Fri) at 18:30'],
    ['15 8 * * 0,6', 'Weekends at 08:15'],
    ['*/15 * * * *', 'Every 15 minutes'],
    ['*/5 * * * 1-5', 'Every 5 minutes on working days (Mon–Fri)'],
    ['0 */2 * * 0,6', 'Every 2 hours on weekends'],
    ['0 * * * *', 'Every hour'],
    ['30 8 * * 1,4', 'Every Monday and Thursday at 08:30'],
    ['0 7 1 * *', 'On day 1 of every month at 07:00'],
  ])('describes "%s" as "%s"', (expr, text) => {
    expect(describeCron(expr)).toBe(text)
  })

  it('admits what it cannot read', () => {
    expect(describeCron('0 0 29 2 *')).toBeNull()
  })
})
