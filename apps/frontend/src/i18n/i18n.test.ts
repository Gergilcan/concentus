import { afterAll, describe, expect, it } from 'vitest'
import i18n from './index.ts'
import ca from './ca.json'
import es from './es.json'

/**
 * The translation bundles as shipped: both languages carry the same keys, placeholders survive
 * translation, and switching languages actually changes what t() answers. These are the failure
 * modes of a merge from seven extraction batches — a key translated in one language and not the
 * other, or a {{variable}} someone translated away.
 */
describe('i18n bundles', () => {
  afterAll(() => void i18n.changeLanguage('en'))

  it('spanish and catalan cover the same keys', () => {
    const esKeys = Object.keys(es).sort()
    const caKeys = Object.keys(ca).sort()
    expect(caKeys).toEqual(esKeys)
    expect(esKeys.length).toBeGreaterThan(1000)
  })

  it('every translation keeps the placeholders of its key', () => {
    const holes = (s: string) => (s.match(/\{\{\w+\}\}/g) ?? []).sort()
    for (const bundle of [es, ca] as Record<string, string>[]) {
      for (const [key, value] of Object.entries(bundle)) {
        expect(holes(value), `placeholders of: ${key}`).toEqual(holes(key))
      }
    }
  })

  it('no translation is empty or identical-when-it-should-not-be', () => {
    for (const bundle of [es, ca] as Record<string, string>[]) {
      for (const [key, value] of Object.entries(bundle)) {
        expect(value.trim(), `empty translation for: ${key}`).not.toBe('')
      }
    }
  })

  it('switching language switches what t() answers, and English falls back to the key', async () => {
    await i18n.changeLanguage('es')
    expect(i18n.t('Save')).toBe('Guardar')
    await i18n.changeLanguage('ca')
    expect(i18n.t('Save')).toBe('Desa')
    await i18n.changeLanguage('en')
    expect(i18n.t('Save')).toBe('Save')
    expect(i18n.t('A key nobody translated')).toBe('A key nobody translated')
  })
})
