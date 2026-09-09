import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import OperationsDailyReport from './OperationsDailyReport.vue'
import type { ExecutionEvent } from '../../types/execution'

const summary = {
  reportId: 'report-1', reportType: 'OPERATIONS_DAILY', title: '智慧园区运营日报', status: 'COMPLETED',
  createdAt: '2026-09-09T01:00:00Z', completedAt: '2026-09-09T01:01:00Z',
  timeWindow: { fromInclusive: '2026-09-04T01:00:00Z', toExclusive: '2026-09-09T01:00:00Z' },
  timezone: 'Asia/Shanghai', asOf: '2026-09-09T01:00:00Z', runId: 'run-1', traceId: 'run-1', downloadAvailable: true,
} as const

const detail = {
  ...summary, requestedBy: 'demo-role:OPERATOR', role: 'OPERATOR', startedAt: summary.createdAt,
  summary: '基于生成时证据的摘要', schemaVersion: 1, generationVersion: 'operations-daily-v2',
  sections: [{ sectionId: 'ENERGY_BASELINE', title: '能耗基线偏差', question: '过去5天各楼宇能耗基线偏差', status: 'COMPLETED',
    summary: '后端摘要', rowCount: 1, truncated: false, columns: ['building', 'value'], rows: [['B1', 100]],
    timeResolution: { status: 'PARSED', fromInclusive: '2026-09-04T01:00:00Z', toExclusive: '2026-09-09T01:00:00Z', source: 'EXPLICIT_USER_RANGE', explanation: '已按报告窗口查询', empty: false },
    evidenceReferences: [], sourceReferences: [], partialReason: null, failureReason: null, runId: 'analysis-1' }],
  evidence: [{ sourceSystem: 'OPERATIONS_ANALYTICS', metric: 'energy_deviation_pct', entity: 'REPORT_SECTION:ENERGY_BASELINE', observationTime: '2026-09-09T01:00:00Z', runReference: 'analysis-1', summary: '已保存 1 行生成时结果' }],
  sourceReferences: [],
} as const

describe('OperationsDailyReport', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('is role gated and restores durable history before opening snapshot detail', async () => {
    const viewer = mount(OperationsDailyReport, { props: { role: 'VIEWER' } })
    expect(viewer.find('[data-testid="operations-daily-report"]').exists()).toBe(false)
    viewer.unmount()

    const trace = { events: ref<ExecutionEvent[]>([]), subscribe: vi.fn() }
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify({ content: [summary], page: 0, size: 20, totalElements: 1, hasNext: false }), { status: 200 }))
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR', trace } })
    await flushPromises()

    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('1 份')
    await wrapper.get('[data-testid="report-history"] button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="report-body"]').text()).toContain('后端摘要')
    expect(wrapper.text()).toContain('100')
    expect(wrapper.text()).not.toContain('38%')
    expect(trace.subscribe).toHaveBeenCalledWith('run-1')
  })

  it('shows real generating state then refreshes history after terminal snapshot', async () => {
    let detailReads = 0
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify({ content: detailReads ? [summary] : [], page: 0, size: 20, totalElements: detailReads ? 1 : 0, hasNext: false }), { status: 200 }))
      if (init?.method === 'POST') return Promise.resolve(new Response(JSON.stringify({ reportId: 'report-1', runId: 'run-1', statusUrl: '/api/operations-reports/report-1' }), { status: 202 }))
      detailReads += 1
      if (detailReads === 1) return Promise.resolve(new Response(JSON.stringify({ ...detail, status: 'GENERATING', completedAt: null, downloadAvailable: false }), { status: 200 }))
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const trace = { events: ref<ExecutionEvent[]>([]), subscribe: vi.fn() }
    const wrapper = mount(OperationsDailyReport, { props: { role: 'ADMIN', trace, pollIntervalMs: 1 } })
    await flushPromises()
    await wrapper.get('[data-generate-report]').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 5))
    await flushPromises()

    expect(trace.subscribe).toHaveBeenCalledWith('run-1')
    expect(wrapper.get('[data-testid="report-body"]').text()).toContain('已完成')
    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('1 份')
    const post = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'POST')
    expect((post?.[1]?.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
  })

  it('renders partial reasons and does not enable unavailable downloads', async () => {
    const partial = { ...summary, status: 'PARTIAL', downloadAvailable: false }
    const partialDetail = { ...detail, ...partial, sections: [{ ...detail.sections[0], status: 'UNAVAILABLE', summary: '', rowCount: 0, rows: [], partialReason: 'REPORT_SECTION_UNAVAILABLE' }] }
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
      url.includes('?') ? { content: [partial], page: 0, size: 20, totalElements: 1, hasNext: false } : partialDetail,
    ), { status: 200 }))))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR' } })
    await flushPromises()
    const buttons = wrapper.findAll('[data-testid="report-history"] button')
    expect(buttons[1].attributes('disabled')).toBeDefined()
    await buttons[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('REPORT_SECTION_UNAVAILABLE')
  })

  it('resubscribes to the selected durable trace when the view is reactivated', async () => {
    const trace = { events: ref<ExecutionEvent[]>([]), subscribe: vi.fn() }
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
      url.includes('?') ? { content: [summary], page: 0, size: 20, totalElements: 1, hasNext: false } : detail,
    ), { status: 200 }))))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR', trace, active: true } })
    await flushPromises()
    await wrapper.get('[data-testid="report-history"] button').trigger('click')
    await flushPromises()
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(trace.subscribe).toHaveBeenCalledTimes(2)
    expect(trace.subscribe).toHaveBeenLastCalledWith('run-1')
  })
})
