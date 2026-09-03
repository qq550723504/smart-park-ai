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

  it('formats the window in the timezone declared by the response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ...overview,
      window: { ...overview.window, from: '2026-09-02T16:00:00Z', to: '2026-09-03T16:00:00Z' },
    }), { status: 200 })))

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('2026/09/03'))
    expect(wrapper.text()).toContain('2026/09/04')
  })

  it('keeps the composite affected count visible when one domain is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ...overview,
      domainStatus: { alerts: 'UNAVAILABLE', devices: 'OK', energy: 'OK' },
    }), { status: 200 })))

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('2'))
    expect(wrapper.text()).toContain('部分数据')
  })

  it('renders a dash for building facts from an unavailable domain and sends active filters', async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Response(JSON.stringify({
      ...overview,
      domainStatus: { alerts: 'UNAVAILABLE', devices: 'UNAVAILABLE', energy: 'OK' },
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.find('[data-anomaly-building="B1"]').exists()).toBe(true))
    await wrapper.get('[data-anomaly-filter="riskLevel"]').setValue('HIGH')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(wrapper.text()).toContain('告警 —'))

    expect(wrapper.text()).toContain('告警 —')
    expect(wrapper.text()).toContain('离线 —')
    expect(fetchMock.mock.calls[1][0]).toContain('riskLevel=HIGH')
    await wrapper.get('[data-anomaly-building="B1"]').trigger('click')
    expect(wrapper.emitted('open-building')).toEqual([['B1', { riskLevel: 'HIGH' }]])
  })

  it('hides the previous overview while a filter request is pending', async () => {
    let resolveSecond!: (response: Response) => void
    const secondResponse = new Promise<Response>((resolve) => { resolveSecond = resolve })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(overview), { status: 200 }))
      .mockReturnValueOnce(secondResponse)
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.find('[data-anomaly-building="B1"]').exists()).toBe(true))

    await wrapper.get('[data-anomaly-filter="riskLevel"]').setValue('HIGH')
    await vi.waitFor(() => expect(wrapper.text()).toContain('正在读取异常聚合…'))
    expect(wrapper.find('[data-anomaly-building="B1"]').exists()).toBe(false)

    resolveSecond(new Response(JSON.stringify(overview), { status: 200 }))
  })

  it('keeps alert and device filters in separate domains', async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Response(JSON.stringify(overview), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.find('[data-anomaly-building="B1"]').exists()).toBe(true))
    await wrapper.get('[data-anomaly-filter="riskLevel"]').setValue('HIGH')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(wrapper.find('[data-anomaly-filter="deviceType"]').exists()).toBe(true))
    await wrapper.get('[data-anomaly-filter="deviceType"]').setValue('HVAC')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))

    expect(fetchMock.mock.calls[2][0]).toContain('deviceType=HVAC')
    expect(fetchMock.mock.calls[2][0]).not.toContain('riskLevel=HIGH')
  })

  it('labels unavailable alert breakdowns as unavailable instead of empty', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ...overview,
      breakdowns: { riskLevels: [], categories: [], statuses: [], deviceTypes: [] },
      domainStatus: { alerts: 'UNAVAILABLE', devices: 'OK', energy: 'OK' },
    }), { status: 200 })))

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('告警数据暂不可用'))
    expect(wrapper.text()).not.toContain('风险等级暂无数据')
  })

  it('preserves alternative filter values after applying a facet', async () => {
    const initialOverview = {
      ...overview,
      breakdowns: {
        ...overview.breakdowns,
        riskLevels: [{ key: 'HIGH', count: 1 }, { key: 'LOW', count: 1 }],
      },
    }
    const filteredOverview = {
      ...initialOverview,
      breakdowns: {
        ...initialOverview.breakdowns,
        riskLevels: [{ key: 'HIGH', count: 1 }],
      },
    }
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Response(JSON.stringify(initialOverview), { status: 200 }))
      .mockImplementationOnce(() => new Response(JSON.stringify(filteredOverview), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AnomalyRadar, { props: { role: 'ADMIN', active: true } })
    await vi.waitFor(() => expect(wrapper.find('[data-anomaly-filter="riskLevel"] option[value="LOW"]').exists()).toBe(true))
    await wrapper.get('[data-anomaly-filter="riskLevel"]').setValue('HIGH')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(wrapper.find('[data-anomaly-filter="riskLevel"] option[value="LOW"]').exists()).toBe(true))
  })
})
