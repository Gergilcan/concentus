/**
 * The cron the schedule builder speaks: plain 5-field expressions (min hour day month weekday),
 * which the backend accepts as-is (ScheduleService prefixes the seconds field for Spring).
 *
 * A preset is the builder's model of a schedule someone can say out loud — "every 15 minutes on
 * working days" — and `build`/`parse` translate between that and cron. Parsing is deliberately
 * humble: anything the presets cannot express round-trips as `custom` and is used verbatim, so
 * the builder never rewrites an expression it does not fully understand.
 */

export type DayScope = 'every' | 'weekdays' | 'weekends'

export type CronPreset =
  | { kind: 'daily'; time: string; days: DayScope }
  | { kind: 'minutes'; n: number; days: DayScope }
  | { kind: 'hours'; n: number; days: DayScope }
  | { kind: 'weekly'; days: number[]; time: string } // days: 0 (Sunday) … 6 (Saturday)
  | { kind: 'monthly'; day: number; time: string }
  | { kind: 'custom'; expression: string }

const SCOPE_TO_DOW: Record<DayScope, string> = { every: '*', weekdays: '1-5', weekends: '0,6' }

function dowToScope(dow: string): DayScope | null {
  if (dow === '*') return 'every'
  if (dow === '1-5') return 'weekdays'
  if (dow === '0,6' || dow === '6,0') return 'weekends'
  return null
}

function hhmm(time: string): [number, number] | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(time)
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  return h <= 23 && min <= 59 ? [h, min] : null
}

const pad = (n: number) => String(n).padStart(2, '0')

export function buildCron(preset: CronPreset): string {
  switch (preset.kind) {
    case 'daily': {
      const t = hhmm(preset.time) ?? [9, 0]
      return `${t[1]} ${t[0]} * * ${SCOPE_TO_DOW[preset.days]}`
    }
    case 'minutes':
      return `*/${clamp(preset.n, 1, 59)} * * * ${SCOPE_TO_DOW[preset.days]}`
    case 'hours':
      return `0 */${clamp(preset.n, 1, 23)} * * ${SCOPE_TO_DOW[preset.days]}`
    case 'weekly': {
      const t = hhmm(preset.time) ?? [9, 0]
      const days = [...new Set(preset.days)].filter((d) => d >= 0 && d <= 6).sort()
      return `${t[1]} ${t[0]} * * ${days.length ? days.join(',') : '*'}`
    }
    case 'monthly': {
      const t = hhmm(preset.time) ?? [9, 0]
      return `${t[1]} ${t[0]} ${clamp(preset.day, 1, 31)} * *`
    }
    case 'custom':
      return preset.expression
  }
}

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.round(n) || lo))

const isNum = (s: string) => /^\d+$/.test(s)

export function parseCron(expression: string): CronPreset {
  const custom: CronPreset = { kind: 'custom', expression }
  let fields = expression.trim().split(/\s+/)
  // The backend also accepts Spring's 6-field form; a leading "0" seconds is dropped so the rest
  // can still be recognised. Any other seconds value is beyond the presets.
  if (fields.length === 6 && fields[0] === '0') fields = fields.slice(1)
  if (fields.length !== 5) return custom
  const [min, hour, dom, month, dow] = fields
  if (month !== '*') return custom
  const scope = dowToScope(dow)

  const stepMin = /^\*\/(\d+)$/.exec(min)
  if (stepMin && hour === '*' && dom === '*' && scope) {
    return { kind: 'minutes', n: Number(stepMin[1]), days: scope }
  }
  const stepHour = /^\*\/(\d+)$/.exec(hour)
  if (min === '0' && stepHour && dom === '*' && scope) {
    return { kind: 'hours', n: Number(stepHour[1]), days: scope }
  }
  // "0 * * * *" reads as hourly — hour '*' is a step of one.
  if (min === '0' && hour === '*' && dom === '*' && scope) {
    return { kind: 'hours', n: 1, days: scope }
  }
  if (isNum(min) && isNum(hour) && Number(min) <= 59 && Number(hour) <= 23) {
    const time = `${pad(Number(hour))}:${pad(Number(min))}`
    if (dom === '*' && scope) return { kind: 'daily', time, days: scope }
    if (dom === '*' && /^[0-7](,[0-7])*$/.test(dow)) {
      // 7 is Sunday's alias; normalised to 0 so the picker has one checkbox to tick.
      const days = [...new Set(dow.split(',').map((d) => Number(d) % 7))].sort()
      return { kind: 'weekly', days, time }
    }
    if (isNum(dom) && Number(dom) >= 1 && Number(dom) <= 31 && dow === '*') {
      return { kind: 'monthly', day: Number(dom), time }
    }
  }
  return custom
}

const DAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

/**
 * How this module says a sentence in the user's language without importing i18n: the render site
 * lends `describeCron` its translate function. The default speaks English — same interpolation
 * shape as i18next, so keys and callers do not fork — which keeps non-UI callers and the tests
 * exactly as they were.
 */
export type CronTranslate = (key: string, options?: Record<string, unknown>) => string

const plainTranslate: CronTranslate = (key, options) =>
  key.replace(/\{\{(\w+)\}\}/g, (match, name) =>
    options && name in options ? String(options[name]) : match,
  )

function scopeSuffix(days: DayScope, t: CronTranslate): string {
  if (days === 'weekdays') return ` ${t('on working days (Mon–Fri)')}`
  if (days === 'weekends') return ` ${t('on weekends')}`
  return ''
}

function listDays(days: number[], t: CronTranslate): string {
  const names = days.map((d) => (DAY_NAMES[d] ? t(DAY_NAMES[d]) : t('day {{d}}', { d })))
  if (names.length === 1) return names[0]
  return names.slice(0, -1).join(', ') + ` ${t('and')} ` + names[names.length - 1]
}

/** The schedule in words, or null when the expression is beyond the presets' vocabulary. */
export function describeCron(expression: string, t: CronTranslate = plainTranslate): string | null {
  const p = parseCron(expression)
  switch (p.kind) {
    case 'daily':
      if (p.days === 'weekdays') return t('Working days (Mon–Fri) at {{time}}', { time: p.time })
      if (p.days === 'weekends') return t('Weekends at {{time}}', { time: p.time })
      return t('Every day at {{time}}', { time: p.time })
    case 'minutes':
      return (
        (p.n === 1 ? t('Every minute') : t('Every {{n}} minutes', { n: p.n })) +
        scopeSuffix(p.days, t)
      )
    case 'hours':
      return (
        (p.n === 1 ? t('Every hour') : t('Every {{n}} hours', { n: p.n })) + scopeSuffix(p.days, t)
      )
    case 'weekly':
      return p.days.length
        ? t('Every {{days}} at {{time}}', { days: listDays(p.days, t), time: p.time })
        : t('Every day at {{time}}', { time: p.time })
    case 'monthly':
      return t('On day {{day}} of every month at {{time}}', { day: p.day, time: p.time })
    case 'custom':
      return null
  }
}
