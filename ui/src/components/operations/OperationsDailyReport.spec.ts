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
    expect(trace.subscribe).toHaveBeenCalledWith('run-1', 'OPERATOR')
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

    expect(trace.subscribe).toHaveBeenCalledWith('run-1', 'ADMIN')
    expect(wrapper.get('[data-testid="report-body"]').text()).toContain('已完成')
    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('1 份')
    const post = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'POST')
    expect((post?.[1]?.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({
      reportType: 'OPERATIONS_DAILY', timezone: 'Asia/Shanghai',
      timeWindow: { fromInclusive: expect.any(String), toExclusive: expect.any(String) },
    })
  })

  it('reuses the exact key and time window after an ambiguous network failure', async () => {
    let postCount = 0
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify({ content: [], page: 0, size: 20, totalElements: 0, hasNext: false }), { status: 200 }))
      if (init?.method === 'POST') {
        postCount += 1
        if (postCount === 1) return Promise.reject(new Error('network response lost'))
        return Promise.resolve(new Response(JSON.stringify({ reportId: 'report-1', runId: 'run-1', statusUrl: '/api/operations-reports/report-1' }), { status: 202 }))
      }
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR', pollIntervalMs: 1 } })
    await flushPromises()

    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()

    const posts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts).toHaveLength(2)
    expect((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key'])
      .toBe((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key'])
    expect(posts[0][1]?.body).toBe(posts[1][1]?.body)
  })

  it('uses a fresh key and window after a definitive create rejection', async () => {
    vi.useFakeTimers()
    let wrapper: ReturnType<typeof mount> | undefined
    try {
      let postCount = 0
      vi.setSystemTime(new Date('2026-09-09T01:00:00Z'))
      vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
        if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify({ content: [], page: 0, size: 20, totalElements: 0, hasNext: false }), { status: 200 }))
        if (init?.method === 'POST') {
          postCount += 1
          if (postCount === 1) return Promise.resolve(new Response(JSON.stringify({ message: 'capacity exhausted' }), { status: 429 }))
          return Promise.resolve(new Response(JSON.stringify({ reportId: 'report-1', runId: 'run-1', statusUrl: '/api/operations-reports/report-1' }), { status: 202 }))
        }
        return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
      }))
      wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR', pollIntervalMs: 1 } })
      await flushPromises()

      await wrapper.get('[data-generate-report]').trigger('click')
      await flushPromises()
      vi.setSystemTime(new Date('2026-09-09T02:00:00Z'))
      await wrapper.get('[data-generate-report]').trigger('click')
      await flushPromises()

      const posts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(posts).toHaveLength(2)
      expect((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key'])
        .not.toBe((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key'])
      expect(JSON.parse(String(posts[0][1]?.body)).timeWindow.toExclusive).toBe('2026-09-09T01:00:00.000Z')
      expect(JSON.parse(String(posts[1][1]?.body)).timeWindow.toExclusive).toBe('2026-09-09T02:00:00.000Z')
    } finally {
      wrapper?.unmount()
      vi.useRealTimers()
    }
  })

  it('retires an accepted creation key when a historical report is opened', async () => {
    let postCount = 0
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify({ content: [summary], page: 0, size: 20, totalElements: 1, hasNext: false }), { status: 200 }))
      if (init?.method === 'POST') {
        postCount += 1
        const id = postCount === 1 ? 'report-generating' : 'report-2'
        return Promise.resolve(new Response(JSON.stringify({ reportId: id, runId: `run-${id}`, statusUrl: `/api/operations-reports/${id}` }), { status: 202 }))
      }
      if (url.endsWith('/report-generating')) {
        return Promise.resolve(new Response(JSON.stringify({ ...detail, reportId: 'report-generating', status: 'GENERATING', completedAt: null, downloadAvailable: false }), { status: 200 }))
      }
      if (url.endsWith('/report-2')) {
        return Promise.resolve(new Response(JSON.stringify({ ...detail, reportId: 'report-2', runId: 'run-report-2', traceId: 'run-report-2' }), { status: 200 }))
      }
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR', pollIntervalMs: 10 } })
    await flushPromises()

    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="report-history"] button').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 15))
    await flushPromises()
    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()

    const posts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts).toHaveLength(2)
    expect((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key'])
      .not.toBe((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key'])
  })

  it('loads every history page instead of hiding reports beyond the first page', async () => {
    const second = { ...summary, reportId: 'report-2', runId: 'run-2', traceId: 'run-2' }
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      const page = new URL(url, 'http://localhost').searchParams.get('page')
      const body = page === '1'
        ? { content: [second], page: 1, size: 20, totalElements: 2, hasNext: false }
        : { content: [summary], page: 0, size: 20, totalElements: 2, hasNext: true }
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))
    }))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR' } })
    await flushPromises()

    expect(wrapper.get('[data-load-more-reports]').text()).toContain('1 / 2')
    await wrapper.get('[data-load-more-reports]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('2 份')
    expect(wrapper.find('[data-load-more-reports]').exists()).toBe(false)
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('page=1'))).toBe(true)
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
    expect(trace.subscribe).toHaveBeenLastCalledWith('run-1', 'OPERATOR')
  })

  it('clears selected data and ignores stale history when the role changes', async () => {
    let adminHistoryCalls = 0
    let resolveStaleAdmin!: (response: Response) => void
    const staleAdmin = new Promise<Response>((resolve) => { resolveStaleAdmin = resolve })
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      const role = (init?.headers as Record<string, string> | undefined)?.['X-Demo-Role']
      if (url.includes('?') && role === 'ADMIN') {
        adminHistoryCalls += 1
        if (adminHistoryCalls > 1) return staleAdmin
        return Promise.resolve(new Response(JSON.stringify({ content: [summary], page: 0, size: 20, totalElements: 1, hasNext: false }), { status: 200 }))
      }
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify({ content: [], page: 0, size: 20, totalElements: 0, hasNext: false }), { status: 200 }))
      return Promise.resolve(new Response(JSON.stringify({ ...detail, requestedBy: 'demo-role:ADMIN', role: 'ADMIN' }), { status: 200 }))
    }))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'ADMIN', active: true } })
    await flushPromises()
    await wrapper.get('[data-testid="report-history"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="report-body"]').exists()).toBe(true)

    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await wrapper.setProps({ role: 'OPERATOR' })
    await flushPromises()
    resolveStaleAdmin(new Response(JSON.stringify({ content: [summary], page: 0, size: 20, totalElements: 1, hasNext: false }), { status: 200 }))
    await flushPromises()

    expect(wrapper.find('[data-testid="report-body"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('0 份')
  })

  it('does not let stale role cleanup unlock a replacement generation', async () => {
    let resolveAdminCreate!: (response: Response) => void
    const adminCreate = new Promise<Response>((resolve) => { resolveAdminCreate = resolve })
    const operatorDetail = new Promise<Response>(() => {})
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      const role = (init?.headers as Record<string, string> | undefined)?.['X-Demo-Role']
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify({ content: [], page: 0, size: 20, totalElements: 0, hasNext: false }), { status: 200 }))
      if (init?.method === 'POST' && role === 'ADMIN') return adminCreate
      if (init?.method === 'POST') return Promise.resolve(new Response(JSON.stringify({ reportId: 'operator-report', runId: 'operator-run', statusUrl: '/api/operations-reports/operator-report' }), { status: 202 }))
      return operatorDetail
    }))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'ADMIN', pollIntervalMs: 1 } })
    await flushPromises()

    await wrapper.get('[data-generate-report]').trigger('click')
    await wrapper.setProps({ role: 'OPERATOR' })
    await flushPromises()
    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-generate-report]').attributes('disabled')).toBeDefined()

    resolveAdminCreate(new Response(JSON.stringify({ reportId: 'admin-report', runId: 'admin-run', statusUrl: '/api/operations-reports/admin-report' }), { status: 202 }))
    await flushPromises()

    expect(wrapper.get('[data-generate-report]').attributes('disabled')).toBeDefined()
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(2)
  })

  it('clears a stale initial history spinner when generation is rejected', async () => {
    let resolveHistory!: (response: Response) => void
    const history = new Promise<Response>((resolve) => { resolveHistory = resolve })
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('?')) return history
      if (init?.method === 'POST') {
        return Promise.resolve(new Response(JSON.stringify({ message: 'capacity exhausted' }), { status: 429 }))
      }
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mount(OperationsDailyReport, { props: { role: 'OPERATOR' } })

    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('加载中')
    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('0 份')
    expect(wrapper.get('[data-testid="report-history"]').text()).not.toContain('加载中')
    resolveHistory(new Response(JSON.stringify({ content: [summary], page: 0, size: 20, totalElements: 1, hasNext: false }), { status: 200 }))
    await flushPromises()
    expect(wrapper.get('[data-testid="report-history"]').text()).toContain('0 份')
  })
})
