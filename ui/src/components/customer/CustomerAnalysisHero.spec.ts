import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CustomerAnalysisHero from './CustomerAnalysisHero.vue'
import type { CustomerAnalysisContext } from '../../types/customer'

const context: CustomerAnalysisContext = {
  buildingId: 'B2',
  buildingName: '研发大厦',
  anomalyId: 'ALT-B2',
  title: '研发大厦存在高风险告警',
  priority: '高',
  summary: { buildingId: 'B2', alertCount: 2, highRiskAlertCount: 2, offlineDeviceCount: 0, energyDeviationPct: 7 },
  overviewDomainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
  anomalyWindow: { from: '2026-09-01T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai' },
  energyWindow: { from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai', granularity: 'HOUR' },
  source: 'OPERATIONS_ANALYTICS',
}

describe('CustomerAnalysisHero', () => {
  it('renders the selected analysis context inside the shared shell hero', async () => {
    const wrapper = mount(CustomerAnalysisHero, { props: { context } })

    expect(wrapper.get('#customer-analysis-title').text()).toBe(context.title)
    expect(wrapper.text()).toContain('B2 · 研发大厦')
    expect(wrapper.text()).toContain('高优先级')
    expect(wrapper.text()).toContain('Asia/Shanghai')
    expect(wrapper.get('button[disabled]').text()).toContain('进入人工确认')

    await wrapper.get('[data-analysis-shell-back]').trigger('click')
    expect(wrapper.emitted('back')).toHaveLength(1)
  })
})
