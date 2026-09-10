import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ShowcaseHome from './ShowcaseHome.vue'

describe('ShowcaseHome customer shell', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('defaults to the customer overview and exposes every delivered customer entry', () => {
    const wrapper = mount(ShowcaseHome, {
      props: { active: false },
      global: {
        stubs: {
          ParkOverview: { template: '<main data-park-overview />' },
          EnergyAnalysis: { template: '<main data-energy-analysis />' },
        },
      },
    })

    expect(wrapper.find('[data-customer-shell]').exists()).toBe(true)
    expect(wrapper.get('[data-customer-nav="overview"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-customer-nav="analysis"]').element.tagName).toBe('BUTTON')
    expect(wrapper.get('[data-energy-analysis]').isVisible()).toBe(false)
    expect(wrapper.get('[data-customer-nav="work-orders"]').element.tagName).toBe('BUTTON')
    expect(wrapper.get('[data-customer-nav="reports"]').element.tagName).toBe('BUTTON')
    const assistant = wrapper.get('[data-customer-nav="assistant"]')
    expect(assistant.element.tagName).toBe('BUTTON')
    expect(assistant.attributes('aria-expanded')).toBe('false')
  })

  it('opens the assistant without changing pages and restores focus after close', async () => {
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      attachTo: document.body,
      global: { stubs: {
        ParkOverview: { template: '<main id="customer-overview-main" data-park-overview />' },
        EnergyAnalysis: { template: '<main id="customer-analysis-main" data-energy-analysis />' },
        CustomerAssistantPanel: {
          props: ['open', 'activePage', 'context'], emits: ['close'],
          template: '<aside v-show="open" data-assistant-stub><span>{{ activePage }}</span><button data-close-stub @click="$emit(\'close\')">关闭</button></aside>',
        },
      } },
    })
    const trigger = wrapper.get('[data-customer-nav="assistant"]')
    await trigger.trigger('click')
    expect(wrapper.get('[data-assistant-stub]').isVisible()).toBe(true)
    expect(wrapper.get('[data-assistant-stub]').text()).toContain('overview')
    expect(trigger.attributes('aria-expanded')).toBe('true')

    await wrapper.get('[data-close-stub]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-assistant-stub]').isVisible()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })

  it('opens reports inside the same customer shell', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({
      knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none', analyticsEnabled: true,
      collaborationEnabled: false, voiceEnabled: false, securityIncidentEnabled: false,
    }), { status: 200 }))))
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: { stubs: {
        ParkOverview: { template: '<main data-park-overview />' },
        EnergyAnalysis: { template: '<main data-energy-analysis />' },
        CustomerOperationsReports: { props: ['active', 'available'], template: '<main id="customer-reports-main" data-customer-reports>{{ active }}:{{ available }}</main>' },
      } },
    })
    const shell = wrapper.get('[data-customer-shell]').element

    await wrapper.get('[data-customer-nav="reports"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-customer-nav="reports"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-customer-reports]').text()).toBe('true:true')
    expect(wrapper.get('[data-customer-shell]').element).toBe(shell)
    expect(wrapper.findAll('.customer-shell__topbar')).toHaveLength(1)
  })

  it('keeps report generation unavailable when the backend capability is disabled', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({
      knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none', analyticsEnabled: false,
      collaborationEnabled: false, voiceEnabled: false, securityIncidentEnabled: false,
    }), { status: 200 }))))
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: { stubs: {
        ParkOverview: { template: '<main data-park-overview />' },
        EnergyAnalysis: { template: '<main data-energy-analysis />' },
        CustomerOperationsReports: { props: ['available'], template: '<main id="customer-reports-main" data-report-available>{{ available }}</main>' },
      } },
    })
    await flushPromises()
    await wrapper.get('[data-customer-nav="reports"]').trigger('click')

    expect(wrapper.get('[data-report-available]').text()).toBe('false')
  })

  it('continues from analysis to work orders with the exact same context and shell', async () => {
    const context = {
      buildingId: 'B1', buildingName: '创新中心', anomalyId: 'ALT-ORCH-ENERGY-B1-001', title: '创新中心能耗偏离基线', priority: '高',
      summary: { buildingId: 'B1', alertCount: 2, highRiskAlertCount: 2, offlineDeviceCount: 1, energyDeviationPct: 12 },
      overviewDomainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
      anomalyWindow: { from: '2026-09-01T00:00:00Z', to: '2026-09-09T00:37:00Z', timezone: 'Asia/Shanghai' },
      energyWindow: { from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai', granularity: 'HOUR' },
      source: 'OPERATIONS_ANALYTICS',
    } as const
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: { stubs: {
        ParkOverview: { emits: ['view-analysis'], template: '<button data-open-analysis @click="$emit(\'view-analysis\', context)">分析</button>', setup: () => ({ context }) },
        EnergyAnalysis: { props: ['context'], emits: ['open-work-orders'], template: '<main data-energy-analysis><button data-open-work-orders @click="$emit(\'open-work-orders\', context)">工单</button></main>' },
        CustomerWorkOrders: { props: ['context'], emits: ['open-reports'], template: '<main id="customer-work-orders-main" data-customer-work-orders>{{ context?.anomalyId }}<button data-continue-reports @click="$emit(\'open-reports\')">报告</button></main>' },
        CustomerOperationsReports: { template: '<main id="customer-reports-main" data-customer-reports />' },
      } },
    })
    const shell = wrapper.get('[data-customer-shell]').element
    await wrapper.get('[data-open-analysis]').trigger('click')
    await wrapper.get('[data-open-work-orders]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-customer-nav="work-orders"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-customer-work-orders]').text()).toContain('ALT-ORCH-ENERGY-B1-001')
    expect(wrapper.get('[data-customer-shell]').element).toBe(shell)
    expect(wrapper.findAll('.customer-shell__topbar')).toHaveLength(1)

    await wrapper.get('[data-continue-reports]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-customer-nav="reports"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-customer-reports]').isVisible()).toBe(true)
    expect(wrapper.get('[data-customer-shell]').element).toBe(shell)
  })

  it('restarts only the customer presentation state after explicit confirmation', async () => {
    const overviewReset = vi.fn()
    const assistantReset = vi.fn(() => true)
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: { stubs: {
        ParkOverview: { methods: { resetForDemo: overviewReset }, template: '<main id="customer-overview-main" data-park-overview tabindex="-1">B2</main>' },
        EnergyAnalysis: { template: '<main id="customer-analysis-main" data-energy-analysis />' },
        CustomerAssistantPanel: { methods: { canResetForDemo: () => true, resetForDemo: assistantReset }, template: '<aside />' },
      } },
    })
    await wrapper.get('[data-customer-nav="reports"]').trigger('click')
    await wrapper.get('[data-restart-demo]').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('不会删除或重置后台工单、历史报告')

    await wrapper.get('[aria-label="取消重开导览"]').trigger('click')
    expect(wrapper.get('[data-customer-nav="reports"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-park-overview]').text()).toBe('B2')
    expect(overviewReset).not.toHaveBeenCalled()

    await wrapper.get('[data-restart-demo]').trigger('click')

    await wrapper.get('[data-confirm-restart]').trigger('click')
    await flushPromises()
    expect(assistantReset).toHaveBeenCalledTimes(1)
    expect(overviewReset).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-customer-nav="overview"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-restart-notice]').text()).toContain('后台工单、报告和进行中的任务均未删除')
  })

  it('blocks restart while an assistant write result is unconfirmed', async () => {
    const assistantReset = vi.fn(() => false)
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: { stubs: {
        ParkOverview: { methods: { resetForDemo: vi.fn() }, template: '<main id="customer-overview-main" data-park-overview />' },
        EnergyAnalysis: { template: '<main id="customer-analysis-main" data-energy-analysis />' },
        CustomerAssistantPanel: { methods: { canResetForDemo: () => false, resetForDemo: assistantReset }, template: '<aside />' },
      } },
    })

    await wrapper.get('[data-customer-nav="reports"]').trigger('click')
    await wrapper.get('[data-restart-demo]').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.get('[data-customer-nav="reports"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-customer-nav="assistant"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-restart-notice]').text()).toContain('已保留请求关联')
    expect(assistantReset).not.toHaveBeenCalled()
  })

  it('uses the existing App event to enter the long-lived internal workbench', async () => {
    const wrapper = mount(ShowcaseHome, {
      props: { active: false },
      global: {
        stubs: {
          ParkOverview: { template: '<main data-park-overview />' },
          EnergyAnalysis: { template: '<main data-energy-analysis />' },
        },
      },
    })

    await wrapper.get('[data-enter-workbench]').trigger('click')

    expect(wrapper.emitted('enter-workbench')).toEqual([[]])
  })

  it('adopts the first overview context when analysis was opened before loading finished', async () => {
    const context = {
      buildingId: 'B1',
      buildingName: '创新中心',
      anomalyId: 'ALT-B1',
      title: '创新中心运营分析',
      priority: '低',
      summary: null,
      overviewDomainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
      anomalyWindow: { from: '2026-09-01T00:00:00Z', to: '2026-09-09T00:37:00Z', timezone: 'Asia/Shanghai' },
      energyWindow: { from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai', granularity: 'HOUR' },
      source: 'OPERATIONS_ANALYTICS',
    }
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: {
        stubs: {
          ParkOverview: {
            emits: ['context-change'],
            template: '<main data-park-overview><button data-finish-loading @click="$emit(\'context-change\', context)">完成加载</button></main>',
            setup: () => ({ context }),
          },
          EnergyAnalysis: {
            props: ['context'],
            template: '<main data-energy-analysis>{{ context?.buildingName ?? \'等待上下文\' }}</main>',
          },
        },
      },
    })

    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('等待上下文')

    await wrapper.get('[data-finish-loading]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('创新中心')
  })

  it('moves from an overview issue to analysis and returns without entering the workbench', async () => {
    const context = {
      buildingId: 'B2',
      buildingName: '研发大厦',
      anomalyId: 'ALT-B2',
      title: '研发大厦能耗偏离基线',
      priority: '中',
      summary: { buildingId: 'B2', alertCount: 1, highRiskAlertCount: 0, offlineDeviceCount: 0, energyDeviationPct: 12 },
      overviewDomainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
      anomalyWindow: { from: '2026-09-01T00:00:00Z', to: '2026-09-09T00:37:00Z', timezone: 'Asia/Shanghai' },
      energyWindow: { from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai', granularity: 'HOUR' },
      source: 'OPERATIONS_ANALYTICS',
    }
    const otherContext = {
      ...context,
      buildingId: 'B3',
      buildingName: '运营中心',
      anomalyId: 'ALT-B3',
      title: '运营中心能耗偏离基线',
      summary: { ...context.summary, buildingId: 'B3' },
    }
    const refreshedContext = {
      ...context,
      energyWindow: {
        ...context.energyWindow,
        from: '2026-09-08T01:00:00Z',
        to: '2026-09-09T01:00:00Z',
      },
    }
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: {
        stubs: {
          ParkOverview: {
            emits: ['context-change', 'view-analysis'],
            template: '<main data-park-overview><button data-open-analysis @click="$emit(\'view-analysis\', context)">查看 B2 分析</button><button data-open-other-analysis @click="$emit(\'view-analysis\', otherContext)">查看 B3 分析</button><button data-select-other @click="$emit(\'context-change\', otherContext)">选择 B3</button><button data-clear-context @click="$emit(\'context-change\', null)">清空上下文</button><button data-refresh-context @click="$emit(\'context-change\', refreshedContext)">刷新窗口</button></main>',
            setup: () => ({ context, otherContext, refreshedContext }),
          },
          EnergyAnalysis: {
            props: ['context'],
            template: '<main data-energy-analysis><span>{{ context?.buildingName }}</span><time>{{ context?.energyWindow.to }}</time></main>',
          },
        },
      },
    })
    const shell = wrapper.get('[data-customer-shell]').element
    const topbar = wrapper.get('.customer-shell__topbar').element

    await wrapper.get('[data-open-analysis]').trigger('click')
    expect(wrapper.get('[data-customer-nav="analysis"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('研发大厦')
    expect(wrapper.get('#customer-analysis-title').text()).toContain('研发大厦能耗偏离基线')
    expect(wrapper.get('[data-customer-shell]').element).toBe(shell)
    expect(wrapper.get('.customer-shell__topbar').element).toBe(topbar)
    expect(wrapper.findAll('.customer-shell__topbar')).toHaveLength(1)
    expect(wrapper.emitted('enter-workbench')).toBeUndefined()
    const analysisPage = wrapper.get('[data-energy-analysis]').element

    await wrapper.get('[data-refresh-context]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-energy-analysis]').element).toBe(analysisPage)
    expect(wrapper.get('[data-energy-analysis]').text()).toContain(context.energyWindow.to)
    expect(wrapper.get('[data-energy-analysis]').text()).not.toContain(refreshedContext.energyWindow.to)

    await wrapper.get('[data-analysis-shell-back]').trigger('click')
    expect(wrapper.get('[data-customer-nav="overview"]').attributes('aria-current')).toBe('page')
    expect(wrapper.find('[data-park-overview]').isVisible()).toBe(true)
    expect(wrapper.get('[data-energy-analysis]').isVisible()).toBe(false)
    expect(wrapper.get('[data-customer-shell]').element).toBe(shell)
    expect(wrapper.get('.customer-shell__topbar').element).toBe(topbar)

    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-customer-nav="analysis"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-energy-analysis]').attributes('style')).not.toContain('display: none')
    expect(wrapper.get('[data-energy-analysis]').element).toBe(analysisPage)
    expect(wrapper.get('[data-energy-analysis]').text()).toContain(context.energyWindow.to)
    expect(wrapper.get('[data-energy-analysis]').text()).not.toContain(refreshedContext.energyWindow.to)

    await wrapper.get('[data-analysis-shell-back]').trigger('click')
    await wrapper.get('[data-select-other]').trigger('click')
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('运营中心')
    expect(wrapper.get('[data-energy-analysis]').element).not.toBe(analysisPage)
    const otherAnalysisPage = wrapper.get('[data-energy-analysis]').element

    await wrapper.get('[data-analysis-shell-back]').trigger('click')
    await wrapper.get('[data-clear-context]').trigger('click')
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('运营中心')
    expect(wrapper.get('[data-energy-analysis]').element).toBe(otherAnalysisPage)

    await wrapper.get('[data-analysis-shell-back]').trigger('click')
    await wrapper.get('[data-open-other-analysis]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('运营中心')
    expect(wrapper.get('[data-energy-analysis]').element).not.toBe(analysisPage)

    await wrapper.get('[data-analysis-shell-back]').trigger('click')
    await wrapper.get('[data-open-analysis]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-energy-analysis]').element).toBe(analysisPage)
  })
})
