import { defineComponent, h, type PropType } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ParkOverview from './ParkOverview.vue'
import { getAnomalyEvidence, getAnomalyOverview } from '../../services/operationsAnomalyApi'
import { getEnergyTimeSeries } from '../../services/energyTimeSeriesApi'
import { getOperationsMetrics, listCollaborationWorkItems } from '../../services/workflowApi'
import type { EnergyTimeSeriesResponse } from '../../types/energyTimeSeries'
import type { AnomalyEvidence, AnomalyOverview } from '../../types/operationsAnomaly'
import type { CollaborationWorkItem } from '../../types/collaborationCenter'
import type { CustomerChartDatum } from './CustomerOverviewChart.vue'

vi.mock('../../services/operationsAnomalyApi', () => ({
  getAnomalyOverview: vi.fn(),
  getAnomalyEvidence: vi.fn(),
}))
vi.mock('../../services/energyTimeSeriesApi', () => ({ getEnergyTimeSeries: vi.fn() }))
vi.mock('../../services/workflowApi', () => ({
  getOperationsMetrics: vi.fn(),
  listCollaborationWorkItems: vi.fn(),
}))

const windowRange = {
  from: '2026-09-01T00:18:26Z',
  to: '2026-09-09T00:18:26Z',
  timezone: 'Asia/Shanghai',
}
const overview: AnomalyOverview = {
  window: windowRange,
  asOf: '2026-09-08T23:00:00Z',
  summary: { alertCount: 3, highRiskAlertCount: 1, offlineDeviceCount: 1, affectedBuildingCount: 2 },
  breakdowns: {
    statuses: [{ key: 'OPEN', count: 2 }, { key: 'RESOLVED', count: 1 }],
    categories: [{ key: 'ENERGY', count: 2 }, { key: 'ACCESS', count: 1 }],
  },
  buildings: [
    { buildingId: 'B1', alertCount: 1, highRiskAlertCount: 0, offlineDeviceCount: 0, energyDeviationPct: 12 },
    { buildingId: 'B2', alertCount: 2, highRiskAlertCount: 1, offlineDeviceCount: 1, energyDeviationPct: 8 },
  ],
  domainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
}
const energy: EnergyTimeSeriesResponse = {
  metric: 'energy_kwh',
  unit: 'kWh',
  timezone: 'Asia/Shanghai',
  window: { from: windowRange.from, to: windowRange.to, granularity: 'HOUR' },
  status: 'AVAILABLE',
  series: [
    { buildingId: 'B1', points: [{ timestamp: '2026-09-08T22:00:00Z', value: 10 }, { timestamp: '2026-09-08T23:00:00Z', value: 20 }], missingTimestamps: [] },
    { buildingId: 'B2', points: [{ timestamp: '2026-09-08T22:00:00Z', value: 30 }, { timestamp: '2026-09-08T23:00:00Z', value: 40 }], missingTimestamps: [] },
  ],
  asOf: '2026-09-08T23:00:00Z',
  source: { system: 'OPERATIONS_ANALYTICS', metricDefinition: 'hourly observed kWh', status: 'AVAILABLE' },
  evidence: [],
}
const evidence: AnomalyEvidence = {
  buildingId: 'B1',
  window: windowRange,
  asOf: '2026-09-08T23:00:00Z',
  alerts: [{ alertId: 'ALT-1', riskLevel: 'HIGH', occurredAt: '2026-09-08T22:00:00Z', redactedSummary: 'REDACTED: 能耗告警 · OPEN' }],
  devices: [],
  energy: [],
  domainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
}
const activeWorkItem: CollaborationWorkItem = {
  id: 'ALERT_WORKFLOW:wf-1',
  source: 'ALERT_WORKFLOW',
  status: 'WAITING_APPROVAL',
  priority: 'HIGH',
  title: 'B2 能耗异常待确认',
  safeSummary: '等待人工确认',
  parkId: 'PARK-1',
  buildingId: 'B2',
  deviceId: 'DEV-1',
  updatedAt: '2026-09-08T22:00:00Z',
  openedAt: '2026-09-08T21:00:00Z',
  slaDueAt: '2026-09-09T02:00:00Z',
  slaState: 'DUE_SOON',
  detailPath: 'workflow',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => { resolve = next })
  return { promise, resolve }
}

const chartStub = defineComponent({
  name: 'CustomerOverviewChart',
  props: {
    kind: { type: String, required: true },
    data: { type: Array as PropType<CustomerChartDatum[]>, required: true },
    label: { type: String, required: true },
  },
  setup(props) {
    return () => h('div', {
      'data-chart-kind': props.kind,
      'data-chart-values': JSON.stringify(props.data.map((item) => item.value)),
    })
  },
})

async function mountLoaded() {
  const wrapper = mount(ParkOverview, { global: { stubs: { CustomerOverviewChart: chartStub } } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(getAnomalyOverview).mockResolvedValue(overview)
  vi.mocked(getEnergyTimeSeries).mockResolvedValue(energy)
  vi.mocked(getAnomalyEvidence).mockResolvedValue(evidence)
  vi.mocked(getOperationsMetrics).mockResolvedValue({
    workflowCount: 4,
    completedWorkflowCount: 3,
    customerSessionCount: 5,
    humanTicketCount: 3,
    auditEntryCount: 7,
    feedbackCount: 0,
    positiveFeedbackCount: 0,
    knowledgeDocumentCount: 4,
    activeKnowledgeDocumentCount: 4,
  })
  vi.mocked(listCollaborationWorkItems).mockResolvedValue([activeWorkItem])
})

afterEach(() => vi.resetAllMocks())

describe('ParkOverview', () => {
  it('derives the overview from existing APIs without inventing device availability', async () => {
    const wrapper = await mountLoaded()

    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('100')
    expect(wrapper.get('[data-kpi="buildings"] strong').text()).toContain('2')
    expect(wrapper.get('[data-kpi="events"] strong').text()).toContain('2')
    expect(wrapper.get('[data-kpi="service-requests"] strong').text()).toContain('3')
    expect(wrapper.text()).toContain('运营规则聚合 · 未调用模型')
    expect(wrapper.text()).not.toContain('设备运行率')
    expect(getAnomalyOverview).toHaveBeenCalledWith('VIEWER', { status: 'OPEN' })
    expect(getEnergyTimeSeries).toHaveBeenCalledWith('VIEWER', {
      buildingIds: ['B1', 'B2', 'B3'],
      from: '2026-09-08T00:00:00.000Z',
      to: '2026-09-09T00:00:00.000Z',
      granularity: 'HOUR',
    })
    expect(listCollaborationWorkItems).toHaveBeenCalledWith('CUSTOMER_AGENT', { limit: 50, sort: 'sla' })
  })

  it('queries the complete current park catalog even when no building is affected', async () => {
    vi.mocked(getAnomalyOverview).mockResolvedValue({
      ...overview,
      summary: { alertCount: 0, highRiskAlertCount: 0, offlineDeviceCount: 0, affectedBuildingCount: 0 },
      buildings: [],
    })

    const wrapper = await mountLoaded()

    expect(getEnergyTimeSeries).toHaveBeenCalledWith('VIEWER', expect.objectContaining({
      buildingIds: ['B1', 'B2', 'B3'],
    }))
    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('100')
    expect(wrapper.text()).toContain('尚无可选楼宇')
  })

  it('clears old energy while a refreshed energy request is pending', async () => {
    const wrapper = await mountLoaded()
    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('100')

    const pendingEnergy = deferred<EnergyTimeSeriesResponse>()
    vi.mocked(getEnergyTimeSeries).mockReturnValueOnce(pendingEnergy.promise)
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await vi.waitFor(() => expect(getEnergyTimeSeries).toHaveBeenCalledTimes(2))

    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('—')
    expect(wrapper.get('[data-kpi="energy"]').text()).toContain('正在读取能耗观测…')

    pendingEnergy.resolve(energy)
    await flushPromises()
    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('100')
  })

  it('shows an explicit unavailable state for a valid unavailable energy response', async () => {
    vi.mocked(getEnergyTimeSeries).mockResolvedValue({
      ...energy,
      status: 'UNAVAILABLE',
      series: [],
      source: { ...energy.source, status: 'UNAVAILABLE' },
    })

    const wrapper = await mountLoaded()

    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('—')
    expect(wrapper.get('[data-kpi="energy"]').text()).toContain('最近 24 小时能耗数据源暂不可用')
    expect(wrapper.text()).not.toContain('当前窗口暂无可绘制的能耗观测')
  })

  it('hides the previous overview while a refreshed overview request is pending', async () => {
    const wrapper = await mountLoaded()
    expect(wrapper.get('[data-kpi="buildings"] strong').text()).toContain('2')

    const pendingOverview = deferred<AnomalyOverview>()
    vi.mocked(getAnomalyOverview).mockReturnValueOnce(pendingOverview.promise)
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await vi.waitFor(() => expect(getAnomalyOverview).toHaveBeenCalledTimes(2))

    expect(wrapper.get('[data-kpi="buildings"] strong').text()).toContain('—')
    expect(wrapper.text()).toContain('正在读取园区运营数据…')
    expect(wrapper.find('[data-building-id="B1"]').exists()).toBe(false)

    pendingOverview.resolve(overview)
    await flushPromises()
  })

  it('does not accept a map selection while the overview is revalidating', async () => {
    const wrapper = await mountLoaded()
    const evidenceCalls = vi.mocked(getAnomalyEvidence).mock.calls.length
    const pendingOverview = deferred<AnomalyOverview>()
    vi.mocked(getAnomalyOverview).mockReturnValueOnce(pendingOverview.promise)

    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await vi.waitFor(() => expect(getAnomalyOverview).toHaveBeenCalledTimes(2))

    const marker = wrapper.get('[data-building-marker="B3"]')
    expect(marker.attributes('disabled')).toBeDefined()
    await marker.trigger('click')
    expect(getAnomalyEvidence).toHaveBeenCalledTimes(evidenceCalls)

    pendingOverview.resolve(overview)
    await flushPromises()
    expect(wrapper.get('[data-building-marker="B1"]').attributes('aria-pressed')).toBe('true')
  })

  it('renders overview and energy while an independent work-item request is still pending', async () => {
    const pendingWorkItems = deferred<CollaborationWorkItem[]>()
    vi.mocked(listCollaborationWorkItems).mockReturnValue(pendingWorkItems.promise)

    const wrapper = mount(ParkOverview, { global: { stubs: { CustomerOverviewChart: chartStub } } })
    await vi.waitFor(() => expect(getAnomalyEvidence).toHaveBeenCalled())

    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('100')
    expect(wrapper.text()).toContain('正在读取待办…')

    pendingWorkItems.resolve([activeWorkItem])
    await flushPromises()
    expect(wrapper.text()).toContain(activeWorkItem.title)
  })

  it('loads building evidence while the independent energy request is still pending', async () => {
    const pendingEnergy = deferred<EnergyTimeSeriesResponse>()
    vi.mocked(getEnergyTimeSeries).mockReturnValue(pendingEnergy.promise)

    const wrapper = mount(ParkOverview, { global: { stubs: { CustomerOverviewChart: chartStub } } })
    await vi.waitFor(() => expect(getAnomalyEvidence).toHaveBeenCalledWith(
      'VIEWER',
      'B1',
      { from: windowRange.from, to: windowRange.to },
    ))
    await flushPromises()

    expect(wrapper.text()).toContain('REDACTED: 能耗告警 · OPEN')
    expect(wrapper.get('[data-kpi="energy"]').text()).toContain('正在读取能耗观测…')

    pendingEnergy.resolve(energy)
    await flushPromises()
  })

  it('filters terminal work items before selecting the first four actionable todos', async () => {
    vi.mocked(listCollaborationWorkItems).mockResolvedValue([
      { ...activeWorkItem, id: 'done', status: 'COMPLETED', title: '已完成事项' },
      { ...activeWorkItem, id: 'closed', status: 'CLOSED', title: '已关闭事项' },
      { ...activeWorkItem, id: 'cancelled', status: 'CANCELLED', title: '已取消事项' },
      { ...activeWorkItem, id: 'active-1', title: '仍需处理事项' },
    ])

    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('仍需处理事项')
    expect(wrapper.text()).not.toContain('已完成事项')
    expect(wrapper.text()).not.toContain('已关闭事项')
    expect(wrapper.text()).not.toContain('已取消事项')
  })

  it('combines alert, device, and energy evidence in timestamp order', async () => {
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...evidence,
      alerts: [{ ...evidence.alerts[0]!, occurredAt: '2026-09-08T20:00:00Z', redactedSummary: '告警证据' }],
      devices: [{ deviceId: 'DEV-2', snapshotAt: '2026-09-08T23:00:00Z', redactedSummary: '设备证据' }],
      energy: [{ meterId: 'METER-1', measuredAt: '2026-09-08T22:00:00Z', redactedSummary: '能耗证据' }],
    })

    const wrapper = await mountLoaded()
    const rows = wrapper.findAll('.customer-latest__row strong').map((row) => row.text())

    expect(rows).toEqual(['设备证据', '能耗证据', '告警证据'])
  })

  it('does not describe unavailable evidence domains as an empty event list', async () => {
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...evidence,
      alerts: [],
      devices: [],
      energy: [],
      domainStatus: { alerts: 'UNAVAILABLE', devices: 'OK', energy: 'OK' },
    })

    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('部分事件数据暂不可用，当前列表可能不完整')
    expect(wrapper.text()).not.toContain('当前楼宇暂无事件记录')
  })

  it('keeps the partial-evidence notice visible alongside available rows', async () => {
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...evidence,
      domainStatus: { alerts: 'UNAVAILABLE', devices: 'OK', energy: 'OK' },
      alerts: [],
      devices: [{ deviceId: 'DEV-2', snapshotAt: '2026-09-08T23:00:00Z', redactedSummary: '可用设备证据' }],
    })

    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('部分事件数据暂不可用，当前列表可能不完整')
    expect(wrapper.text()).toContain('可用设备证据')
  })

  it('uses the observation time to keep repeated meter evidence keys unique', async () => {
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...evidence,
      alerts: [],
      energy: [
        { meterId: 'METER-1', measuredAt: '2026-09-08T22:00:00Z', redactedSummary: '能耗证据 22 点' },
        { meterId: 'METER-1', measuredAt: '2026-09-08T23:00:00Z', redactedSummary: '能耗证据 23 点' },
      ],
    })

    const wrapper = await mountLoaded()
    const keys = wrapper.findAll('.customer-latest__row').map((row) => row.attributes('data-event-key'))

    expect(keys).toEqual([
      'METER-1:2026-09-08T23:00:00Z',
      'METER-1:2026-09-08T22:00:00Z',
    ])
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('does not describe a zero energy deviation as a deviation', async () => {
    vi.mocked(getAnomalyOverview).mockResolvedValue({
      ...overview,
      buildings: [
        { buildingId: 'B1', alertCount: 1, highRiskAlertCount: 0, offlineDeviceCount: 0, energyDeviationPct: 0 },
      ],
    })

    const wrapper = await mountLoaded()
    const title = wrapper.get('[data-building-id="B1"] strong').text()

    expect(title).toBe('创新中心存在待关注告警')
    expect(title).not.toContain('能耗偏离基线')
  })

  it('uses one building id for map selection and evidence loading', async () => {
    const wrapper = await mountLoaded()
    vi.mocked(getAnomalyEvidence).mockResolvedValueOnce({ ...evidence, buildingId: 'B2', alerts: [] })

    await wrapper.get('[data-building-marker="B2"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-building-marker="B2"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('B2 · 研发大厦')
    expect(getAnomalyEvidence).toHaveBeenLastCalledWith('VIEWER', 'B2', { from: windowRange.from, to: windowRange.to })
  })

  it('preserves an explicitly selected catalog building across reactivation', async () => {
    const wrapper = await mountLoaded()
    vi.mocked(getAnomalyEvidence).mockResolvedValue({ ...evidence, buildingId: 'B3', alerts: [] })

    await wrapper.get('[data-building-marker="B3"]').trigger('click')
    await flushPromises()
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(wrapper.get('[data-building-marker="B3"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('B3 · 运营中心')
    expect(getAnomalyEvidence).toHaveBeenLastCalledWith('VIEWER', 'B3', { from: windowRange.from, to: windowRange.to })
  })

  it('derives attention badges and map state from each building signal', async () => {
    vi.mocked(getAnomalyOverview).mockResolvedValue({
      ...overview,
      summary: { alertCount: 1, highRiskAlertCount: 1, offlineDeviceCount: 0, affectedBuildingCount: 2 },
      buildings: [
        { buildingId: 'B1', alertCount: 0, highRiskAlertCount: 0, offlineDeviceCount: 0, energyDeviationPct: 12 },
        { buildingId: 'B2', alertCount: 1, highRiskAlertCount: 1, offlineDeviceCount: 0, energyDeviationPct: 0 },
      ],
    })

    const wrapper = await mountLoaded()

    expect(wrapper.get('[data-building-id="B1"] .customer-attention__level').text()).toBe('偏差')
    expect(wrapper.get('[data-building-id="B2"] .customer-attention__level').text()).toBe('高')
    expect(wrapper.get('[data-building-marker="B1"]').classes()).toContain('is-warning')
  })

  it('treats an absent catalog building as normal only when all anomaly domains are complete', async () => {
    const completeWrapper = await mountLoaded()
    expect(completeWrapper.get('[data-building-marker="B3"]').classes()).toContain('is-normal')
    completeWrapper.unmount()

    vi.mocked(getAnomalyOverview).mockResolvedValue({
      ...overview,
      domainStatus: { alerts: 'OK', devices: 'OK', energy: 'UNAVAILABLE' },
    })
    const incompleteWrapper = await mountLoaded()
    expect(incompleteWrapper.get('[data-building-marker="B3"]').classes()).toContain('is-unknown')
  })

  it('clears old evidence when a refreshed overview contains no buildings', async () => {
    const wrapper = await mountLoaded()
    expect(wrapper.text()).toContain('REDACTED: 能耗告警 · OPEN')

    vi.mocked(getAnomalyOverview).mockResolvedValue({
      ...overview,
      summary: { alertCount: 0, highRiskAlertCount: 0, offlineDeviceCount: 0, affectedBuildingCount: 0 },
      buildings: [],
    })
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(wrapper.text()).not.toContain('REDACTED: 能耗告警 · OPEN')
    expect(wrapper.text()).toContain('选择楼宇后查看')
    expect(wrapper.text()).toContain('当前楼宇暂无事件记录')
  })

  it('ignores a stale refresh after a newer activation has completed', async () => {
    const firstEnergy = deferred<EnergyTimeSeriesResponse>()
    vi.mocked(getEnergyTimeSeries)
      .mockReturnValueOnce(firstEnergy.promise)
      .mockResolvedValueOnce(energy)
    vi.mocked(getOperationsMetrics)
      .mockResolvedValueOnce({
        workflowCount: 1,
        completedWorkflowCount: 0,
        customerSessionCount: 0,
        humanTicketCount: 1,
        auditEntryCount: 0,
        feedbackCount: 0,
        positiveFeedbackCount: 0,
        knowledgeDocumentCount: 0,
        activeKnowledgeDocumentCount: 0,
      })
      .mockResolvedValueOnce({
        workflowCount: 9,
        completedWorkflowCount: 9,
        customerSessionCount: 9,
        humanTicketCount: 9,
        auditEntryCount: 9,
        feedbackCount: 0,
        positiveFeedbackCount: 0,
        knowledgeDocumentCount: 9,
        activeKnowledgeDocumentCount: 9,
      })
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...evidence,
      alerts: [{ ...evidence.alerts[0]!, redactedSummary: 'LATEST SAFE EVENT' }],
    })

    const wrapper = mount(ParkOverview, { global: { stubs: { CustomerOverviewChart: chartStub } } })
    await vi.waitFor(() => expect(getEnergyTimeSeries).toHaveBeenCalledTimes(1))
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await vi.waitFor(() => expect(getEnergyTimeSeries).toHaveBeenCalledTimes(2))
    await flushPromises()

    expect(wrapper.get('[data-kpi="service-requests"] strong').text()).toContain('9')
    expect(wrapper.text()).toContain('LATEST SAFE EVENT')
    expect(getAnomalyEvidence).toHaveBeenCalledTimes(2)

    firstEnergy.resolve(energy)
    await flushPromises()

    expect(wrapper.get('[data-kpi="service-requests"] strong').text()).toContain('9')
    expect(wrapper.text()).toContain('LATEST SAFE EVENT')
    expect(getAnomalyEvidence).toHaveBeenCalledTimes(2)
  })

  it('preserves a missing timestamp as a null trend point', async () => {
    vi.mocked(getEnergyTimeSeries).mockResolvedValue({
      ...energy,
      status: 'PARTIAL',
      series: [
        { ...energy.series[0]!, missingTimestamps: ['2026-09-08T23:00:00Z'], points: [energy.series[0]!.points[0]!] },
        energy.series[1]!,
      ],
    })

    const wrapper = await mountLoaded()
    const line = wrapper.get('[data-chart-kind="line"]')

    expect(line.attributes('data-chart-values')).toContain('null')
    expect(wrapper.get('[data-kpi="energy"]').text()).toContain('部分观测，缺口未补零')
  })

  it('shows independent failure states and never substitutes a success fixture', async () => {
    vi.mocked(getAnomalyOverview).mockRejectedValue(new Error('database password SQL trace'))
    vi.mocked(getOperationsMetrics).mockRejectedValue(new Error('secret'))
    vi.mocked(listCollaborationWorkItems).mockRejectedValue(new Error('secret'))

    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('园区运营总览暂不可用')
    expect(wrapper.text()).toContain('页面不会回填预置成功数据')
    expect(wrapper.get('[data-kpi="energy"] strong').text()).toContain('—')
    expect(wrapper.text()).not.toContain('database password')
    expect(wrapper.text()).not.toContain('SQL trace')
  })
})
