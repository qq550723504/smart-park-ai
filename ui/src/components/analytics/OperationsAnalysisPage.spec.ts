import { describe, expect, it, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import OperationsAnalysisPage from './OperationsAnalysisPage.vue'
import type { ExecutionTraceLike } from '../../composables/useOperationsAnalysis'
import type { ExecutionEvent } from '../../types/execution'

type FetchHandler = (url: string, init?: RequestInit) => Response

const RUN_ID = '11111111-2222-3333-4444-555555555555'
const originalFetch = globalThis.fetch
let handler: FetchHandler = () => new Response('{}', { status: 200 })

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

beforeEach(() => {
  handler = () => jsonResponse({})
  globalThis.fetch = ((url: RequestInfo | URL, init?: RequestInit) =>
    handler(String(url), init)) as unknown as typeof fetch
})

async function flush(times = 8): Promise<void> {
  for (let i = 0; i < times; i++) {
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
}

describe('OperationsAnalysisPage', () => {
  it('renders the question form in the idle state', () => {
    const wrapper = mount(OperationsAnalysisPage)
    expect(wrapper.find('[aria-label="分析问题"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('开始分析')
    wrapper.unmount()
  })

  it('fills the question input when a recommended question is clicked', async () => {
    const wrapper = mount(OperationsAnalysisPage)
    const presets = wrapper.findAll('[aria-label="推荐问题"] button')

    expect(wrapper.find('[aria-label="推荐问题"]').attributes('role')).toBe('group')
    expect(presets.map((preset) => preset.text())).toEqual([
      '过去5天各楼宇能耗',
      '能耗总量',
      '各楼宇能耗对比',
      '过去5天按小时能耗趋势',
      '过去5天各楼宇能耗排行',
      '过去5天各楼宇能耗热力图',
      '过去5天按日期能耗日历热力图',
      '过去5天能耗目标完成率',
      '过去5天各楼宇能耗与占用人数关系',
      '过去5天各楼宇能耗空间分布',
      '过去5天各楼宇分时能耗堆叠图',
      '告警数量',
      '高风险告警数量',
      '停车进场量',
      '设备离线数',
    ])

    await presets[0].trigger('click')

    expect((wrapper.find('[aria-label="分析问题"]').element as HTMLInputElement).value)
      .toBe('过去5天各楼宇能耗')
    wrapper.unmount()
  })

  it('shows real result table, row count and summary after completion', async () => {
    let polls = 0
    handler = (url, init) => {
      if (init?.method === 'POST') return jsonResponse({ runId: RUN_ID }, 202)
      if (/\/runs\/[0-9a-f-]+$/.test(url)) {
        polls++
        return jsonResponse(polls >= 2 ? {
          runId: RUN_ID,
          status: 'COMPLETED',
          summary: '共 3 行结果。',
          rowCount: 3,
          truncated: false,
          columns: ['building_id', 'total_kwh'],
          rows: [['B1', '1820.5'], ['B2', '1444.25'], ['B3', '990']],
          createdAt: '2026-08-24T08:00:00Z',
        } : { runId: RUN_ID, status: 'RUNNING', createdAt: '2026-08-24T08:00:00Z' })
      }
      return jsonResponse({}, 404)
    }

    const wrapper = mount(OperationsAnalysisPage, { props: { pollIntervalMs: 1 } })
    await wrapper.find('[aria-label="分析问题"]').setValue('上周各楼宇能耗')
    await wrapper.find('form').trigger('submit')
    await flush()

    const table = wrapper.find('[data-testid="result-panel"] table')
    expect(table.exists()).toBe(true)
    expect(wrapper.text()).toContain('真实只读查询')
    expect(wrapper.text()).toContain('1820.5')
    expect(wrapper.text()).toContain('返回 3 行')
    expect(wrapper.emitted('run-started')).toBeTruthy()
    wrapper.unmount()
  })

  it('shows clarification controls and resumes with a chosen metric', async () => {
    let clarified = false
    let submitted: unknown = undefined
    handler = (url, init) => {
      if (url.includes('/clarifications')) {
        clarified = true
        submitted = JSON.parse(String(init?.body ?? '{}'))
        return jsonResponse({ runId: RUN_ID, status: 'COMPLETED', summary: 'ok', rowCount: 3,
          columns: ['c'], rows: [[1]], createdAt: '' })
      }
      if (/\/runs\/[0-9a-f-]+$/.test(url)) {
        return jsonResponse(clarified ? { runId: RUN_ID, status: 'COMPLETED', createdAt: '' } : {
          runId: RUN_ID,
          status: 'NEEDS_CLARIFICATION',
          clarificationQuestions: ['“告警”可以指: 告警数量 / 高风险告警数量'],
          clarificationOptions: [['alert_count', 'parking_entries']],
          createdAt: '',
        })
      }
      return jsonResponse({ runId: RUN_ID }, 202)
    }

    const wrapper = mount(OperationsAnalysisPage, { props: { pollIntervalMs: 1 } })
    await wrapper.find('[aria-label="分析问题"]').setValue('告警情况')
    await wrapper.find('form').trigger('submit')
    await flush()

    expect(wrapper.find('[data-testid="clarify-panel"]').exists()).toBe(true)
    // Each select renders the backend-provided candidate list, not hard-coded options.
    const options = wrapper.findAll('option').map((o) => (o.element as HTMLOptionElement).value)
    expect(options).toEqual(['alert_count', 'parking_entries'])
    await wrapper.find('select').setValue('parking_entries')
    await wrapper.find('[data-testid="resume-button"]').trigger('click')
    await flush()

    expect(clarified).toBe(true)
    expect(submitted).toMatchObject({ selections: [{ term: '澄清-1', metric: 'parking_entries' }] })
    expect(wrapper.find('[data-testid="result-panel"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('displays the failed stage when the backend terminates the run', async () => {
    handler = (url) => {
      if (/\/runs\/[0-9a-f-]+$/.test(url)) {
        return jsonResponse({ runId: RUN_ID, status: 'FAILED', failureStage: 'validateSqlAst', createdAt: '' })
      }
      return jsonResponse({ runId: RUN_ID }, 202)
    }
    const wrapper = mount(OperationsAnalysisPage, { props: { pollIntervalMs: 1 } })
    await wrapper.find('[aria-label="分析问题"]').setValue('上周能耗')
    await wrapper.find('form').trigger('submit')
    await flush()

    expect(wrapper.find('[data-testid="failed-panel"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('validateSqlAst')
    wrapper.unmount()
  })

  it('keeps echarts rendering optional when canvas is unavailable', async () => {
    handler = (url, init) => {
      if (init?.method === 'POST') return jsonResponse({ runId: RUN_ID }, 202)
      if (/\/runs\/[0-9a-f-]+$/.test(url)) {
        return jsonResponse({ runId: RUN_ID, status: 'COMPLETED', rowCount: 1,
          columns: ['building_id'], rows: [['B1']], createdAt: '' })
      }
      return jsonResponse({}, 404)
    }
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const wrapper = mount(OperationsAnalysisPage, { props: { pollIntervalMs: 1 } })
    await wrapper.find('[aria-label="分析问题"]').setValue('q')
    await wrapper.find('form').trigger('submit')
    await flush()
    // No ECharts crash should have been logged even though jsdom lacks canvas.
    expect(spy).not.toHaveBeenCalledWith(expect.stringContaining('echarts'))
    spy.mockRestore()
    wrapper.unmount()
  })

  it('starts the verified default question for a matching guided request', async () => {
    let submittedQuestion = ''
    handler = (_url, init) => {
      if (init?.method === 'POST') {
        submittedQuestion = JSON.parse(String(init.body)).question
        return jsonResponse({ runId: RUN_ID }, 202)
      }
      return jsonResponse({ runId: RUN_ID, status: 'RUNNING', createdAt: '' })
    }
    const wrapper = mount(OperationsAnalysisPage, {
      props: {
        active: true,
        pollIntervalMs: 1,
        launchRequest: {
          requestId: 22, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
          launchInput: { alertId: null, question: '目录下发的运营问题' },
        },
      },
    })
    await flush(2)
    expect(submittedQuestion).toBe('目录下发的运营问题')
    expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({ requestId: 22, state: 'started' })
    wrapper.unmount()
  })

  it('reuses an accepted analysis when returning from the showcase instead of posting a duplicate run', async () => {
    let posts = 0
    handler = (_url, init) => {
      if (init?.method === 'POST') {
        posts += 1
        return jsonResponse({ runId: 'active-run' }, 202)
      }
      return jsonResponse({ runId: 'active-run', status: 'RUNNING', createdAt: '' })
    }
    const wrapper = mount(OperationsAnalysisPage, {
      props: {
        active: true,
        pollIntervalMs: 1,
        launchRequest: {
          requestId: 30, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
          launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
        },
      },
    })
    await flush(2)
    expect(posts).toBe(1)

    await wrapper.setProps({ active: false })
    await wrapper.setProps({
      launchRequest: {
        requestId: 31, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
        launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
      },
    })
    await wrapper.setProps({ active: true })
    await flush(2)

    expect(posts).toBe(1)
    expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({
      requestId: 31, state: 'started', message: '已保留当前运营分析，请继续查看',
    })
    wrapper.unmount()
  })

  it('coalesces a pending guided submission when returning from the showcase', async () => {
    const pendingPost = deferred<Response>()
    let posts = 0
    handler = (_url, init) => {
      if (init?.method === 'POST') {
        posts += 1
        return pendingPost.promise as unknown as Response
      }
      return jsonResponse({ runId: 'pending-run', status: 'RUNNING', createdAt: '' })
    }
    const wrapper = mount(OperationsAnalysisPage, {
      props: {
        active: true,
        pollIntervalMs: 1,
        launchRequest: {
          requestId: 32, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
          launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
        },
      },
    })
    await flush(1)
    expect(posts).toBe(1)

    await wrapper.setProps({ active: false })
    await wrapper.setProps({
      launchRequest: {
        requestId: 33, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
        launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
      },
    })
    await wrapper.setProps({ active: true })
    await flush(1)

    expect(posts).toBe(1)
    expect((wrapper.emitted('launch-status') ?? [])
      .some(([update]) => (update as { requestId: number; state: string }).requestId === 32
        && (update as { state: string }).state === 'failed')).toBe(false)

    pendingPost.resolve(jsonResponse({ runId: 'pending-run' }, 202))
    await flush(2)

    expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({
      requestId: 33, state: 'started', message: '已保留当前运营分析，请继续查看',
    })
    wrapper.unmount()
  })

  it('waits for the newer guided request before reporting the accepted run', async () => {
    const firstPost = deferred<Response>()
    const secondPost = deferred<Response>()
    const subscribe = vi.fn()
    const trace: ExecutionTraceLike = { events: ref<ExecutionEvent[]>([]), subscribe }
    let posts = 0
    handler = (_url, init) => {
      if (init?.method === 'POST') {
        posts += 1
        return (posts === 1 ? firstPost.promise : secondPost.promise) as unknown as Response
      }
      return jsonResponse({ runId: 'run-b', status: 'RUNNING', createdAt: '' })
    }
    const wrapper = mount(OperationsAnalysisPage, {
      props: {
        active: true,
        pollIntervalMs: 1,
        trace,
        launchRequest: {
          requestId: 71, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
          launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
        },
      },
    })
    await flush(1)
    expect(posts).toBe(1)

    await wrapper.setProps({
      launchRequest: {
        requestId: 72, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
        launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
      },
    })
    await flush(1)
    expect(posts).toBe(2)
    const supersededA = (wrapper.emitted('launch-status') ?? [])
      .map(([update]) => update as { requestId: number; state: string })
      .filter((update) => update.requestId === 71)
    expect(supersededA.some((update) => update.state === 'failed')).toBe(true)

    firstPost.resolve(jsonResponse({ runId: 'run-a' }, 202))
    await flush(2)

    const updatesBeforeB = (wrapper.emitted('launch-status') ?? [])
      .map(([update]) => update as { requestId: number; state: string })
      .filter((update) => update.requestId === 72)
    expect(updatesBeforeB.some((update) => update.state === 'started')).toBe(false)
    expect(subscribe).not.toHaveBeenCalledWith('run-a')

    secondPost.resolve(jsonResponse({ runId: 'run-b' }, 202))
    await flush(2)

    expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({ requestId: 72, state: 'started' })
    expect(subscribe).toHaveBeenCalledWith('run-b')
    wrapper.unmount()
  })

  it('settles a guided start when a manual submission supersedes it', async () => {
    const guidedPost = deferred<Response>()
    const manualPost = deferred<Response>()
    let posts = 0
    handler = (_url, init) => {
      if (init?.method === 'POST') {
        posts += 1
        return (posts === 1 ? guidedPost.promise : manualPost.promise) as unknown as Response
      }
      return jsonResponse({ runId: 'manual-run', status: 'RUNNING', createdAt: '' })
    }
    const wrapper = mount(OperationsAnalysisPage, {
      props: {
        active: true,
        pollIntervalMs: 1,
        launchRequest: {
          requestId: 81, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics',
          launchInput: { alertId: null, question: '过去5天各楼宇能耗' },
        },
      },
    })
    await flush(1)
    expect(posts).toBe(1)

    await wrapper.find('form').trigger('submit')
    await flush(1)
    expect(posts).toBe(2)
    expect((wrapper.emitted('launch-status') ?? [])
      .some(([update]) => (update as { requestId: number; state: string }).requestId === 81
        && (update as { state: string }).state === 'failed')).toBe(true)

    guidedPost.resolve(jsonResponse({ runId: 'guided-run' }, 202))
    manualPost.resolve(jsonResponse({ runId: 'manual-run' }, 202))
    await flush(2)

    expect(wrapper.emitted('run-started')?.at(-1)).toEqual(['manual-run'])
    expect((wrapper.emitted('launch-status') ?? [])
      .some(([update]) => (update as { requestId: number; state: string }).requestId === 81
        && (update as { state: string }).state === 'started')).toBe(false)
    wrapper.unmount()
  })
})
