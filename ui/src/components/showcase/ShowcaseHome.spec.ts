import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ShowcaseHome from './ShowcaseHome.vue'
import { getShowcaseScenarios } from '../../services/workflowApi'
import type { ShowcaseScenario, ShowcaseScenarioCatalog, ShowcaseScenarioStatus } from '../../services/workflowApi'

vi.mock('../../services/workflowApi', () => ({
  getShowcaseScenarios: vi.fn(),
}))

const verifiedAt = '2026-08-30T09:59:59Z'

function scenario(
  id: ShowcaseScenario['id'],
  status: ShowcaseScenarioStatus,
  live: boolean,
  unavailableReason: string | null,
  overrides: Partial<ShowcaseScenario> = {},
): ShowcaseScenario {
  const titles: Record<ShowcaseScenario['id'], string> = {
    ALERT_WORKFLOW: '告警处置',
    EXPERT_COLLABORATION: '跨域专家协作',
    OPERATIONS_ANALYSIS: '运营分析',
    CUSTOMER_SERVICE: '园区客服',
    VOICE_ASSISTANT: '实时语音助手',
    CUSTOMER_SERVICE: '园区客服',
  }
  const questions: Record<ShowcaseScenario['id'], string> = {
    ALERT_WORKFLOW: '配电或暖通异常该如何处置？',
    EXPERT_COLLABORATION: '能耗、设备与安防是否存在关联？',
    OPERATIONS_ANALYSIS: '过去几天哪座楼能耗偏离基线？',
    CUSTOMER_SERVICE: '园区服务如何自动回答并有序转人工？',
    VOICE_ASSISTANT: '通过语音询问园区问题并获得在线回答',
    CUSTOMER_SERVICE: '园区服务如何自动回答并有序转人工？',
  }
  const launchInputs = {
    ALERT_WORKFLOW: { alertId: 'ALT-POWER-001', question: null },
    EXPERT_COLLABORATION: {
      alertId: null,
      question: '电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联',
    },
    OPERATIONS_ANALYSIS: { alertId: null, question: '过去5天各楼宇能耗' },
    CUSTOMER_SERVICE: { alertId: null, question: '访客停车怎么收费？' },
    VOICE_ASSISTANT: { alertId: null, question: null },
    CUSTOMER_SERVICE: { alertId: null, question: '访客停车怎么收费？' },
  } as const

  return {
    id,
    status,
    live,
    title: titles[id],
    businessQuestion: questions[id],
    expectedDurationSeconds: 40,
    requiredCapabilities: ['在线模型', '领域工具'],
    proofTypes: ['专家分工', '工具证据'],
    humanBoundary: '证据不足时保留人工复核',
    launchInput: launchInputs[id],
    unavailableReason,
    lastVerifiedAt: status === 'READY' && live ? verifiedAt : null,
    ...overrides,
  }
}

function catalog(scenarios: ShowcaseScenario[]): ShowcaseScenarioCatalog {
  return {
    capturedAt: '2026-08-30T10:00:00Z',
    scenarios,
  }
}

async function mountLoaded() {
  const wrapper = mount(ShowcaseHome)
  await flushPromises()
  return wrapper
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

afterEach(() => {
  vi.resetAllMocks()
})

describe('ShowcaseHome truthful catalog selection', () => {
  it('selects verified collaboration and emits its exact scenario id', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('ALERT_WORKFLOW', 'NOT_READY', false, '最近一次在线检查未通过'),
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))

    const wrapper = await mountLoaded()

    expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')
    await wrapper.get('[data-start-showcase]').trigger('click')
    expect(wrapper.emitted('start-scenario')).toEqual([[
      'EXPERT_COLLABORATION',
      {
        alertId: null,
        question: '电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联',
      },
    ]])
  })

  it('revalidates immediately before start and fails closed when readiness expired', async () => {
    vi.mocked(getShowcaseScenarios)
      .mockResolvedValueOnce(catalog([
        scenario('EXPERT_COLLABORATION', 'READY', true, null),
      ]))
      .mockResolvedValueOnce(catalog([
        scenario('EXPERT_COLLABORATION', 'NOT_READY', false, '在线验证已过期'),
      ]))

    const wrapper = await mountLoaded()
    await wrapper.get('[data-start-showcase]').trigger('click')
    await flushPromises()

    expect(getShowcaseScenarios).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('start-scenario')).toBeUndefined()
    expect(wrapper.get('[data-showcase-status]').text()).toContain('暂无已验证场景')
    expect(wrapper.text()).toContain('在线验证已过期')
  })

  it('does not complete a pending scenario start after navigation deactivates the showcase', async () => {
    const pendingValidation = deferred<ShowcaseScenarioCatalog>()
    vi.mocked(getShowcaseScenarios)
      .mockResolvedValueOnce(catalog([
        scenario('EXPERT_COLLABORATION', 'READY', true, null),
      ]))
      .mockReturnValueOnce(pendingValidation.promise)

    const wrapper = mount(ShowcaseHome, { props: { active: true } })
    await flushPromises()

    await wrapper.get('[data-start-showcase]').trigger('click')
    await wrapper.get('[data-enter-workbench]').trigger('click')
    await wrapper.setProps({ active: false })

    pendingValidation.resolve(catalog([
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))
    await flushPromises()

    expect(wrapper.emitted('enter-workbench')).toEqual([[]])
    expect(wrapper.emitted('start-scenario')).toBeUndefined()
  })

  it('revalidates whenever the showcase becomes active again', async () => {
    vi.mocked(getShowcaseScenarios)
      .mockResolvedValueOnce(catalog([
        scenario('EXPERT_COLLABORATION', 'READY', true, null),
      ]))
      .mockResolvedValueOnce(catalog([
        scenario('ALERT_WORKFLOW', 'READY', true, null),
        scenario('EXPERT_COLLABORATION', 'NOT_READY', false, '在线验证已过期'),
      ]))

    const wrapper = mount(ShowcaseHome, { props: { active: true } })
    await flushPromises()
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')

    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(getShowcaseScenarios).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('告警处置')
  })

  it('ignores an older catalog response that resolves after a newer activation refresh', async () => {
    const first = deferred<ShowcaseScenarioCatalog>()
    vi.mocked(getShowcaseScenarios)
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(catalog([
        scenario('ALERT_WORKFLOW', 'READY', true, null),
      ]))

    const wrapper = mount(ShowcaseHome, { props: { active: true } })
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('告警处置')

    first.resolve(catalog([
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))
    await flushPromises()

    expect(wrapper.get('[data-selected-scenario]').text()).toContain('告警处置')
  })

  it('keeps the pre-run evidence ribbon explanatory instead of implying execution', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('ALERT_WORKFLOW', 'NOT_READY', false, '最近一次在线检查未通过'),
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))

    const wrapper = await mountLoaded()
    const ribbon = wrapper.get('[aria-label="能力账本"]').text()

    expect(ribbon).toContain('流程说明')
    expect(ribbon).not.toContain('实时演绎中')
  })

  it('orders every scenario by customer-demo priority and exposes each as a real row', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('VOICE_ASSISTANT', 'READY', true, null),
      scenario('OPERATIONS_ANALYSIS', 'READY', true, null),
      scenario('ALERT_WORKFLOW', 'READY', true, null),
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))

    const wrapper = await mountLoaded()
    const rows = wrapper.findAll('[data-showcase-scenario-row]')

    expect(rows).toHaveLength(4)
    expect(rows.map((row) => row.attributes('data-scenario-id'))).toEqual([
      'EXPERT_COLLABORATION',
      'ALERT_WORKFLOW',
      'OPERATIONS_ANALYSIS',
      'VOICE_ASSISTANT',
    ])
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')
  })

  it('selects the fourth ready voice scenario from its real row and starts it after revalidation', async () => {
    const allReady = catalog([
      scenario('VOICE_ASSISTANT', 'READY', true, null),
      scenario('OPERATIONS_ANALYSIS', 'READY', true, null),
      scenario('ALERT_WORKFLOW', 'READY', true, null),
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ])
    vi.mocked(getShowcaseScenarios).mockResolvedValueOnce(allReady).mockResolvedValueOnce(allReady)

    const wrapper = await mountLoaded()
    const voiceRow = wrapper.get('[data-showcase-scenario-row][data-scenario-id="VOICE_ASSISTANT"]')
    await voiceRow.trigger('click')
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('实时语音助手')

    await wrapper.get('[data-start-showcase]').trigger('click')
    await flushPromises()

    expect(getShowcaseScenarios).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('start-scenario')).toEqual([
      ['VOICE_ASSISTANT', { alertId: null, question: null }],
    ])
  })

  it('renders server-provided selected scenario facts truthfully', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('EXPERT_COLLABORATION', 'READY', true, null, {
        title: '服务端标题',
        businessQuestion: '服务端业务问题？',
        expectedDurationSeconds: 37,
        proofTypes: ['服务端证据 A', '服务端证据 B'],
        humanBoundary: '服务端人工边界',
        lastVerifiedAt: '2026-08-30T08:15:00Z',
      }),
    ]))

    const wrapper = await mountLoaded()
    const selected = wrapper.get('[data-selected-scenario]').text()

    expect(selected).toContain('服务端标题')
    expect(selected).toContain('服务端业务问题？')
    expect(selected).toContain('约 37 秒')
    expect(selected).toContain('服务端证据 A')
    expect(selected).toContain('服务端证据 B')
    expect(selected).toContain('服务端人工边界')
    expect(selected).toContain('2026-08-30T08:15:00Z')
  })

  it('shows unavailable reasons and prevents NOT_READY and DISABLED rows from being selected', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
      scenario('ALERT_WORKFLOW', 'NOT_READY', false, '本次部署尚未完成在线验证'),
      scenario('OPERATIONS_ANALYSIS', 'DISABLED', false, '本次部署未启用运营分析'),
    ]))

    const wrapper = await mountLoaded()
    const rows = wrapper.findAll('[data-showcase-scenario-row]')
    const alert = rows.find((row) => row.attributes('data-scenario-id') === 'ALERT_WORKFLOW')
    const analytics = rows.find((row) => row.attributes('data-scenario-id') === 'OPERATIONS_ANALYSIS')

    expect(alert?.attributes('disabled')).toBeDefined()
    expect(alert?.text()).toContain('本次部署尚未完成在线验证')
    expect(analytics?.attributes('disabled')).toBeDefined()
    expect(analytics?.text()).toContain('本次部署未启用运营分析')

    await alert!.trigger('click')
    await analytics!.trigger('click')

    expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')
  })

  it('treats READY with live false as unavailable and shows a safe reason', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
      scenario('VOICE_ASSISTANT', 'READY', false, null, { lastVerifiedAt: null }),
    ]))

    const wrapper = await mountLoaded()
    const voice = wrapper.findAll('[data-showcase-scenario-row]')
      .find((row) => row.attributes('data-scenario-id') === 'VOICE_ASSISTANT')

    expect(voice?.attributes('disabled')).toBeDefined()
    expect(voice?.text()).toContain('当前链路未通过在线验证')
    await voice!.trigger('click')
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')
  })

  it('disables start and shows the safe error message when the catalog request fails', async () => {
    vi.mocked(getShowcaseScenarios).mockRejectedValue(new Error('internal service detail'))

    const wrapper = await mountLoaded()

    expect(wrapper.get('[data-showcase-status]').text()).toContain('当前无法确认演示链路')
    expect(wrapper.get('[data-catalog-stamp]').text()).toContain('无法确认演示链路')
    expect(wrapper.get('[data-catalog-stamp]').text()).not.toContain('正在检查')
    expect(wrapper.get('[data-catalog-stamp]').attributes('data-catalog-state')).toBe('failed')
    expect(wrapper.get('[data-start-showcase]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('internal service detail')
  })

  it('recovers from a transient catalog failure through the visible retry action', async () => {
    vi.mocked(getShowcaseScenarios)
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce(catalog([
        scenario('EXPERT_COLLABORATION', 'READY', true, null),
      ]))

    const wrapper = await mountLoaded()
    await wrapper.get('[data-retry-catalog]').trigger('click')
    await flushPromises()

    expect(getShowcaseScenarios).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-catalog-stamp]').attributes('data-catalog-state')).toBe('verified')
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')
  })

  it('disables start and shows the safe no-ready message when no scenario is selectable', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('ALERT_WORKFLOW', 'NOT_READY', false, '本次部署尚未完成在线验证'),
      scenario('OPERATIONS_ANALYSIS', 'DISABLED', false, '本次部署未启用运营分析'),
      scenario('VOICE_ASSISTANT', 'READY', false, null, { lastVerifiedAt: null }),
    ]))

    const wrapper = await mountLoaded()

    expect(wrapper.get('[data-showcase-status]').text()).toContain('暂无已验证场景')
    expect(wrapper.get('[data-start-showcase]').attributes('disabled')).toBeDefined()
  })

  it('retains the fourth unavailable capability reason in its disabled row', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('EXPERT_COLLABORATION', 'DISABLED', false, '本次部署未启用专家协作'),
      scenario('ALERT_WORKFLOW', 'NOT_READY', false, '本次部署尚未完成在线验证'),
      scenario('OPERATIONS_ANALYSIS', 'DISABLED', false, '本次部署未启用运营分析'),
      scenario('VOICE_ASSISTANT', 'DISABLED', false, '本次展台未启用语音链路'),
    ]))

    const wrapper = await mountLoaded()

    const rows = wrapper.findAll('[data-showcase-scenario-row]')
    const voice = rows.find((row) => row.attributes('data-scenario-id') === 'VOICE_ASSISTANT')
    expect(rows).toHaveLength(4)
    expect(voice?.attributes('disabled')).toBeDefined()
    expect(voice?.text()).toContain('实时语音助手')
    expect(voice?.text()).toContain('本次展台未启用语音链路')
  })

  it('emits the workbench intent without a payload', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))
    const wrapper = await mountLoaded()

    await wrapper.get('[data-enter-workbench]').trigger('click')

    expect(wrapper.emitted('enter-workbench')).toEqual([[]])
  })
})
