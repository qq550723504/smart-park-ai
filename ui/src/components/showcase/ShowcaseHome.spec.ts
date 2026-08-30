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
    VOICE_ASSISTANT: '实时语音助手',
  }
  const questions: Record<ShowcaseScenario['id'], string> = {
    ALERT_WORKFLOW: '配电或暖通异常该如何处置？',
    EXPERT_COLLABORATION: '能耗、设备与安防是否存在关联？',
    OPERATIONS_ANALYSIS: '过去几天哪座楼能耗偏离基线？',
    VOICE_ASSISTANT: '通过语音询问园区问题并获得在线回答',
  }

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

afterEach(() => {
  vi.clearAllMocks()
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
    expect(wrapper.emitted('start-scenario')).toEqual([['EXPERT_COLLABORATION']])
  })

  it('orders selectable scenarios by customer-demo priority and exposes at most three rows', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('VOICE_ASSISTANT', 'READY', true, null),
      scenario('OPERATIONS_ANALYSIS', 'READY', true, null),
      scenario('ALERT_WORKFLOW', 'READY', true, null),
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))

    const wrapper = await mountLoaded()
    const rows = wrapper.findAll('[data-showcase-scenario-row]')

    expect(rows).toHaveLength(3)
    expect(rows.map((row) => row.attributes('data-scenario-id'))).toEqual([
      'EXPERT_COLLABORATION',
      'ALERT_WORKFLOW',
      'OPERATIONS_ANALYSIS',
    ])
    expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')
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
    expect(wrapper.get('[data-start-showcase]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('internal service detail')
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

  it('emits the workbench intent without a payload', async () => {
    vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
      scenario('EXPERT_COLLABORATION', 'READY', true, null),
    ]))
    const wrapper = await mountLoaded()

    await wrapper.get('[data-enter-workbench]').trigger('click')

    expect(wrapper.emitted('enter-workbench')).toEqual([[]])
  })
})
