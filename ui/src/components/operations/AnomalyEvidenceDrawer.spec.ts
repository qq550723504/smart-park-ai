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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...evidence, domainStatus: { alerts: 'OK', devices: 'UNAVAILABLE', energy: 'OK' }, devices: [] }), { status: 200 })))
    const wrapper = mount(AnomalyEvidenceDrawer, { props: { role: 'ADMIN', buildingId: 'B1', open: true } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('设备数据暂不可用'))

    expect(wrapper.text()).toContain('REDACTED: POWER · OPEN')
  })
})
