import { afterEach, expect, it, vi } from 'vitest'
import { cancelOrchestration, getOrchestration, OrchestrationApiError, startOrchestration } from './orchestrationApi'

afterEach(() => vi.unstubAllGlobals())

it('sends role and idempotency headers when starting a typed orchestration', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ runId: 'run-1', status: 'RUNNING' }), { status: 202 }))
  vi.stubGlobal('fetch', fetchMock)
  const input = { question: '检查异常', alertId: null, buildingIds: [], energyRelated: false,
    crossDomain: false, securityRelated: false, requestAction: false }

  await startOrchestration('OPERATOR', 'request-key', input)

  expect(fetchMock).toHaveBeenCalledWith('/api/orchestrations/runs', expect.objectContaining({
    method: 'POST',
    headers: expect.objectContaining({ 'Idempotency-Key': 'request-key', 'X-Demo-Role': 'OPERATOR' }),
  }))
  expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
    definitionId: 'JOINT_ANOMALY_ASSESSMENT', input,
  })
})

it('uses role-scoped status and cancel requests', async () => {
  const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 })))
  vi.stubGlobal('fetch', fetchMock)
  await getOrchestration('VIEWER', 'run id')
  await cancelOrchestration('VIEWER', 'run id')
  expect(fetchMock.mock.calls[0][0]).toBe('/api/orchestrations/runs/run%20id')
  expect(fetchMock.mock.calls[0][1]).toEqual({ headers: { 'X-Demo-Role': 'VIEWER' } })
  expect(fetchMock.mock.calls[1][1]).toEqual({ method: 'POST', headers: { 'X-Demo-Role': 'VIEWER' } })
})

it('preserves the response status on API failures', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'missing' }), {
    status: 404,
    headers: { 'Content-Type': 'application/json' },
  })))

  await expect(getOrchestration('VIEWER', 'stale-run')).rejects.toEqual(
    expect.objectContaining<Partial<OrchestrationApiError>>({ status: 404, message: 'missing' }),
  )
})
