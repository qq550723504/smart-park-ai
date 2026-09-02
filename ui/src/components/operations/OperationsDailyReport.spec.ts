import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { ref } from 'vue'
import OperationsDailyReport from './OperationsDailyReport.vue'
import type { ExecutionEvent } from '../../types/execution'

describe('OperationsDailyReport', () => {
  afterEach(() => vi.restoreAllMocks())

  it('is role-gated and renders the backend snapshot without inventing values', async () => {
    const viewer = mount(OperationsDailyReport, { props: { role: 'VIEWER' } })
    expect(viewer.find('[data-testid="operations-daily-report"]').exists()).toBe(false)

    const subscribed: string[] = []
    const trace = {
      events: ref<ExecutionEvent[]>([]),
      subscribe: (runId: string) => subscribed.push(runId),
    }
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return Promise.resolve(new Response(JSON.stringify({ runId: 'report-1', statusUrl: '/api/operations-reports/runs/report-1' }), { status: 202 }))
      }
      return Promise.resolve(new Response(JSON.stringify({
        runId: 'report-1', status: 'COMPLETED', createdAt: '2026-09-02T00:00:00Z', updatedAt: '2026-09-02T00:01:00Z',
        sections: [{ id: 'ENERGY_BASELINE', title: '能耗基线偏差', question: '过去5天各楼宇能耗基线偏差', status: 'COMPLETED', summary: '后端摘要', rowCount: 1, truncated: false, columns: ['building'], rows: [['A1']] }],
      }), { status: 200 }))
    }))

    const wrapper = mount(OperationsDailyReport, { props: { role: 'ADMIN', trace, pollIntervalMs: 1 } })
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(subscribed).toEqual(['report-1'])
    expect(wrapper.get('[data-testid="report-body"]').text()).toContain('能耗基线偏差')
    expect(wrapper.text()).toContain('后端摘要')
    expect(wrapper.text()).toContain('A1')
    expect(wrapper.text()).not.toContain('38%')
  })
})
