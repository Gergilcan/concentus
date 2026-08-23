/**
 * The escape hatch, shared by the two pages the shell shows when the backend will not start
 * (failure-page.ts and license-page.ts): a strip that surfaces a pending update and installs it.
 *
 * <p>It exists for one reason: a refusal to start — a license wall included — must never strand
 * an install on the version that refuses. The updater lives in the shell and keeps working while
 * the backend is down, but its normal UI lives in the app that never loaded; without this strip,
 * "the fix shipped yesterday" would be reachable only by hand-downloading an installer.
 *
 * <p>Self-contained: inline styles (the two host pages have different stylesheets), DOM built via
 * textContent (the version string comes from the update feed and is not this page's to trust as
 * markup), and every failure path stays silent — the strip is an offer, not another error source.
 */
export function updateStrip(): string {
  return `
  <div id="update-strip" style="display:none; margin-top:1.25rem; padding:.65rem .9rem; border-radius:6px; border:1px solid #9db8e8; background:rgba(110,168,254,.12); font-size:13px;"></div>
  <script>
    (function () {
      const strip = document.getElementById('update-strip')
      let installing = false

      function render(state) {
        if (installing || !state || !state.supported) return
        if (state.phase === 'downloaded') {
          strip.style.display = 'block'
          strip.textContent = ''
          const text = document.createElement('span')
          text.textContent = 'Update ' + (state.available || '') + ' is ready — it may fix exactly this. '
          const button = document.createElement('button')
          button.textContent = 'Install and restart'
          button.style.cssText = 'font:inherit; margin-left:.5rem; padding:.3rem .8rem; border-radius:6px; cursor:pointer; border:1px solid #3a3ad6; background:#3a3ad6; color:#fff;'
          button.onclick = async () => {
            installing = true
            button.disabled = true
            button.textContent = 'Installing…'
            try {
              const result = await concentus.installUpdate()
              if (!result.ok) {
                installing = false
                button.disabled = false
                button.textContent = 'Install and restart'
                text.textContent = 'Update could not start: ' + (result.error || 'unknown error') + ' '
              }
            } catch (e) {
              installing = false
              button.disabled = false
              button.textContent = 'Install and restart'
            }
          }
          strip.appendChild(text)
          strip.appendChild(button)
        } else if (state.phase === 'downloading') {
          strip.style.display = 'block'
          strip.textContent = 'Downloading update ' + (state.available || '') + '… '
            + (typeof state.progressPercent === 'number' ? Math.round(state.progressPercent) + '%' : '')
        } else {
          strip.style.display = 'none'
        }
      }

      async function tick() {
        try { render(await concentus.updateStatus()) } catch (e) { /* the strip is an offer */ }
        setTimeout(tick, 3000)
      }
      // Kick a check on arrival: this page IS the moment an update matters most.
      try { concentus.checkForUpdates().catch(() => {}) } catch (e) { /* dev runs have no updater */ }
      tick()
    })()
  </script>`
}
