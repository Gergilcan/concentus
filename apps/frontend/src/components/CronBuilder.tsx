import { useEffect, useState } from 'react'
import { cx } from '../utils/cx.ts'
import { buildCron, describeCron, parseCron, type CronPreset, type DayScope } from './cron.ts'
import { Field, SelectField } from './fields.tsx'
import styles from './panels.module.scss'

/**
 * A schedule you can say out loud — "every 15 minutes on working days" — instead of a cron
 * expression you had to already know. The builder edits a preset and writes plain 5-field cron
 * through `onChange`; anything it cannot express is a "Custom" preset edited verbatim, so cron
 * literates lose nothing. The live summary states both the expression and its meaning, which is
 * also how the builder earns trust: you can check its work.
 */
export function CronBuilder({ value, onChange }: { value: string; onChange: (cron: string) => void }) {
  const [preset, setPreset] = useState<CronPreset>(() =>
    value.trim() === '' ? { kind: 'daily', time: '09:00', days: 'every' } : parseCron(value),
  )

  // Selecting another node re-renders the same mounted inspector with new data; when the value
  // stops matching what this preset would build, re-derive instead of showing the old node's
  // schedule. User edits always match their own build output, so they never trip this.
  useEffect(() => {
    if (value.trim() !== '' && buildCron(preset) !== value) setPreset(parseCron(value))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])

  const apply = (next: CronPreset) => {
    setPreset(next)
    onChange(buildCron(next))
  }

  const changeKind = (kind: string) => {
    // Fresh defaults per shape; the previous fields rarely translate and half-carried state
    // (weekly days surviving into monthly) reads as a bug.
    const time = 'time' in preset ? preset.time : '09:00'
    const days: DayScope = 'days' in preset && !Array.isArray(preset.days) ? preset.days : 'every'
    const next: CronPreset =
      kind === 'daily' ? { kind, time, days }
      : kind === 'minutes' ? { kind, n: 15, days }
      : kind === 'hours' ? { kind, n: 2, days }
      : kind === 'weekly' ? { kind, days: [1], time }
      : kind === 'monthly' ? { kind, day: 1, time }
      : { kind: 'custom', expression: value }
    apply(next)
  }

  const expression = buildCron(preset)
  const meaning = describeCron(expression)

  return (
    <>
      <SelectField label="Schedule" value={preset.kind} onChange={changeKind}>
        <option value="daily">At a fixed time</option>
        <option value="minutes">Every N minutes</option>
        <option value="hours">Every N hours</option>
        <option value="weekly">Weekly, on chosen days</option>
        <option value="monthly">Monthly, on a day</option>
        <option value="custom">Custom (cron expression)</option>
      </SelectField>

      {(preset.kind === 'minutes' || preset.kind === 'hours') && (
        <Field
          label={preset.kind === 'minutes' ? 'Every how many minutes' : 'Every how many hours'}
          type="number"
          value={preset.n}
          onChange={(v) => apply({ ...preset, n: Number(v) || 1 })}
        />
      )}

      {(preset.kind === 'daily' || preset.kind === 'weekly' || preset.kind === 'monthly') && (
        <Field
          label="At"
          type="time"
          value={preset.time}
          onChange={(v) => v && apply({ ...preset, time: v })}
        />
      )}

      {(preset.kind === 'daily' || preset.kind === 'minutes' || preset.kind === 'hours') && (
        <SelectField
          label="On which days"
          value={preset.days}
          onChange={(v) => apply({ ...preset, days: v as DayScope })}
        >
          <option value="every">Every day</option>
          <option value="weekdays">Working days (Mon–Fri)</option>
          <option value="weekends">Weekends</option>
        </SelectField>
      )}

      {preset.kind === 'weekly' && (
        <div className={styles.dayRow} role="group" aria-label="Days of the week">
          {(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const).map((name, i) => {
            const day = (i + 1) % 7 // Mon=1 … Sat=6, Sun=0
            const on = preset.days.includes(day)
            return (
              <button
                key={name}
                type="button"
                className={cx(styles.dayChip, on && styles.dayChipOn)}
                aria-pressed={on}
                onClick={() => {
                  const days = on ? preset.days.filter((d) => d !== day) : [...preset.days, day]
                  // An empty week never fires silently — keep the last day selected.
                  if (days.length > 0) apply({ ...preset, days })
                }}
              >
                {name}
              </button>
            )
          })}
        </div>
      )}

      {preset.kind === 'monthly' && (
        <Field
          label="Day of the month"
          type="number"
          value={preset.day}
          onChange={(v) => apply({ ...preset, day: Number(v) || 1 })}
        />
      )}

      {preset.kind === 'custom' && (
        <Field
          label="Cron expression"
          value={preset.expression}
          placeholder="0 9 * * *"
          onChange={(v) => apply({ kind: 'custom', expression: v })}
        />
      )}

      <p className={styles.hint}>
        <code>{expression || '—'}</code>
        {meaning
          ? ` — ${meaning}`
          : preset.kind === 'custom' && expression
            ? ' — used as written. 5-field (min hour day month weekday) or 6-field cron.'
            : ''}
        {value.trim() === '' && expression && ' (adjust any control to set it)'}
      </p>
    </>
  )
}
