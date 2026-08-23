import '@testing-library/jest-dom/vitest'
// Initialize i18n so useTranslation() resolves in every component test. jsdom reports English,
// so t() returns its key — which is the English string the existing tests assert.
import '../i18n/index.ts'
