import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import ca from './ca.json'
import es from './es.json'

/**
 * The interface in the user's language — Spanish and Catalan first, because that is who builds
 * with this today.
 *
 * The keys ARE the English strings. Chosen over symbolic keys (`toolbar.save`) deliberately:
 * English remains written where it is read, a missing translation falls back to something a
 * person can use rather than a dotted path, and the hundreds of existing tests that assert
 * English text keep asserting exactly what the default UI shows. The cost — renaming an English
 * string orphans its translations — is paid at translation time, where it is visible, instead of
 * at runtime, where it is not.
 */

export const LANGS = [
  { id: 'auto', label: 'Auto' },
  { id: 'en', label: 'English' },
  { id: 'es', label: 'Español' },
  { id: 'ca', label: 'Català' },
] as const

export type LangId = (typeof LANGS)[number]['id']

const STORE_KEY = 'concentus.lang'

/** The stored choice, 'auto' when the person never chose. */
export function storedLang(): LangId {
  const raw = localStorage.getItem(STORE_KEY)
  return LANGS.some((l) => l.id === raw) ? (raw as LangId) : 'auto'
}

/** What 'auto' means on this machine: the browser's language, if we speak it. */
function autoLang(): string {
  const nav = (navigator.language || 'en').toLowerCase()
  if (nav.startsWith('es')) return 'es'
  if (nav.startsWith('ca')) return 'ca'
  return 'en'
}

export function setLang(id: LangId) {
  localStorage.setItem(STORE_KEY, id)
  void i18n.changeLanguage(id === 'auto' ? autoLang() : id)
}

void i18n.use(initReactI18next).init({
  resources: {
    es: { translation: es },
    ca: { translation: ca },
  },
  lng: storedLang() === 'auto' ? autoLang() : storedLang(),
  fallbackLng: 'en',
  // The keys are sentences: '.' and ':' appear inside them and must not be parsed as structure.
  keySeparator: false,
  nsSeparator: false,
  interpolation: {
    // React already escapes; double-escaping would print &#39; into the UI.
    escapeValue: false,
  },
  // With English-as-key, an untranslated string is not an error — it is the English UI.
  returnEmptyString: false,
})

export default i18n
