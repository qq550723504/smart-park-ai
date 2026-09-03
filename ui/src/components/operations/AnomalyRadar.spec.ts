import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AnomalyRadar from './AnomalyRadar.vue'

const overview = {
  window: { from: '2026-08-27T00:00:00Z', to: '2026-09-03T00:00:00Z', timezone: 'Asia/Shanghai' },
  asOf: '2026-09-03T02:00:00Z',
  summary: { alertCount: 3, highRiskAlertCount: 1, offlineDeviceCount: 1, affectedBuildingCount: 2 },
  breakdowns: {
    riskLevels: [{ key: 'HIGH', count: 1 }],
    categories: [{ key: 'POWER', count: 3 }],
    statuses: [{ key: 'OPEN', count: 2 }],
    deviceTypes: [{ key: 'HVAC', count: 1 }],
  },
  buildings: [{ buildingId: 'B1', alertCount: 2, highRiskAlertCount: 1, offlineDeviceCount: 0, energyDeviationPct: 20 }],
  domainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
}

afterEach(() => vi.unstubAllGlobals())

describe('AnomalyRadar', () => {
  it('renders server-backed facts with explicit data windows', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(overview), { status: 200 })))

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.get('[data-anomaly-radar]').text()).toContain('3'))

    expect(wrapper.text()).toContain('近 7 天告警')
    expect(wrapper.text()).toContain('最近 1 天离线设备')
    expect(wrapper.text()).toContain('2026/08/27')
    expect(wrapper.text()).toContain('B1')
  })

  it('shows partial-domain status instead of turning unavailable energy into zero', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...overview, domainStatus: { alerts: 'OK', devices: 'OK', energy: 'UNAVAILABLE' }, buildings: [{ ...overview.buildings[0], energyDeviationPct: null }] }), { status: 200 })))

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('能耗数据暂不可用'))

    expect(wrapper.text()).not.toContain('能耗偏差：0%')
  })

  it('emits a building event when a ranking is selected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(overview), { status: 200 })))
    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.find('[data-anomaly-building="B1"]').exists()).toBe(true))

    await wrapper.get('[data-anomaly-building="B1"]').trigger('click')
    expect(wrapper.emitted('open-building')).toEqual([['B1', {}]])
  })
})
