import { afterEach, expect, it, vi } from 'vitest'
import { getShowcaseScenarios, listCollaborationSlaTrend } from './workflowApi'
import type { ShowcaseScenarioCatalog } from './workflowApi'

const originalFetch = globalThis.fetch

afterEach(() => {
  globalThis.fetch = originalFetch
})

it('loads the server-owned showcase catalog without manufacturing readiness', async () => {
  const catalog: ShowcaseScenarioCatalog = {
    capturedAt: '2026-08-30T10:00:00Z',
    scenarios: [{
      id: 'OPERATIONS_ANALYSIS',
      status: 'NOT_READY',
      live: false,
      title: '运营分析',
      businessQuestion: '过去几天哪座楼能耗偏离基线？',
      expectedDurationSeconds: 30,
      requiredCapabilities: ['模型', '只读数据'],
      proofTypes: ['指标口径', '只读查询'],
      humanBoundary: '只读数据，不自动执行操作',
      launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
      unavailableReason: '本次部署尚未完成在线验证',
      lastVerifiedAt: null,
    }],
  }
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(catalog), { status: 200 }))
  globalThis.fetch = fetchMock as typeof fetch

  const result = await getShowcaseScenarios()

  expect(result).toEqual(catalog)
  expect(fetchMock).toHaveBeenCalledWith('/api/showcase/scenarios', expect.any(Object))
})

it('loads the read-only collaboration SLA trend with the demo role', async () => {
  const trend = [{ capturedAt: '2026-09-02T10:00:00Z', total: 4, overdue: 1, dueSoon: 1, onTrack: 2, completed: 0, notApplicable: 0 }]
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(trend), { status: 200 }))
  globalThis.fetch = fetchMock as typeof fetch

  await expect(listCollaborationSlaTrend('ADMIN', 60)).resolves.toEqual(trend)
  expect(fetchMock).toHaveBeenCalledWith('/api/collaboration/sla-trend?limit=60', expect.objectContaining({
    headers: expect.objectContaining({ 'X-Demo-Role': 'ADMIN' }),
  }))
})
