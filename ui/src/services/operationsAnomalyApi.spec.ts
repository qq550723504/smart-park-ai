import { afterEach, describe, expect, it, vi } from 'vitest'
import { getAnomalyEvidence } from './operationsAnomalyApi'

afterEach(() => vi.unstubAllGlobals())

describe('operationsAnomalyApi', () => {
  it('rejects a malformed successful evidence response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })))

    await expect(getAnomalyEvidence('ADMIN', 'B1')).rejects.toThrow('异常证据响应格式无效')
  })
})
