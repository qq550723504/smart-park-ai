import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CustomerOperationsReports from './CustomerOperationsReports.vue'

const summary = {
  reportId: 'report-1', reportType: 'OPERATIONS_DAILY', title: '智慧园区运营日报', status: 'COMPLETED',
  createdAt: '2026-09-09T01:00:00Z', completedAt: '2026-09-09T01:01:00Z',
  timeWindow: { fromInclusive: '2026-09-04T01:00:00Z', toExclusive: '2026-09-09T01:00:00Z' },
  timezone: 'Asia/Shanghai', asOf: '2026-09-09T01:00:00Z', runId: 'run-1', traceId: 'run-1', downloadAvailable: true,
  artifact: { artifactId: 'artifact-1', format: 'MARKDOWN', fileName: 'operations-report-report-1.md', contentType: 'text/markdown', size: 1024, createdAt: '2026-09-09T01:01:00Z', checksum: 'abc', rendererVersion: 'v1' },
} as const

const detail = {
  ...summary,
  requestedBy: 'demo-role:OPERATOR', role: 'OPERATOR', startedAt: summary.createdAt,
  summary: '本次快照显示能耗基线偏差需要持续核查。', schemaVersion: 1, generationVersion: 'operations-daily-v2',
  sections: [
    {
      sectionId: 'ENERGY_BASELINE', title: '能耗基线偏差', question: '报告窗口内各楼宇能耗基线偏差', status: 'COMPLETED',
      summary: '创新中心偏差为 12%，来自本报告生成时快照。', rowCount: 2, truncated: false,
      columns: ['building', 'deviation_pct'], rows: [['B1', 12], ['B2', null]],
      timeResolution: {}, evidenceReferences: [],
      sourceReferences: [{ sourceSystem: 'analytics.v_energy_hourly', metric: 'energy_deviation_pct', unit: '%', status: 'AVAILABLE', asOf: '2026-09-09T01:00:00Z' }],
      partialReason: null, failureReason: null, runId: 'analysis-energy',
    },
    {
      sectionId: 'PARKING_UTILIZATION', title: '停车利用率', question: '报告窗口内各区域停车利用率', status: 'UNAVAILABLE',
      summary: '', rowCount: 0, truncated: false, columns: [], rows: [], timeResolution: {}, evidenceReferences: [], sourceReferences: [],
      partialReason: '停车数据源本次不可用', failureReason: null, runId: null,
    },
  ],
  evidence: [{ sourceSystem: 'OPERATIONS_ANALYTICS', metric: 'energy_deviation_pct', entity: 'REPORT_SECTION:ENERGY_BASELINE', observationTime: '2026-09-09T01:00:00Z', runReference: 'analysis-energy', summary: '已保存 2 行生成时结果' }],
  sourceReferences: [],
} as const

function page(content: unknown[]) {
  return { content, page: 0, size: 20, totalElements: content.length, hasNext: false }
}

function mountReports(props: Record<string, unknown> = {}) {
  return mount(CustomerOperationsReports, {
    props: { pollIntervalMs: 1, ...props },
    global: {
      stubs: {
        CustomerOverviewChart: {
          props: ['data', 'unit', 'label'],
          template: '<div data-report-chart :data-unit="unit" :aria-label="label">{{ JSON.stringify(data) }}</div>',
          setup: () => ({ JSON }),
        },
      },
    },
  })
}

describe('CustomerOperationsReports', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('reads history, preview, conclusions and chart from one immutable snapshot without generating', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      return Promise.resolve(new Response(JSON.stringify(url.includes('?') ? page([summary]) : detail), { status: 200 }))
    }))
    const wrapper = mountReports()
    await flushPromises()

    expect(wrapper.get('[data-testid="report-preview"]').text()).toContain('智慧园区运营日报')
    expect(wrapper.get('[data-testid="report-preview"]').text()).toContain('本次快照显示能耗基线偏差需要持续核查')
    expect(wrapper.get('[data-report-chart]').text()).toContain('"name":"B1","value":12')
    expect(wrapper.get('.customer-reports__conclusions').text()).toContain('创新中心偏差为 12%')
    expect(wrapper.get('[data-download-current]').text()).toContain('Markdown')
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  })

  it('keeps one report through generate, refresh, leave-return, history reopen and download', async () => {
    let historyReads = 0
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.stubGlobal('URL', Object.assign(URL, {
      createObjectURL: vi.fn(() => 'blob:report-1'),
      revokeObjectURL: vi.fn(),
    }))
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        return Promise.resolve(new Response(JSON.stringify({ reportId: 'report-1', runId: 'run-1', statusUrl: '/api/operations-reports/report-1' }), { status: 202 }))
      }
      if (url.endsWith('/download')) {
        return Promise.resolve(new Response('# report-1', { status: 200, headers: { 'Content-Disposition': 'attachment; filename="operations-report-report-1.md"' } }))
      }
      if (url.includes('?')) {
        historyReads += 1
        return Promise.resolve(new Response(JSON.stringify(page(historyReads === 1 ? [] : [summary])), { status: 200 }))
      }
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mountReports()
    await flushPromises()

    await Promise.all([
      wrapper.get('[data-generate-report]').trigger('click'),
      wrapper.get('[data-generate-report]').trigger('click'),
    ])
    await flushPromises()
    expect(wrapper.get('[data-testid="report-preview"]').text()).toContain('report-1')

    await wrapper.get('[data-refresh-reports]').trigger('click')
    await flushPromises()
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()
    await wrapper.get('.customer-reports__history-list > button').trigger('click')
    await flushPromises()
    await wrapper.get('[data-download-current]').trigger('click')
    await flushPromises()

    const calls = vi.mocked(fetch).mock.calls
    expect(calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    expect(calls.filter(([url]) => String(url).endsWith('/report-1/download'))).toHaveLength(1)
    expect(wrapper.get('[data-testid="report-preview"]').text()).toContain('智慧园区运营日报')
    expect(anchorClick).toHaveBeenCalledTimes(1)
  })

  it('preserves the exact create identity when an ambiguous failure is followed by refresh', async () => {
    let postCount = 0
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        postCount += 1
        if (postCount === 1) return Promise.reject(new Error('network response lost'))
        return Promise.resolve(new Response(JSON.stringify({ reportId: 'report-1', runId: 'run-1', statusUrl: '/api/operations-reports/report-1' }), { status: 202 }))
      }
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify(page([])), { status: 200 }))
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mountReports()
    await flushPromises()

    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-refresh-reports]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()

    const posts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts).toHaveLength(2)
    expect((posts[0]![1]?.headers as Record<string, string>)['Idempotency-Key'])
      .toBe((posts[1]![1]?.headers as Record<string, string>)['Idempotency-Key'])
    expect(posts[0]![1]?.body).toBe(posts[1]![1]?.body)
  })

  it('preserves the create identity for an HTTP failure that can happen after durable admission', async () => {
    let postCount = 0
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        postCount += 1
        if (postCount === 1) {
          return Promise.resolve(new Response(JSON.stringify({ message: 'Operations report capacity is exhausted; retry later' }), { status: 429 }))
        }
        return Promise.resolve(new Response(JSON.stringify({ reportId: 'report-1', runId: 'run-1', statusUrl: '/api/operations-reports/report-1' }), { status: 202 }))
      }
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify(page([])), { status: 200 }))
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mountReports()
    await flushPromises()

    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-refresh-reports]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-generate-report]').trigger('click')
    await flushPromises()

    const posts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts).toHaveLength(2)
    expect((posts[0]![1]?.headers as Record<string, string>)['Idempotency-Key'])
      .toBe((posts[1]![1]?.headers as Record<string, string>)['Idempotency-Key'])
    expect(posts[0]![1]?.body).toBe(posts[1]![1]?.body)
  })

  it('keeps a history refresh failure visible without discarding the selected receipt', async () => {
    let historyReads = 0
    let detailReads = 0
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('?')) {
        historyReads += 1
        return historyReads === 1
          ? Promise.resolve(new Response(JSON.stringify(page([summary])), { status: 200 }))
          : Promise.resolve(new Response(JSON.stringify({ message: '报告目录暂时不可用' }), { status: 503 }))
      }
      detailReads += 1
      return Promise.resolve(new Response(JSON.stringify(detail), { status: 200 }))
    }))
    const wrapper = mountReports()
    await flushPromises()

    await wrapper.get('[data-refresh-reports]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="report-error"]').text()).toContain('报告目录暂时不可用')
    expect(wrapper.get('[data-testid="report-preview"]').text()).toContain('智慧园区运营日报')
    expect(detailReads).toBe(1)
  })

  it('keeps partial and failed reports visibly distinct from successful output', async () => {
    const partialSummary = { ...summary, reportId: 'partial', status: 'PARTIAL', downloadAvailable: false, artifact: undefined }
    const failedSummary = { ...summary, reportId: 'failed', status: 'FAILED', downloadAvailable: false, artifact: undefined }
    const partialDetail = { ...detail, ...partialSummary, summary: '仅能完成能耗章节。', sections: [detail.sections[0], { ...detail.sections[1], partialReason: '停车源不可用' }] }
    const failedDetail = { ...detail, ...failedSummary, summary: '', sections: [{ ...detail.sections[1], status: 'FAILED', partialReason: null, failureReason: '报告生成失败' }] }
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      const body = url.includes('?') ? page([partialSummary, failedSummary]) : url.endsWith('/failed') ? failedDetail : partialDetail
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))
    }))
    const wrapper = mountReports()
    await flushPromises()

    expect(wrapper.text()).toContain('本报告仅部分完成')
    expect(wrapper.get('[data-download-current]').attributes('disabled')).toBeDefined()
    await wrapper.findAll('.customer-reports__history-list > button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本次报告生成失败')
    expect(wrapper.text()).toContain('报告生成失败')
    expect(wrapper.get('[data-download-current]').attributes('disabled')).toBeDefined()
  })

  it('ignores an old report response that arrives after a newer history selection', async () => {
    let resolveOld!: (response: Response) => void
    const oldDetail = new Promise<Response>((resolve) => { resolveOld = resolve })
    const secondSummary = { ...summary, reportId: 'report-2', runId: 'run-2', traceId: 'run-2' }
    const secondDetail = { ...detail, ...secondSummary, summary: '第二份报告快照' }
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('?')) return Promise.resolve(new Response(JSON.stringify(page([summary, secondSummary])), { status: 200 }))
      if (url.endsWith('/report-1')) return oldDetail
      return Promise.resolve(new Response(JSON.stringify(secondDetail), { status: 200 }))
    }))
    const wrapper = mountReports()
    await flushPromises()

    await wrapper.findAll('.customer-reports__history-list > button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第二份报告快照')
    resolveOld(new Response(JSON.stringify(detail), { status: 200 }))
    await flushPromises()

    expect(wrapper.text()).toContain('第二份报告快照')
    expect(wrapper.text()).not.toContain('本次快照显示能耗基线偏差需要持续核查')
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  })
})
