import test from 'node:test'
import assert from 'node:assert/strict'
import { ANNUAL_DISCOUNT, DEFAULT_MONTHLY_PER_SEAT, MAX_TEAM_SEATS, parseSeats, parseTerm, quote, readPricing } from './pricing.mjs'

function quiet(fn) {
  const original = console.error
  console.error = () => {}
  try {
    return fn()
  } finally {
    console.error = original
  }
}

test('no price in the environment: the decided default, 25 a month per seat', () => {
  const p = readPricing({})
  assert.equal(p.monthlyPerSeat, DEFAULT_MONTHLY_PER_SEAT)
  assert.equal(DEFAULT_MONTHLY_PER_SEAT, 25)
  // `off` takes the price down: the page says "to be announced" and checkout refuses.
  assert.equal(readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: 'off' }).monthlyPerSeat, null)
  assert.equal(p.currency, 'eur')
  assert.equal(p.annualDiscount, ANNUAL_DISCOUNT)
  assert.equal(p.maxSeats, MAX_TEAM_SEATS)
})

test('a configured price is read as a number; the currency follows its own variable, lower-cased', () => {
  const p = readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: '12', TEAM_PRICE_CURRENCY: 'USD' })
  assert.equal(p.monthlyPerSeat, 12)
  assert.equal(p.currency, 'usd')
})

test('a price that is not a positive number is treated as no price, not as €NaN or free', () => {
  for (const raw of ['abc', '0', '-5']) {
    assert.equal(quiet(() => readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: raw })).monthlyPerSeat, null, raw)
  }
})

test('quote: monthly is seats × price in integer cents', () => {
  const q = quote({ seats: 3, term: 'monthly' }, readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: '12' }))
  assert.equal(q.unitAmountCents, 1200)
  assert.equal(q.totalCents, 3600)
  assert.equal(q.months, 1)
})

test('quote: annual is twelve months less 20%, rounded once per seat', () => {
  const q = quote({ seats: 3, term: 'annual' }, readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: '12' }))
  assert.equal(q.unitAmountCents, 11520) // 1200 × 12 × 0.8
  assert.equal(q.totalCents, 34560)
  assert.equal(q.months, 12)
  // A price with cents: 9.99 × 12 × 0.8 = 95.904 → 9590 cents, and the total is an exact multiple of it.
  const odd = quote({ seats: 7, term: 'annual' }, readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: '9.99' }))
  assert.equal(odd.unitAmountCents, 9590)
  assert.equal(odd.totalCents, 9590 * 7)
})

test('quote without a configured price throws rather than charging zero', () => {
  assert.throws(() => quote({ seats: 1, term: 'monthly' }, readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: 'off' })), /no price/)
})

test('parseSeats: integers 1..10 only, from a number or a string; anything else is null, never clamped', () => {
  assert.equal(parseSeats(3), 3)
  assert.equal(parseSeats('10'), 10)
  assert.equal(parseSeats('1'), 1)
  for (const bad of [0, 11, '2.5', 'three', '', null, undefined, -1]) assert.equal(parseSeats(bad), null, String(bad))
})

test('parseTerm: monthly or annual, nothing else', () => {
  assert.equal(parseTerm('monthly'), 'monthly')
  assert.equal(parseTerm('annual'), 'annual')
  for (const bad of ['yearly', 'MONTHLY', '', undefined]) assert.equal(parseTerm(bad), null, String(bad))
})
