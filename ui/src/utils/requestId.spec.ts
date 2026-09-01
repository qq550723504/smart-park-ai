import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRequestId } from './requestId'

describe('createRequestId', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses randomUUID when the browser exposes it', () => {
    vi.stubGlobal('crypto', { randomUUID: vi.fn(() => 'uuid-from-browser') })

    expect(createRequestId()).toBe('uuid-from-browser')
  })

  it('still creates a UUID-shaped key when randomUUID is unavailable', () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.set([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])
      return bytes
    })
    vi.stubGlobal('crypto', { getRandomValues })

    expect(createRequestId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(getRandomValues).toHaveBeenCalledOnce()
  })

  it('falls back when the runtime has no Web Crypto API', () => {
    vi.stubGlobal('crypto', undefined)

    expect(createRequestId()).toMatch(/^request-[a-z0-9-]+$/)
  })
})
