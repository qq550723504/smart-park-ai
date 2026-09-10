import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ShowcaseHome from './ShowcaseHome.vue'

describe('ShowcaseHome customer shell', () => {
  it('defaults to the customer overview, enables analysis, and keeps later pages non-interactive', () => {
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
    for (const page of ['work-orders', 'reports', 'assistant']) {
      const item = wrapper.get(`[data-customer-nav="${page}"]`)
      expect(item.element.tagName).toBe('SPAN')
      expect(item.attributes('aria-disabled')).toBe('true')
    }
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
            template: '<main data-park-overview><button data-open-analysis @click="$emit(\'view-analysis\', context)">查看 B2 分析</button><button data-open-other-analysis @click="$emit(\'view-analysis\', otherContext)">查看 B3 分析</button><button data-select-other @click="$emit(\'context-change\', otherContext)">选择 B3</button><button data-refresh-context @click="$emit(\'context-change\', refreshedContext)">刷新窗口</button></main>',
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

    await wrapper.get('[data-open-analysis]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-customer-nav="analysis"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-energy-analysis]').attributes('style')).not.toContain('display: none')
    expect(wrapper.get('[data-energy-analysis]').element).toBe(analysisPage)

    await wrapper.get('[data-analysis-shell-back]').trigger('click')
    await wrapper.get('[data-select-other]').trigger('click')
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('运营中心')
    expect(wrapper.get('[data-energy-analysis]').element).not.toBe(analysisPage)

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
