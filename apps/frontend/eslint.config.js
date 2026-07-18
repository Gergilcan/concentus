import { createRequire } from 'node:module'
import path from 'node:path'

// --- typescript-eslint / classic-TS-API compatibility shim -----------------
// The pinned `typescript` dependency (^7.0.2) is the new native/"tsgo" preview
// compiler: its npm package no longer ships the classic JS compiler API
// (ts.createProgram, ts.ModuleKind, ts.Extension, ...) that `typescript-eslint`
// is built on — `require('typescript')` only exposes `{ version, versionMajorMinor }`.
// Downgrading the real `typescript` dependency is out of scope (pinned by the
// backend/build), so instead we install a private, lint-only classic TS copy
// under the alias `typescript-eslint-shim-ts` (see package.json) and redirect
// `require('typescript')` calls made from *inside* @typescript-eslint/* to it.
// This only affects this ESLint process — `tsc -b` / `vite build` still resolve
// the real pinned `typescript` package untouched.
// Remove this shim once typescript-eslint supports the native TS compiler.
const require = createRequire(import.meta.url)
const shimTsDir = path.dirname(require.resolve('typescript-eslint-shim-ts/package.json'))
const nodeModule = require('node:module')
const originalResolveFilename = nodeModule._resolveFilename
nodeModule._resolveFilename = function patchedResolveFilename(request, ...rest) {
  if (request === 'typescript' || request.startsWith('typescript/')) {
    const mapped =
      request === 'typescript' ? shimTsDir : path.join(shimTsDir, request.slice('typescript/'.length))
    return originalResolveFilename.call(this, mapped, ...rest)
  }
  return originalResolveFilename.call(this, request, ...rest)
}
// -----------------------------------------------------------------------------

const js = (await import('@eslint/js')).default
const globals = (await import('globals')).default
const reactHooks = (await import('eslint-plugin-react-hooks')).default
const reactRefresh = (await import('eslint-plugin-react-refresh')).default
const tseslint = (await import('typescript-eslint')).default

export default tseslint.config(
  { ignores: ['dist'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      // eslint-plugin-react-hooks v7 bundles the full React Compiler rule set under
      // "recommended" (purity/immutability/set-state-in-effect/...), which is tuned for
      // codebases written for the compiler from scratch and is too aggressive to retrofit
      // here. Stick to the two well-established hook rules instead.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      ...reactRefresh.configs.vite.rules,
      // unused locals/params are already enforced by tsc (noUnusedLocals/noUnusedParameters
      // in tsconfig.app.json), which correctly understands type-only usage; the ESLint
      // version is redundant and noisier under the classic-TS parsing shim above.
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
)
