import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const stylesPath = fileURLToPath(new URL('../src/styles.css', import.meta.url))
const styles = readFileSync(stylesPath, 'utf8')

function findChrome() {
  const candidates = [
    process.env.CHROME_BIN,
    process.platform === 'win32' ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' : null,
    'google-chrome',
    'chromium',
    'chromium-browser',
  ].filter(Boolean)

  for (const candidate of candidates) {
    if (candidate.includes('\\') || candidate.includes('/')) {
      if (existsSync(candidate)) return candidate
      continue
    }
    const lookup = spawnSync(process.platform === 'win32' ? 'where' : 'which', [candidate], { encoding: 'utf8' })
    if (lookup.status === 0) return lookup.stdout.trim().split(/\r?\n/)[0]
  }
  throw new Error('Chrome/Chromium executable not found; set CHROME_BIN to run the layout smoke test')
}

const fixture = `<!doctype html>
<html><head><meta charset="utf-8"><style>${styles}</style></head>
<body>
  <div class="workspace">
    <main class="main-content customer-main" style="min-height:400px">
      <section class="customer-console">
        <aside class="customer-sidebar" style="height:300px"></aside>
        <section class="panel chat-panel" style="height:300px"></section>
      </section>
    </main>
    <aside class="trace-rail panel global-rail" style="height:400px"></aside>
  </div>
  <script>
    const selectors = ['.workspace', '.main-content', '.customer-console', '.customer-sidebar', '.chat-panel', '.global-rail']
    const layout = Object.fromEntries(selectors.map((selector) => {
      const rect = document.querySelector(selector).getBoundingClientRect()
      return [selector, { x: rect.x, y: rect.y, width: rect.width, right: rect.right, bottom: rect.bottom }]
    }))
    document.body.insertAdjacentHTML('beforeend', '<pre id="layout">' + JSON.stringify(layout) + '</pre>')
  </script>
</body></html>`

const chromeUserDataDir = mkdtempSync(join(tmpdir(), 'smart-park-layout-'))

function capture(chrome, width) {
  const page = `data:text/html;base64,${Buffer.from(fixture).toString('base64')}`
  const output = execFileSync(chrome, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    '--no-first-run', '--disable-extensions', `--user-data-dir=${chromeUserDataDir}`,
    `--window-size=${width},900`, '--dump-dom', page,
  ], { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024, timeout: 30000 })
  const matches = [...output.matchAll(/<pre id="layout">([\s\S]*?)<\/pre>/g)]
  const match = matches.at(-1)
  if (!match) throw new Error(`Layout fixture did not produce measurements for ${width}px`) 
  return JSON.parse(match[1])
}

const chrome = findChrome()

try {
  const wide = capture(chrome, 1465)
  if (wide['.main-content'].width < 900 || wide['.chat-panel'].width < 500 || wide['.global-rail'].x - wide['.main-content'].right > 19) {
    throw new Error(`Wide layout did not fill the left workspace column: ${JSON.stringify(wide)}`)
  }

  const stacked = capture(chrome, 1100)
  if (stacked['.global-rail'].y <= stacked['.main-content'].bottom) {
    throw new Error(`Responsive layout did not stack the execution rail: ${JSON.stringify(stacked)}`)
  }

  console.log('Layout smoke test passed: wide fill and <=1250px stacking verified')
} finally {
  rmSync(chromeUserDataDir, { recursive: true, force: true })
}
