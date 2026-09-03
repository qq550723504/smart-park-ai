import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const styleUrls = [
  new URL('../src/styles/showcase-theme.css', import.meta.url),
  new URL('../src/styles/workbench-primitives.css', import.meta.url),
  new URL('../src/components/workbench/immersive-workbench.css', import.meta.url),
  new URL('../src/components/customer-service.css', import.meta.url),
]
const styles = styleUrls
  .map((url) => readFileSync(fileURLToPath(url), 'utf8'))
  .join('\n')

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
<html><head><meta charset="utf-8"><style>.el-select { width: 240px; }</style><style>${styles}</style></head>
<body>
  <div class="immersive-workbench">
    <header class="immersive-workbench__topbar">
      <div class="immersive-workbench__brand">智慧园区智能运营中心</div>
      <nav class="immersive-workbench__nav"></nav>
      <div class="immersive-workbench__actions"><div class="el-select">角色</div><button type="button">返回展示首页</button></div>
    </header>
    <div class="immersive-workbench__workspace">
      <section class="immersive-workbench__stage">
        <main class="main-content customer-main" style="min-height:400px">
          <section class="customer-console">
            <aside class="customer-sidebar panel" style="height:300px"></aside>
            <section class="panel chat-panel" style="height:300px"></section>
          </section>
        </main>
      </section>
      <details class="immersive-workbench__rail" open>
        <summary>执行轨迹</summary>
        <div class="immersive-workbench__rail-content">
          <aside class="trace-rail panel global-rail" style="height:400px"></aside>
        </div>
      </details>
    </div>
  </div>
  <script>
    const selectors = ['.immersive-workbench__workspace', '.immersive-workbench__stage', '.main-content', '.customer-console', '.customer-sidebar', '.chat-panel', '.immersive-workbench__rail', '.global-rail', '.immersive-workbench__actions', '.immersive-workbench__actions .el-select', '.immersive-workbench__actions button']
    const layout = Object.fromEntries(selectors.map((selector) => {
      const rect = document.querySelector(selector).getBoundingClientRect()
      return [selector, { x: rect.x, y: rect.y, width: rect.width, right: rect.right, bottom: rect.bottom }]
    }))
    document.body.insertAdjacentHTML('beforeend', '<pre id="layout">' + JSON.stringify(layout) + '</pre>')
  </script>
</body></html>`

function capture(chrome, width, layoutWidth = width) {
  const layoutFixture = fixture.replace(
    '<div class="immersive-workbench">',
    `<div class="immersive-workbench" style="width:${layoutWidth}px">`,
  )
  const page = `data:text/html;base64,${Buffer.from(layoutFixture).toString('base64')}`
  let lastError
  // Chrome occasionally takes longer to exit on shared GitHub-hosted runners
  // (usually while emitting harmless DBus errors). Use a fresh profile and a
  // bounded retry so this smoke test does not fail due to runner noise.
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const chromeUserDataDir = mkdtempSync(join(tmpdir(), 'smart-park-layout-'))
    try {
      const output = execFileSync(chrome, [
        '--headless=new', '--disable-gpu', '--disable-dev-shm-usage', '--no-sandbox', '--hide-scrollbars',
        '--no-first-run', '--disable-extensions', '--disable-background-networking',
        `--user-data-dir=${chromeUserDataDir}`, `--window-size=${width},900`, '--dump-dom', page,
      ], { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024, timeout: 45000 })
      const matches = [...output.matchAll(/<pre id="layout">([\s\S]*?)<\/pre>/g)]
      const match = matches.at(-1)
      if (!match) throw new Error(`Layout fixture did not produce measurements for ${width}px`)
      return JSON.parse(match[1])
    } catch (error) {
      lastError = error
      if (attempt < 3) continue
      throw error
    } finally {
      rmSync(chromeUserDataDir, { recursive: true, force: true })
    }
  }
  throw lastError
}

const chrome = findChrome()

try {
  const wide = capture(chrome, 1465)
  if (wide['.immersive-workbench__stage'].width < 900 || wide['.chat-panel'].width < 500 || wide['.immersive-workbench__rail'].x - wide['.immersive-workbench__stage'].right > 19) {
    throw new Error(`Wide layout did not fill the left workspace column: ${JSON.stringify(wide)}`)
  }

  const stacked = capture(chrome, 1100)
  if (stacked['.immersive-workbench__rail'].y <= stacked['.immersive-workbench__stage'].bottom) {
    throw new Error(`Responsive layout did not stack the execution rail: ${JSON.stringify(stacked)}`)
  }

  // Headless Chrome clamps its viewport to 500px, so render a 320px shell
  // within that mobile media-query viewport to exercise the actual layout.
  const phone = capture(chrome, 500, 320)
  if (phone['.immersive-workbench__actions'].right > 320
    || phone['.immersive-workbench__actions .el-select'].right > 320
    || phone['.immersive-workbench__actions button'].right > 320) {
    throw new Error(`Phone action controls overflow at 320px: ${JSON.stringify(phone)}`)
  }

  console.log('Layout smoke test passed: wide fill, <=1250px stacking, and 320px actions verified')
} finally {
  // Each capture owns and removes its temporary Chrome profile.
}
