import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
// The display face (brand, page titles, KPI figures). Vendored through @fontsource so the
// packaged app renders it offline — no CDN, no CSP exception. Two weights only on purpose.
import '@fontsource/space-grotesk/500.css'
import '@fontsource/space-grotesk/700.css'
import './styles/global.scss'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
