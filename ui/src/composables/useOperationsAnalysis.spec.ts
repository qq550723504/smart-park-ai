import { describe, expect, it, beforeEach } from 'vitest'
import { useOperationsAnalysis } from './useOperationsAnalysis'
import { ref } from 'vue'
import type { ExecutionEvent } from '../types/execution'

type FetchHandler = (url: string, init?: RequestInit) => Response

const originalFetch = globalThis.fetch
let handler: FetchHandler = () => new Response('{}', { status: 200 })

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const RUN_ID = '11111111-2222-3333-4444-555555555555'

beforeEach(() => {
  handler = () => jsonResponse({})
})

globalThis.fetch = ((url: RequestInfo | URL, init?: RequestInit) =>
  handler(String(url), init)) as unknown as typeof fetch

function fakeTrace() {
  const events = ref<ExecutionEvent[]>([])
  const subscribed: string[] = []
  return {
    events,
    subscribed,
    subscribe(runId: string) {
      subscribed.push(runId)
    },
  }
}

describe('useOperationsAnalysis', () => {
  it('subscribes the trace and completes when status reaches COMPLETED', async () => {
    const trace = fakeTrace()
    let pollCount = 0
    handler = (url, init) => {
      if (init?.method === 'POST' && url.endsWith('/api/operations-analysis/runs')) {
        return jsonResponse({ runId: RUN_ID }, 202)
      }
      if (/\/api\/operations-analysis\/runs\/[0-9a-f-]+$/.test(url)) {
        pollCount++
        return jsonResponse({ runId: RUN_ID, status: pollCount >= 2 ? 'COMPLETED' : 'RUNNING', createdAt: '' })
      }
      return jsonResponse({}, 404)
    }
    const analysis = useOperationsAnalysis({ trace, pollIntervalMs: 1 })
    await analysis.submit('上周能耗')

    expect(trace.subscribed).toEqual([RUN_ID])
    expect(analysis.phase.value).toBe('completed')
    expect(analysis.dto.value?.status).toBe('COMPLETED')
  })

  it('keeps an accepted analysis running when trace setup is unavailable', async () => {
    handler = (url, init) => {
      if (init?.method === 'POST') return jsonResponse({ runId: RUN_ID }, 202)
      return jsonResponse({ runId: RUN_ID, status: 'COMPLETED', createdAt: '' })
    }
    const accepted: string[] = []
    const failures: Error[] = []
    const analysis = useOperationsAnalysis({
      trace: { events: ref([]), subscribe: () => { throw new Error('EventSource unavailable') } },
      pollIntervalMs: 1,
    })

    await analysis.submit('上周能耗', {
      onAccepted: (runId) => accepted.push(runId),
      onFailed: (cause) => failures.push(cause),
    })

    expect(accepted).toEqual([RUN_ID])
    expect(failures).toEqual([])
    expect(analysis.phase.value).toBe('completed')
  })

  it('surfaces clarification questions and resumes with structured selections', async () => {
    const trace = fakeTrace()
    let clarified = false
    handler = (url) => {
      if (url.includes('/clarifications')) {
        clarified = true
        return jsonResponse({
          runId: RUN_ID,
          status: 'COMPLETED',
          summary: '共 3 行结果。',
          createdAt: '',
        })
      }
      if (/\/runs\/[0-9a-f-]+$/.test(url)) {
        return jsonResponse(
          clarified
            ? { runId: RUN_ID, status: 'COMPLETED', createdAt: '' }
            : {
                runId: RUN_ID,
                status: 'NEEDS_CLARIFICATION',
                clarificationQuestions: ['“告警”可以指: 告警数量 / 高风险告警数量'],
                createdAt: '',
              },
        )
      }
      return jsonResponse({ runId: RUN_ID }, 202)
    }

    const analysis = useOperationsAnalysis({ trace, pollIntervalMs: 1 })
    await analysis.submit('告警情况')
    expect(analysis.phase.value).toBe('clarification')

    analysis.selections.value = [{ term: '告警', metric: 'alert_count' }]
    await analysis.clarify()
    expect(analysis.phase.value).toBe('completed')
    expect(clarified).toBe(true)
  })

  it('continues checking a paused run so clarification expiry reaches the UI', async () => {
    const trace = fakeTrace()
    let statusCalls = 0
    handler = (url, init) => {
      if (init?.method === 'POST') return jsonResponse({ runId: RUN_ID }, 202)
      if (/\/runs\/[0-9a-f-]+$/.test(url)) {
        statusCalls += 1
        return jsonResponse({
          runId: RUN_ID,
          status: statusCalls === 1 ? 'NEEDS_CLARIFICATION' : 'FAILED',
          failureStage: statusCalls === 1 ? undefined : 'CLARIFICATION_TIMEOUT',
          createdAt: '',
        })
      }
      return jsonResponse({}, 404)
    }

    const analysis = useOperationsAnalysis({ trace, pollIntervalMs: 1 })
    await analysis.submit('告警情况')
    expect(analysis.phase.value).toBe('clarification')

    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(statusCalls).toBeGreaterThan(1)
    expect(analysis.phase.value).toBe('failed')
    expect(analysis.dto.value?.failureStage).toBe('CLARIFICATION_TIMEOUT')
  })

  it('reports backend failures without fabricating results', async () => {
    handler = (_url) => jsonResponse({ message: 'boom' }, 400)
    const analysis = useOperationsAnalysis({ pollIntervalMs: 1 })
    await analysis.submit('')
    expect(analysis.phase.value).toBe('idle')
    expect(analysis.error.value).toContain('请输入分析问题')
  })

  it('clears the previous run id before a new analysis that fails to start', async () => {
    let starts = 0
    handler = (url, init) => {
      if (init?.method === 'POST' && url.endsWith('/api/operations-analysis/runs')) {
        starts += 1
        return starts === 1
          ? jsonResponse({ runId: RUN_ID }, 202)
          : jsonResponse({ message: 'busy' }, 503)
      }
      return jsonResponse({ runId: RUN_ID, status: 'COMPLETED', createdAt: '' })
    }
    const analysis = useOperationsAnalysis({ pollIntervalMs: 1 })

    await analysis.submit('第一次分析')
    expect(analysis.runId.value).toBe(RUN_ID)
    await analysis.submit('第二次分析')

    expect(analysis.runId.value).toBeNull()
    expect(analysis.phase.value).toBe('failed')
  })

  it('captures the chart spec from real CHART_SPECIFIED trace events only', async () => {
    const trace = fakeTrace()
    handler = (url, init) => {
      if (init?.method === 'POST') return jsonResponse({ runId: RUN_ID }, 202)
      return jsonResponse({ runId: RUN_ID, status: 'COMPLETED', createdAt: '' })
    }
    const analysis = useOperationsAnalysis({ trace, pollIntervalMs: 1 })
    await analysis.submit('q')
    expect(analysis.chart.value).toBeNull()
    const { nextTick } = await import('vue')
    await nextTick()

    trace.events.value = [
      {
        eventId: 'e1',
        runId: RUN_ID,
        sequence: 1,
        timestamp: '2026-08-24T08:00:00Z',
        scenario: 'OPERATIONS_ANALYSIS',
        actor: 'analytics',
        stage: 'RENDERING',
        eventType: 'CHART_SPECIFIED',
        status: 'RUNNING',
        safeSummary: '图表规格: BAR',
        displayPayload: {
          payloadType: 'CHART',
          type: 'BAR',
          title: '分楼宇能耗',
          xField: 'building_id',
          yFields: ['total_kwh'],
          seriesField: '-',
          unit: 'kWh',
        },
      } as ExecutionEvent,
    ]
    await nextTick()
    expect((analysis.chart.value as { payloadType: string }).payloadType).toBe('CHART')
  })

  it('keeps chart state bound to the current analysis run', async () => {
    const trace = fakeTrace()
    handler = (url, init) => {
      if (init?.method === 'POST') return jsonResponse({ runId: 'run-b' }, 202)
      return jsonResponse({ runId: 'run-b', status: 'COMPLETED', createdAt: '' })
    }
    const analysis = useOperationsAnalysis({ trace, pollIntervalMs: 1 })
    await analysis.submit('B 的分析')
    const { nextTick } = await import('vue')
    const chart = (runId: string, title: string) => ({
      eventId: `chart-${runId}`, runId, sequence: 1, timestamp: '', scenario: 'OPERATIONS_ANALYSIS',
      actor: 'analytics', stage: 'RENDERING', eventType: 'CHART_SPECIFIED', status: 'RUNNING', safeSummary: title,
      displayPayload: { payloadType: 'CHART', type: 'BAR', title, xField: 'building', yFields: ['energy_kwh'], seriesField: '-', unit: 'kWh' },
    } as ExecutionEvent)

    trace.events.value = [chart('run-b', 'B 图表')]
    await nextTick()
    trace.events.value = [chart('run-b', 'B 图表'), chart('run-a', 'A 旧图表')]
    await nextTick()

    expect((analysis.chart.value as { title: string }).title).toBe('B 图表')
  })
})
