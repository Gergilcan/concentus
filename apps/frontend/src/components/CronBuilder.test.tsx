import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CronBuilder } from './CronBuilder.tsx'

// A schedule you can say out loud. The builder writes plain 5-field cron and shows both the
// expression and its meaning, so the person can check its work; anything beyond the presets is
// "Custom" and used verbatim.
describe('CronBuilder', () => {
  it('starts an empty schedule at 09:00 every day, and says nothing is set until a control moves', () => {
    const onChange = vi.fn()
    render(<CronBuilder value="" onChange={onChange} />)

    expect(screen.getByLabelText('Schedule')).toHaveValue('daily')
    expect(screen.getByLabelText('At')).toHaveValue('09:00')
    expect(screen.getByLabelText('On which days')).toHaveValue('every')
    expect(screen.getByText('0 9 * * *')).toBeInTheDocument()
    expect(screen.getByText(/Every day at 09:00 \(adjust any control to set it\)/)).toBeInTheDocument()
    // Nothing written until the person moves something: an empty schedule stays empty.
    expect(onChange).not.toHaveBeenCalled()
  })

  it('reads an existing expression back into its controls and says what it means', () => {
    render(<CronBuilder value="*/15 * * * 1-5" onChange={vi.fn()} />)

    expect(screen.getByLabelText('Schedule')).toHaveValue('minutes')
    expect(screen.getByLabelText('Every how many minutes')).toHaveValue(15)
    expect(screen.getByLabelText('On which days')).toHaveValue('weekdays')
    expect(screen.getByText(/Every 15 minutes on working days \(Mon–Fri\)/)).toBeInTheDocument()
  })

  it('a time or day-scope change writes the cron straight away', () => {
    const onChange = vi.fn()
    render(<CronBuilder value="0 9 * * *" onChange={onChange} />)

    fireEvent.change(screen.getByLabelText('At'), { target: { value: '08:30' } })
    expect(onChange).toHaveBeenLastCalledWith('30 8 * * *')
    fireEvent.change(screen.getByLabelText('On which days'), { target: { value: 'weekends' } })
    expect(onChange).toHaveBeenLastCalledWith('30 8 * * 0,6')
    expect(screen.getByText(/Weekends at 08:30/)).toBeInTheDocument()
  })

  it('switching shape starts from that shape’s own defaults, carrying over only what both shapes share', () => {
    const onChange = vi.fn()
    render(<CronBuilder value="30 8 * * 1-5" onChange={onChange} />)

    // Daily → monthly: both have a time, so 08:30 survives; the weekday scope has no home here.
    fireEvent.change(screen.getByLabelText('Schedule'), { target: { value: 'monthly' } })
    expect(onChange).toHaveBeenLastCalledWith('30 8 1 * *')
    fireEvent.change(screen.getByLabelText('Day of the month'), { target: { value: '15' } })
    expect(onChange).toHaveBeenLastCalledWith('30 8 15 * *')
    expect(screen.getByText(/On day 15 of every month at 08:30/)).toBeInTheDocument()

    // Monthly → hours: no time, no scope — every N hours, every day, from the shape's own default.
    fireEvent.change(screen.getByLabelText('Schedule'), { target: { value: 'hours' } })
    expect(onChange).toHaveBeenLastCalledWith('0 */2 * * *')
    expect(screen.getByLabelText('Every how many hours')).toHaveValue(2)
    expect(screen.getByLabelText('On which days')).toHaveValue('every')
  })

  it('weekly: the day chips toggle, the days are listed in words, and the last day cannot be switched off', () => {
    const onChange = vi.fn()
    render(<CronBuilder value="0 9 * * 1" onChange={onChange} />)

    const days = screen.getByRole('group', { name: 'Days of the week' })
    expect(days).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Mon' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Fri' })).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(screen.getByRole('button', { name: 'Fri' }))
    expect(onChange).toHaveBeenLastCalledWith('0 9 * * 1,5')
    expect(screen.getByText(/Every Monday and Friday at 09:00/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Mon' }))
    expect(onChange).toHaveBeenLastCalledWith('0 9 * * 5')
    // An empty week would never fire, silently: the last day stays.
    fireEvent.click(screen.getByRole('button', { name: 'Fri' }))
    expect(onChange).toHaveBeenLastCalledWith('0 9 * * 5')
    expect(screen.getByRole('button', { name: 'Fri' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('an expression the presets cannot say is Custom, edited verbatim and labelled as used as written', () => {
    const onChange = vi.fn()
    render(<CronBuilder value="0 0 1 1 *" onChange={onChange} />)

    expect(screen.getByLabelText('Schedule')).toHaveValue('custom')
    expect(screen.getByLabelText('Cron expression')).toHaveValue('0 0 1 1 *')
    expect(screen.getByText(/used as written\. 5-field/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Cron expression'), { target: { value: '0 0 1 6 *' } })
    expect(onChange).toHaveBeenLastCalledWith('0 0 1 6 *')
  })

  it('clamps a step to what cron can express', () => {
    const onChange = vi.fn()
    render(<CronBuilder value="*/15 * * * *" onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('Every how many minutes'), { target: { value: '90' } })
    expect(onChange).toHaveBeenLastCalledWith('*/59 * * * *')
  })

  it('follows a value that changes under it — selecting another node — instead of showing the old schedule', () => {
    const { rerender } = render(<CronBuilder value="0 9 * * *" onChange={vi.fn()} />)
    expect(screen.getByLabelText('Schedule')).toHaveValue('daily')

    rerender(<CronBuilder value="0 */3 * * *" onChange={vi.fn()} />)
    expect(screen.getByLabelText('Schedule')).toHaveValue('hours')
    expect(screen.getByLabelText('Every how many hours')).toHaveValue(3)
    expect(screen.getByText(/Every 3 hours/)).toBeInTheDocument()
  })
})
