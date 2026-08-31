// @vitest-environment node
import { describe, expect, it } from 'vitest'
import config from '../vite.config'

interface ProxyEntry {
  target?: string
  changeOrigin?: boolean
  ws?: boolean
}

describe('Vite backend proxy', () => {
  it('forwards HTTP APIs and voice WebSockets to the same backend', () => {
    const resolved = config as {
      server?: { proxy?: Record<string, ProxyEntry> }
    }
    const proxy = resolved.server?.proxy

    expect(proxy?.['/api']).toMatchObject({ changeOrigin: true })
    expect(proxy?.['/ws']).toMatchObject({
      target: proxy?.['/api']?.target,
      changeOrigin: true,
      ws: true,
    })
  })
})
