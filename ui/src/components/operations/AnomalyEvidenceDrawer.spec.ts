import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AnomalyEvidenceDrawer from './AnomalyEvidenceDrawer.vue'

const evidence = {
  buildingId: 'B1',
  window: { from: '2026-08-27T00:00:00Z', to: '2026-09-03T00:00:00Z', timezone: 'Asia/Shanghai' },
  asOf: '2026-09-03T02:00:00Z',
  alerts: [{ alertId: 'ALT-1', category: 'POWER', riskLevel: 'HIGH', status: 'OPEN', occurredAt: '2026-09-03T01:00:00Z', redactedSummary: 'REDACTED: POWER · OPEN', executionRunId: 'run-1' }],
  devices: [{ deviceId: 'DEV-1', deviceType: 'HVAC', status: 'OFFLINE', snapshotAt: '2026-09-03T02:00:00Z', redactedSummary: 'REDACTED: 设备状态 · OFFLINE' }],
  energy: [{ meterId: 'MTR-1', deviationPct: 20, measuredAt: '2026-09-03T03:00:00Z', redactedSummary: 'REDACTED: 能耗读数摘要' }],
  domainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
}

afterEach(() => vi.unstubAllGlobals())

describe('AnomalyEvidenceDrawer', () => {
  it('loads and renders redacted evidence with trace and analysis actions', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(evidence), { status: 200 })))
    const wrapper = mount(AnomalyEvidenceDrawer, { props: { role: 'ADMIN', buildingId: 'B1', open: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('REDACTED: POWER · OPEN'))

    expect(wrapper.text()).toContain('B1 异常证据链')
    expect(wrapper.text()).toContain('HVAC')
    expect(wrapper.text()).not.toContain('rawPayload')
    await wrapper.get('[data-evidence-trace="run-1"]').trigger('click')
    expect(wrapper.emitted('open-trace')).toEqual([['run-1']])
  })

  it('shows unavailable domains without hiding available evidence', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...evidence, asOf: null, domainStatus: { alerts: 'OK', devices: 'UNAVAILABLE', energy: 'OK' }, devices: [] }), { status: 200 })))
    const wrapper = mount(AnomalyEvidenceDrawer, { props: { role: 'ADMIN', buildingId: 'B1', open: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('设备数据暂不可用'))

    expect(wrapper.text()).toContain('REDACTED: POWER · OPEN')
    expect(wrapper.text()).toContain('快照：—')
  })

  it('clears previous evidence while loading a different building', async () => {
    let resolveSecond!: (response: Response) => void
    const secondResponse = new Promise<Response>((resolve) => { resolveSecond = resolve })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(evidence), { status: 200 }))
      .mockReturnValueOnce(secondResponse)
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AnomalyEvidenceDrawer, { props: { role: 'ADMIN', buildingId: 'B1', open: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('REDACTED: POWER · OPEN'))

    await wrapper.setProps({ buildingId: 'B2' })
    await vi.waitFor(() => expect(wrapper.text()).toContain('正在读取楼宇证据'))
    expect(wrapper.text()).not.toContain('REDACTED: POWER · OPEN')
    resolveSecond(new Response(JSON.stringify({ ...evidence, buildingId: 'B2' }), { status: 200 }))
  })

  it('keeps the selected building and filters when entering analysis', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(evidence), { status: 200 })))
    const wrapper = mount(AnomalyEvidenceDrawer, {
      props: {
        role: 'ADMIN',
        buildingId: 'B1',
        filters: { riskLevel: 'HIGH', category: 'POWER', status: 'OPEN', deviceType: 'HVAC' },
        open: true,
      },
    })
    await vi.waitFor(() => expect(wrapper.text()).toContain('REDACTED: POWER · OPEN'))

    await wrapper.get('.anomaly-evidence__actions button').trigger('click')

    expect(wrapper.emitted('open-analysis')).toEqual([[
      '过去7天楼宇 B1 的离线设备数量（风险等级：HIGH；告警类别：POWER；告警状态：OPEN；设备类型：HVAC）',
    ]])
  })
})
