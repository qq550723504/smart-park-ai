import { defineComponent, h, type PropType } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EnergyAnalysis from './EnergyAnalysis.vue'
import { getEnergyTimeSeries } from '../../services/energyTimeSeriesApi'
import { getAnomalyEvidence } from '../../services/operationsAnomalyApi'
import { getAnalysisStatus, startAnalysis, submitClarification } from '../../services/analyticsApi'
import type { CustomerAnalysisContext } from '../../types/customer'
import type { EnergyTimeSeriesResponse, EnergyTimeSeriesStatus } from '../../types/energyTimeSeries'
import type { AnomalyEvidence } from '../../types/operationsAnomaly'
import type { EnergyAnalysisPoint } from './EnergyAnalysisChart.vue'

vi.mock('../../services/energyTimeSeriesApi', () => ({ getEnergyTimeSeries: vi.fn() }))
vi.mock('../../services/operationsAnomalyApi', () => ({ getAnomalyEvidence: vi.fn() }))
vi.mock('../../services/analyticsApi', () => ({
  AnalyticsApiError: class AnalyticsApiError extends Error {
    constructor(public readonly status: number, message: string) {
      super(message)
    }
  },
  getAnalysisStatus: vi.fn(),
  startAnalysis: vi.fn(),
  submitClarification: vi.fn(),
}))

const context: CustomerAnalysisContext = {
  buildingId: 'B1',
  buildingName: '创新中心',
  anomalyId: 'ALT-B1',
  title: '创新中心能耗偏离基线',
  priority: '中',
  summary: { buildingId: 'B1', alertCount: 2, highRiskAlertCount: 0, offlineDeviceCount: 1, energyDeviationPct: 18.5 },
  overviewDomainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
  anomalyWindow: {
    from: '2026-09-01T00:18:26Z',
    to: '2026-09-09T00:18:26Z',
    timezone: 'Asia/Shanghai',
  },
  energyWindow: {
    from: '2026-09-08T00:00:00.000Z',
    to: '2026-09-09T00:00:00.000Z',
    timezone: 'Asia/Shanghai',
    granularity: 'HOUR',
  },
  source: 'OPERATIONS_ANALYTICS',
}

function series(metric: string, values: number[], status: EnergyTimeSeriesStatus = 'AVAILABLE', buildingId = 'B1'): EnergyTimeSeriesResponse {
  const timestamps = ['2026-09-08T00:00:00.000Z', '2026-09-08T01:00:00.000Z']
  return {
    metric,
    unit: 'kWh',
    timezone: 'Asia/Shanghai',
    window: { from: context.energyWindow.from, to: context.energyWindow.to, granularity: 'HOUR' },
    status,
    series: status === 'UNAVAILABLE' ? [] : [{
      buildingId,
      points: values.map((value, index) => ({ timestamp: timestamps[index]!, value })),
      missingTimestamps: status === 'PARTIAL' ? ['2026-09-08T02:00:00.000Z'] : [],
    }],
    asOf: status === 'UNAVAILABLE' ? null : timestamps[values.length - 1]!,
    source: { system: 'OPERATIONS_ANALYTICS', metricDefinition: metric, status },
    evidence: [],
  }
}

function anomalyEvidence(buildingId = 'B1'): AnomalyEvidence {
  return {
    buildingId,
    window: context.anomalyWindow,
    asOf: '2026-09-08T01:00:00.000Z',
    alerts: [{ alertId: `ALT-${buildingId}`, deviceId: `AC-${buildingId}-07`, category: 'TEMPERATURE', status: 'OPEN', occurredAt: '2026-09-08T01:00:00.000Z' }],
    devices: [{ deviceId: `LFT-${buildingId}-01`, deviceType: 'ELEVATOR', status: 'OFFLINE', snapshotAt: '2026-09-08T00:30:00.000Z' }],
    energy: [{ meterId: `MTR-${buildingId}-1`, kwh: 42, baselineKwh: 35, deviationPct: 20, measuredAt: '2026-09-08T01:00:00.000Z' }],
    domainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => { resolve = next })
  return { promise, resolve }
}

const chartStub = defineComponent({
  name: 'EnergyAnalysisChart',
  props: {
    points: { type: Array as PropType<EnergyAnalysisPoint[]>, required: true },
    timezone: { type: String, required: true },
    unit: { type: String, required: true },
  },
  setup(props) {
    return () => h('div', {
      'data-analysis-chart': '',
      'data-points': JSON.stringify(props.points),
      'data-timezone': props.timezone,
      'data-unit': props.unit,
    })
  },
})

async function mountLoaded(current: CustomerAnalysisContext | null = context) {
  const wrapper = mount(EnergyAnalysis, {
    props: { context: current, active: true, analysisPollIntervalMs: 60_000 },
    global: { stubs: { EnergyAnalysisChart: chartStub } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(getEnergyTimeSeries).mockImplementation(async (_role, filters) => filters.metric === 'energy_baseline_kwh'
    ? series('energy_baseline_kwh', [80, 90])
    : series('energy_kwh', [100, 110]))
  vi.mocked(getAnomalyEvidence).mockResolvedValue(anomalyEvidence())
  vi.mocked(startAnalysis).mockResolvedValue({ runId: 'run-1' })
  vi.mocked(getAnalysisStatus).mockResolvedValue({
    runId: 'run-1', status: 'COMPLETED', summary: '受控模型结论', createdAt: '2026-09-09T00:00:00Z',
  })
  vi.mocked(submitClarification).mockResolvedValue({
    runId: 'run-1', status: 'RUNNING', createdAt: '2026-09-09T00:00:00Z',
  })
})

afterEach(() => vi.resetAllMocks())

describe('EnergyAnalysis', () => {
  it('reads one building with the inherited hourly window and keeps the anomaly window separate', async () => {
    const wrapper = await mountLoaded()

    expect(getEnergyTimeSeries).toHaveBeenCalledWith('VIEWER', {
      buildingIds: ['B1'],
      from: context.energyWindow.from,
      to: context.energyWindow.to,
      granularity: 'HOUR',
      metric: 'energy_kwh',
    })
    expect(getEnergyTimeSeries).toHaveBeenCalledWith('VIEWER', expect.objectContaining({
      buildingIds: ['B1'], metric: 'energy_baseline_kwh',
    }))
    expect(getAnomalyEvidence).toHaveBeenCalledWith('VIEWER', 'B1', {
      from: context.anomalyWindow.from,
      to: context.anomalyWindow.to,
    })
    expect(wrapper.text()).toContain('210 kWh')
    expect(wrapper.text()).toContain('MTR-B1-1')
    expect(wrapper.text()).toContain('LFT-B1-01')
    expect(wrapper.get('[data-analysis-chart]').attributes('data-timezone')).toBe('Asia/Shanghai')
    expect(wrapper.get('[data-analysis-basis]').text()).toContain('异常依据周期')
    expect(wrapper.get('[data-analysis-basis]').text()).toContain('能耗图周期')
    expect(wrapper.text()).not.toMatch(/SQL|Trace|run-1/)
  })

  it('shows the newest observation when one meter has multiple evidence rows', async () => {
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...anomalyEvidence(),
      energy: [
        { meterId: 'MTR-B1-1', kwh: 42, baselineKwh: 35, deviationPct: 20, measuredAt: '2026-09-08T01:00:00.000Z' },
        { meterId: 'MTR-B1-1', kwh: 30, baselineKwh: 28, deviationPct: 7.1, measuredAt: '2026-09-08T00:00:00.000Z' },
      ],
    })

    const wrapper = await mountLoaded()

    const meterRow = wrapper.findAll('tbody tr').find((row) => row.text().includes('MTR-B1-1'))
    expect(meterRow?.text()).toContain('42 kWh')
    expect(meterRow?.text()).not.toContain('30 kWh')
  })

  it('preserves missing hourly buckets and withholds a complete-window deviation for partial data', async () => {
    vi.mocked(getEnergyTimeSeries).mockImplementation(async (_role, filters) => filters.metric === 'energy_baseline_kwh'
      ? series('energy_baseline_kwh', [80, 90], 'PARTIAL')
      : series('energy_kwh', [100, 110]))

    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('部分小时缺失，曲线保留断点')
    expect(wrapper.get('[data-analysis-chart]').attributes('data-points')).toContain('"baseline":null')
    expect(wrapper.text()).not.toContain('完整窗口偏差')
    expect(wrapper.text()).not.toContain('正常范围')
  })

  it('keeps the loading state visible until the inherited-context requests settle', async () => {
    const slowActual = deferred<EnergyTimeSeriesResponse>()
    const slowBaseline = deferred<EnergyTimeSeriesResponse>()
    const slowEvidence = deferred<AnomalyEvidence>()
    vi.mocked(getEnergyTimeSeries)
      .mockReturnValueOnce(slowActual.promise)
      .mockReturnValueOnce(slowBaseline.promise)
    vi.mocked(getAnomalyEvidence).mockReturnValueOnce(slowEvidence.promise)

    const wrapper = mount(EnergyAnalysis, {
      props: { context, active: true, analysisPollIntervalMs: 60_000 },
      global: { stubs: { EnergyAnalysisChart: chartStub } },
    })
    expect(wrapper.text()).toContain('正在读取单楼宇小时数据')

    slowActual.resolve(series('energy_kwh', [100, 110]))
    slowBaseline.resolve(series('energy_baseline_kwh', [80, 90]))
    slowEvidence.resolve(anomalyEvidence())
    await flushPromises()

    expect(wrapper.text()).toContain('210 kWh')
    expect(wrapper.text()).not.toContain('正在读取单楼宇小时数据')
  })

  it('renders each data domain as it settles without waiting for slower requests', async () => {
    const slowBaseline = deferred<EnergyTimeSeriesResponse>()
    const slowEvidence = deferred<AnomalyEvidence>()
    vi.mocked(getEnergyTimeSeries).mockImplementation(async (_role, filters) => {
      if (filters.metric === 'energy_baseline_kwh') return slowBaseline.promise
      return series('energy_kwh', [100, 110])
    })
    vi.mocked(getAnomalyEvidence).mockReturnValue(slowEvidence.promise)

    const wrapper = mount(EnergyAnalysis, {
      props: { context, active: true, analysisPollIntervalMs: 60_000 },
      global: { stubs: { EnergyAnalysisChart: chartStub } },
    })
    await flushPromises()

    expect(wrapper.get('[data-analysis-chart]').attributes('data-points')).toContain('"actual":100')
    expect(wrapper.text()).toContain('210 kWh')
    expect(wrapper.text()).toContain('正在读取单楼宇小时数据')
    expect(wrapper.text()).toContain('正在读取相关设备')

    slowBaseline.resolve(series('energy_baseline_kwh', [80, 90]))
    await flushPromises()
    expect(wrapper.text()).toContain('完整窗口偏差')
    expect(wrapper.text()).not.toContain('正在读取单楼宇小时数据')
    expect(wrapper.text()).toContain('正在读取相关设备')

    slowEvidence.resolve(anomalyEvidence())
    await flushPromises()
    expect(wrapper.text()).toContain('MTR-B1-1')
  })

  it('shows an explicit unavailable state instead of manufacturing empty-window values', async () => {
    vi.mocked(getEnergyTimeSeries).mockImplementation(async (_role, filters) => series(filters.metric!, [], 'UNAVAILABLE'))
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...anomalyEvidence(),
      alerts: [],
      devices: [],
      energy: [],
    })

    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('该楼宇实际用电暂不可用')
    expect(wrapper.text()).toContain('该楼宇基线用电暂不可用')
    expect(wrapper.text()).toContain('当前能耗窗口没有可绘制数据')
    expect(wrapper.text()).toContain('当前窗口暂无相关设备或计量点')
    expect(wrapper.text()).not.toContain('完整窗口偏差')
  })

  it('shows observed zero counts only when the overview domains are complete', async () => {
    const zeroContext: CustomerAnalysisContext = {
      ...context,
      summary: { ...context.summary!, alertCount: 0, highRiskAlertCount: 0, offlineDeviceCount: 0 },
    }
    const wrapper = await mountLoaded(zeroContext)

    expect(wrapper.get('[data-overview-alert-status] strong').text()).toBe('0 条')
    expect(wrapper.text()).toContain('同期未处理告警 0 条')
    expect(wrapper.text()).toContain('同期离线设备 0 台')
  })

  it('does not turn unavailable overview domains with default zeroes into confirmed zero facts', async () => {
    const unavailableContext: CustomerAnalysisContext = {
      ...context,
      summary: { ...context.summary!, alertCount: 0, highRiskAlertCount: 0, offlineDeviceCount: 0 },
      overviewDomainStatus: { alerts: 'UNAVAILABLE', devices: 'UNAVAILABLE', energy: 'OK' },
    }
    const wrapper = await mountLoaded(unavailableContext)

    expect(wrapper.get('[data-overview-alert-status] strong').text()).toBe('未取得')
    expect(wrapper.text()).toContain('同期未处理告警 未取得')
    expect(wrapper.text()).toContain('同期离线设备 未取得')
    expect(wrapper.text()).not.toContain('同期未处理告警 0 条')
    expect(wrapper.text()).not.toContain('同期离线设备 0 台')
    expect(wrapper.text()).toContain('先补充告警域数据')
  })

  it('labels partially observed overview values without claiming complete totals', async () => {
    const partialContext: CustomerAnalysisContext = {
      ...context,
      summary: { ...context.summary!, alertCount: 2, offlineDeviceCount: 0, energyDeviationPct: 18.5 },
      overviewDomainStatus: { alerts: 'PARTIAL', devices: 'PARTIAL', energy: 'PARTIAL' },
    }
    const wrapper = await mountLoaded(partialContext)

    expect(wrapper.get('[data-overview-alert-status] strong').text()).toBe('至少 2 条')
    expect(wrapper.get('[data-overview-energy-status] strong').text()).toBe('已观测 18.5%')
    expect(wrapper.text()).toContain('同期离线设备 总数未知')
    expect(wrapper.text()).not.toContain('同期离线设备 0 台')
  })

  it('keeps partial evidence rows while showing every incomplete domain notice', async () => {
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...anomalyEvidence(),
      domainStatus: { alerts: 'PARTIAL', devices: 'UNAVAILABLE', energy: 'OK' },
    })

    const wrapper = await mountLoaded()
    const notice = wrapper.get('[data-evidence-availability]')

    expect(notice.text()).toContain('告警依据仅部分可用')
    expect(notice.text()).toContain('设备依据暂不可用')
    expect(wrapper.text()).toContain('AC-B1-07')
    expect(wrapper.text()).toContain('MTR-B1-1')
  })

  it('does not describe empty incomplete evidence domains as confirmed empty lists', async () => {
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...anomalyEvidence(),
      alerts: [],
      devices: [],
      energy: [],
      domainStatus: { alerts: 'PARTIAL', devices: 'UNAVAILABLE', energy: 'OK' },
    })

    const wrapper = await mountLoaded()

    expect(wrapper.get('[data-evidence-availability]').text()).toContain('告警依据仅部分可用')
    expect(wrapper.get('[data-evidence-availability]').text()).toContain('设备依据暂不可用')
    expect(wrapper.text()).toContain('暂不能确认是否无相关设备或计量点')
    expect(wrapper.text()).toContain('暂不能确认当前窗口无相关记录')
    expect(wrapper.text()).not.toContain('当前窗口暂无相关设备或计量点')
    expect(wrapper.text()).not.toContain('当前窗口暂无相关记录')
  })

  it('refreshes when availability changes within the same building and window', async () => {
    const wrapper = await mountLoaded()
    const changedContext: CustomerAnalysisContext = {
      ...context,
      summary: { ...context.summary!, alertCount: 0 },
      overviewDomainStatus: { ...context.overviewDomainStatus, alerts: 'UNAVAILABLE' },
    }

    await wrapper.setProps({ context: changedContext })
    await flushPromises()

    expect(getEnergyTimeSeries).toHaveBeenCalledTimes(4)
    expect(wrapper.get('[data-overview-alert-status] strong').text()).toBe('未取得')
  })

  it('shows safe failures and retries the same inherited context without success fallback', async () => {
    vi.mocked(getEnergyTimeSeries).mockRejectedValueOnce(new Error('jdbc password')).mockRejectedValueOnce(new Error('sql trace'))
    vi.mocked(getAnomalyEvidence).mockRejectedValueOnce(new Error('secret'))
    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('该楼宇实际用电读取失败')
    expect(wrapper.text()).toContain('该楼宇基线用电读取失败')
    expect(wrapper.text()).toContain('告警与设备依据读取失败')
    expect(wrapper.text()).not.toMatch(/jdbc password|sql trace|secret/)
    expect(wrapper.text()).not.toContain('210 kWh')

    await wrapper.get('[data-analysis-basis]').trigger('click')
    await wrapper.get('.energy-analysis__inline-error button').trigger('click')
    await flushPromises()

    expect(getEnergyTimeSeries).toHaveBeenCalledTimes(4)
    expect(wrapper.text()).toContain('210 kWh')
  })

  it('ignores old responses after a rapid building switch', async () => {
    const oldActual = deferred<EnergyTimeSeriesResponse>()
    const oldBaseline = deferred<EnergyTimeSeriesResponse>()
    const oldEvidence = deferred<AnomalyEvidence>()
    vi.mocked(getEnergyTimeSeries)
      .mockReturnValueOnce(oldActual.promise)
      .mockReturnValueOnce(oldBaseline.promise)
      .mockResolvedValueOnce(series('energy_kwh', [30, 40], 'AVAILABLE', 'B2'))
      .mockResolvedValueOnce(series('energy_baseline_kwh', [25, 35], 'AVAILABLE', 'B2'))
    vi.mocked(getAnomalyEvidence)
      .mockReturnValueOnce(oldEvidence.promise)
      .mockResolvedValueOnce(anomalyEvidence('B2'))
    const wrapper = mount(EnergyAnalysis, {
      props: { context, active: true, analysisPollIntervalMs: 1 },
      global: { stubs: { EnergyAnalysisChart: chartStub } },
    })
    await vi.waitFor(() => expect(getEnergyTimeSeries).toHaveBeenCalledTimes(2))
    const nextContext: CustomerAnalysisContext = {
      ...context,
      buildingId: 'B2',
      buildingName: '研发大厦',
      title: '研发大厦存在告警',
      summary: { ...context.summary!, buildingId: 'B2' },
    }

    await wrapper.setProps({ context: nextContext })
    await flushPromises()
    expect(wrapper.text()).toContain('研发大厦')
    expect(wrapper.text()).toContain('MTR-B2-1')

    oldActual.resolve(series('energy_kwh', [900, 900]))
    oldBaseline.resolve(series('energy_baseline_kwh', [1, 1]))
    oldEvidence.resolve(anomalyEvidence('B1'))
    await flushPromises()

    expect(wrapper.text()).toContain('研发大厦')
    expect(wrapper.text()).toContain('MTR-B2-1')
    expect(wrapper.text()).not.toContain('MTR-B1-1')
    expect(wrapper.text()).not.toContain('1,800 kWh')
  })

  it('refreshes evidence when only the inherited anomaly window changes', async () => {
    const wrapper = await mountLoaded()
    const nextContext: CustomerAnalysisContext = {
      ...context,
      anomalyWindow: { ...context.anomalyWindow, to: '2026-09-09T00:19:26Z' },
    }
    vi.mocked(getAnomalyEvidence).mockResolvedValue({
      ...anomalyEvidence(),
      window: nextContext.anomalyWindow,
    })

    await wrapper.setProps({ context: nextContext })
    await flushPromises()

    expect(getAnomalyEvidence).toHaveBeenLastCalledWith('VIEWER', 'B1', {
      from: nextContext.anomalyWindow.from,
      to: nextContext.anomalyWindow.to,
    })
  })

  it('rejects fulfilled data whose source, building, or window does not match the active context', async () => {
    vi.mocked(getEnergyTimeSeries).mockImplementation(async (_role, filters) => {
      const response = filters.metric === 'energy_baseline_kwh'
        ? series('energy_baseline_kwh', [80, 90])
        : series('energy_kwh', [100, 110], 'AVAILABLE', 'B2')
      if (filters.metric === 'energy_baseline_kwh') response.source.system = 'WRONG_SOURCE'
      return response
    })
    vi.mocked(getAnomalyEvidence).mockResolvedValue(anomalyEvidence('B2'))

    const wrapper = await mountLoaded()

    expect(wrapper.text()).toContain('实际用电返回范围与当前选择不一致')
    expect(wrapper.text()).toContain('基线用电返回范围与当前选择不一致')
    expect(wrapper.text()).toContain('告警与设备依据返回范围与当前选择不一致')
    expect(wrapper.text()).not.toContain('210 kWh')
    expect(wrapper.text()).not.toContain('MTR-B2-1')
  })

  it('starts the real analysis only by explicit action and suppresses duplicate clicks', async () => {
    const started = deferred<{ runId: string }>()
    vi.mocked(startAnalysis).mockReturnValue(started.promise)
    const wrapper = await mountLoaded()
    expect(startAnalysis).not.toHaveBeenCalled()

    await wrapper.get('[data-run-ai-analysis]').trigger('click')
    await wrapper.get('[data-run-ai-analysis]').trigger('click')
    expect(startAnalysis).toHaveBeenCalledTimes(1)
    expect(startAnalysis).toHaveBeenCalledWith(expect.stringContaining('building_id=B1'))
    expect(startAnalysis).toHaveBeenCalledWith(expect.stringContaining(context.energyWindow.from))

    started.resolve({ runId: 'run-1' })
    await flushPromises()
    expect(wrapper.text()).toContain('受控模型结论')
    expect(wrapper.text()).not.toContain('run-1')
  })

  it('presents clarification in business language and resumes with the selected backend option', async () => {
    vi.mocked(getAnalysisStatus)
      .mockResolvedValueOnce({
        runId: 'run-1',
        status: 'NEEDS_CLARIFICATION',
        clarificationQuestions: ['请选择能耗口径'],
        clarificationOptions: [['energy_deviation_pct', 'energy_kwh']],
        createdAt: '2026-09-09T00:00:00Z',
      })
      .mockResolvedValueOnce({
        runId: 'run-1', status: 'COMPLETED', summary: '澄清后的结论', createdAt: '2026-09-09T00:00:01Z',
      })
    const wrapper = await mountLoaded()

    await wrapper.get('[data-run-ai-analysis]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('需要确认指标口径')
    expect(wrapper.text()).toContain('能耗基线偏差率')
    expect(wrapper.get('[data-run-ai-analysis]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-run-ai-analysis]').text()).toContain('等待口径确认')

    await wrapper.setProps({ context: { ...context, anomalyId: 'ALT-LATE' } })
    await flushPromises()
    expect(wrapper.text()).toContain('需要确认指标口径')
    expect(wrapper.get('[data-run-ai-analysis]').attributes('disabled')).toBeDefined()
    expect(startAnalysis).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ context: null })
    await wrapper.setProps({ context })
    await flushPromises()
    expect(wrapper.text()).toContain('需要确认指标口径')
    expect(wrapper.get('[data-run-ai-analysis]').attributes('disabled')).toBeDefined()
    expect(startAnalysis).toHaveBeenCalledTimes(1)

    await wrapper.get('.energy-analysis__clarification button').trigger('click')
    await flushPromises()
    expect(submitClarification).toHaveBeenCalledWith('run-1', [{ term: '请选择能耗口径', metric: 'energy_deviation_pct' }])
    expect(wrapper.text()).toContain('澄清后的结论')
  })

  it('keeps a clarification recoverable when its submission fails', async () => {
    vi.mocked(getAnalysisStatus).mockResolvedValue({
      runId: 'run-1',
      status: 'NEEDS_CLARIFICATION',
      clarificationQuestions: ['请选择能耗口径'],
      clarificationOptions: [['energy_deviation_pct']],
      createdAt: '2026-09-09T00:00:00Z',
    })
    vi.mocked(submitClarification).mockRejectedValue(new Error('temporary upstream failure'))
    const wrapper = await mountLoaded()

    await wrapper.get('[data-run-ai-analysis]').trigger('click')
    await flushPromises()
    await wrapper.get('.energy-analysis__clarification button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('需要确认指标口径')
    expect(wrapper.text()).toContain('口径提交未完成，请保留当前选择并重试')
    expect(wrapper.get('[data-run-ai-analysis]').attributes('disabled')).toBeDefined()
    expect(startAnalysis).toHaveBeenCalledTimes(1)
  })

  it('shows a clear selection prompt when analysis is opened without context', async () => {
    const wrapper = await mountLoaded(null)

    expect(wrapper.text()).toContain('请先选择需要分析的楼宇')
    expect(getEnergyTimeSeries).not.toHaveBeenCalled()
  })
})
