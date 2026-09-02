import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { ref } from 'vue'
import OperationsDailyReport from './OperationsDailyReport.vue'
import type { ExecutionEvent } from '../../types/execution'

describe('OperationsDailyReport', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

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

  it('keeps the previous snapshot when a replacement report is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ runId: 'report-1', statusUrl: '/api/operations-reports/runs/report-1' }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        runId: 'report-1', status: 'COMPLETED', createdAt: '2026-09-02T00:00:00Z', updatedAt: '2026-09-02T00:01:00Z',
        sections: [{ id: 'ENERGY_BASELINE', title: '已完成日报', question: '问题', status: 'COMPLETED', summary: '仍然有效的摘要', rowCount: 0, truncated: false, columns: [], rows: [] }],
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: '已有正在生成的运营日报' }), { status: 409 })))

    const wrapper = mount(OperationsDailyReport, { props: { role: 'ADMIN', pollIntervalMs: 1 } })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('仍然有效的摘要')

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="report-body"]').text()).toContain('已完成日报')
    expect(wrapper.get('[data-testid="report-error"]').text()).toContain('已有正在生成的运营日报')
  })

  it('restores the accepted report trace when returning to the operations view', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ runId: 'report-1', statusUrl: '/api/operations-reports/runs/report-1' }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        runId: 'report-1', status: 'COMPLETED', createdAt: '2026-09-02T00:00:00Z', updatedAt: '2026-09-02T00:01:00Z',
        sections: [],
      }), { status: 200 })))
    const trace = { events: ref<ExecutionEvent[]>([]), subscribe: vi.fn() }
    const wrapper = mount(OperationsDailyReport, { props: { role: 'ADMIN', active: true, trace, pollIntervalMs: 1 } })

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(trace.subscribe).toHaveBeenNthCalledWith(1, 'report-1')

    await wrapper.setProps({ active: false })
    trace.subscribe('other-run')
    await wrapper.setProps({ active: true })

    expect(trace.subscribe).toHaveBeenNthCalledWith(3, 'report-1')
  })

  it('renders each completed section time resolution, including empty periods', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ runId: 'report-1', statusUrl: '/api/operations-reports/runs/report-1' }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        runId: 'report-1', status: 'COMPLETED', createdAt: '2026-09-02T00:00:00Z', updatedAt: '2026-09-02T00:01:00Z',
        sections: [
          {
            id: 'ENERGY_BASELINE', title: '显式时间范围', question: '昨天能耗', status: 'COMPLETED', summary: '', rowCount: 0, truncated: false,
            columns: [], rows: [], timeResolution: {
              status: 'PARSED', fromInclusive: '2026-09-01T00:00:00Z', toExclusive: '2026-09-02T00:00:00Z',
              source: 'EXPLICIT_USER_RANGE', explanation: '已按您指定的时间范围查询', empty: false,
            },
          },
          {
            id: 'ALERT_RISK', title: '空周期', question: '当前告警', status: 'COMPLETED', summary: '', rowCount: 0, truncated: false,
            columns: [], rows: [], timeResolution: {
              status: 'EMPTY', fromInclusive: '2026-09-02T00:00:00Z', toExclusive: '2026-09-02T00:00:00Z',
              source: 'EXPLICIT_USER_RANGE', explanation: '当前周期刚开始，暂无数据', empty: true,
            },
          },
        ],
      }), { status: 200 })))

    const wrapper = mount(OperationsDailyReport, { props: { role: 'ADMIN', pollIntervalMs: 1 } })
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const ranges = wrapper.findAll('[data-testid="section-time-resolution"]')
    expect(ranges).toHaveLength(2)
    expect(ranges[0].text()).toContain('已指定')
    expect(ranges[0].text()).toContain('已按您指定的时间范围查询')
    expect(ranges[0].attributes('data-from')).toBe('2026-09-01T00:00:00Z')
    expect(ranges[0].attributes('data-to')).toBe('2026-09-02T00:00:00Z')
    expect(ranges[1].text()).toContain('空周期')
    expect(ranges[1].text()).toContain('当前周期刚开始，暂无数据')
    expect(ranges[1].text()).not.toContain('2026-09-02T00:00:00Z')
  })
})
