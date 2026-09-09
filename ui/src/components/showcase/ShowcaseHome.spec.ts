import { mount } from '@vue/test-utils'
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
    expect(wrapper.find('[data-energy-analysis]').exists()).toBe(false)
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

  it('moves from an overview issue to analysis and returns without entering the workbench', async () => {
    const context = {
      buildingId: 'B2',
      buildingName: '研发大厦',
      anomalyId: 'ALT-B2',
      title: '研发大厦能耗偏离基线',
      priority: '中',
      summary: { buildingId: 'B2', alertCount: 1, highRiskAlertCount: 0, offlineDeviceCount: 0, energyDeviationPct: 12 },
      anomalyWindow: { from: '2026-09-01T00:00:00Z', to: '2026-09-09T00:37:00Z', timezone: 'Asia/Shanghai' },
      energyWindow: { from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai', granularity: 'HOUR' },
      source: 'OPERATIONS_ANALYTICS',
    }
    const wrapper = mount(ShowcaseHome, {
      props: { active: true },
      global: {
        stubs: {
          ParkOverview: {
            emits: ['context-change', 'view-analysis'],
            template: '<main data-park-overview><button data-open-analysis @click="$emit(\'view-analysis\', context)">查看分析</button></main>',
            setup: () => ({ context }),
          },
          EnergyAnalysis: {
            props: ['context'],
            emits: ['back'],
            template: '<main data-energy-analysis><span>{{ context?.buildingName }}</span><button data-back @click="$emit(\'back\')">返回</button></main>',
          },
        },
      },
    })

    await wrapper.get('[data-open-analysis]').trigger('click')
    expect(wrapper.get('[data-customer-nav="analysis"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-energy-analysis]').text()).toContain('研发大厦')
    expect(wrapper.emitted('enter-workbench')).toBeUndefined()

    await wrapper.get('[data-back]').trigger('click')
    expect(wrapper.get('[data-customer-nav="overview"]').attributes('aria-current')).toBe('page')
    expect(wrapper.find('[data-park-overview]').isVisible()).toBe(true)
    expect(wrapper.find('[data-energy-analysis]').exists()).toBe(false)
  })
})
