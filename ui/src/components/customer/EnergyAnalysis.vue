<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeft, Calendar, Checked, DataAnalysis, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { useOperationsAnalysis } from '../../composables/useOperationsAnalysis'
import { getEnergyTimeSeries } from '../../services/energyTimeSeriesApi'
import { getAnomalyEvidence } from '../../services/operationsAnomalyApi'
import type { CustomerAnalysisContext } from '../../types/customer'
import type { EnergyTimeSeriesFilters, EnergyTimeSeriesResponse } from '../../types/energyTimeSeries'
import type { AnomalyDomain, AnomalyDomainStatus, AnomalyEvidence } from '../../types/operationsAnomaly'
import { customerRecommendations } from '../../utils/customerRecommendations'
import EnergyAnalysisChart, { type EnergyAnalysisPoint } from './EnergyAnalysisChart.vue'

const props = withDefaults(defineProps<{
  context: CustomerAnalysisContext | null
  active?: boolean
  analysisPollIntervalMs?: number
}>(), { active: true })

const emit = defineEmits<{
  back: []
  'open-work-orders': [context: CustomerAnalysisContext]
}>()
const actual = ref<EnergyTimeSeriesResponse | null>(null)
const baseline = ref<EnergyTimeSeriesResponse | null>(null)
const evidence = ref<AnomalyEvidence | null>(null)
const loading = ref({ actual: false, baseline: false, evidence: false })
const errors = ref({ actual: '', baseline: '', evidence: '' })
const analysis = useOperationsAnalysis({
  ...(props.analysisPollIntervalMs != null ? { pollIntervalMs: props.analysisPollIntervalMs } : {}),
})
const clarificationSelections = ref<string[]>([])
let requestGeneration = 0

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function textValue(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function formatNumber(value: number | null | undefined, digits = 1): string {
  return value == null || !Number.isFinite(value)
    ? '—'
    : value.toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

function formatTime(value: string | null | undefined, timezone = props.context?.anomalyWindow.timezone ?? 'Asia/Shanghai'): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function formatWindow(from: string, to: string, timezone: string): string {
  return `${formatTime(from, timezone)} 至 ${formatTime(to, timezone)}`
}

function sameInstant(left: string, right: string): boolean {
  const leftTime = Date.parse(left)
  const rightTime = Date.parse(right)
  return Number.isFinite(leftTime) && Number.isFinite(rightTime) && leftTime === rightTime
}

function matchesEnergyContext(
  response: EnergyTimeSeriesResponse,
  context: CustomerAnalysisContext,
  metric: string,
): boolean {
  return response.metric === metric
    && response.source.system === context.source
    && response.source.metricDefinition === metric
    && response.source.status === response.status
    && sameInstant(response.window.from, context.energyWindow.from)
    && sameInstant(response.window.to, context.energyWindow.to)
    && response.window.granularity === context.energyWindow.granularity
    && response.timezone === context.energyWindow.timezone
    && (response.status === 'UNAVAILABLE'
      || response.series.every((item) => item.buildingId === context.buildingId))
}

async function refresh(): Promise<void> {
  const context = props.context
  if (!props.active || !context) return
  const generation = ++requestGeneration
  loading.value = { actual: true, baseline: true, evidence: true }
  actual.value = null
  baseline.value = null
  evidence.value = null
  errors.value = { actual: '', baseline: '', evidence: '' }

  const filters: EnergyTimeSeriesFilters = {
    buildingIds: [context.buildingId],
    from: context.energyWindow.from,
    to: context.energyWindow.to,
    granularity: context.energyWindow.granularity,
  }
  const isCurrent = () => generation === requestGeneration && props.context?.buildingId === context.buildingId
  const actualRequest = getEnergyTimeSeries('VIEWER', { ...filters, metric: 'energy_kwh' })
    .then((response) => {
      if (!isCurrent()) return
      if (matchesEnergyContext(response, context, 'energy_kwh')) {
        actual.value = response
        if (response.status === 'UNAVAILABLE') errors.value.actual = '该楼宇实际用电暂不可用。'
      } else {
        errors.value.actual = '实际用电返回范围与当前选择不一致。'
      }
    })
    .catch(() => {
      if (isCurrent()) errors.value.actual = '该楼宇实际用电读取失败。'
    })
    .finally(() => {
      if (isCurrent()) loading.value.actual = false
    })
  const baselineRequest = getEnergyTimeSeries('VIEWER', { ...filters, metric: 'energy_baseline_kwh' })
    .then((response) => {
      if (!isCurrent()) return
      if (matchesEnergyContext(response, context, 'energy_baseline_kwh')) {
        baseline.value = response
        if (response.status === 'UNAVAILABLE') errors.value.baseline = '该楼宇基线用电暂不可用。'
      } else {
        errors.value.baseline = '基线用电返回范围与当前选择不一致。'
      }
    })
    .catch(() => {
      if (isCurrent()) errors.value.baseline = '该楼宇基线用电读取失败。'
    })
    .finally(() => {
      if (isCurrent()) loading.value.baseline = false
    })
  const evidenceRequest = getAnomalyEvidence('VIEWER', context.buildingId, {
    from: context.anomalyWindow.from,
    to: context.anomalyWindow.to,
  })
    .then((response) => {
      if (!isCurrent()) return
      if (response.buildingId === context.buildingId
        && sameInstant(response.window.from, context.anomalyWindow.from)
        && sameInstant(response.window.to, context.anomalyWindow.to)
        && response.window.timezone === context.anomalyWindow.timezone) {
        evidence.value = response
      } else {
        errors.value.evidence = '告警与设备依据返回范围与当前选择不一致。'
      }
    })
    .catch(() => {
      if (isCurrent()) errors.value.evidence = '该楼宇的告警与设备依据读取失败。'
    })
    .finally(() => {
      if (isCurrent()) loading.value.evidence = false
    })
  await Promise.allSettled([actualRequest, baselineRequest, evidenceRequest])
}

const chartPoints = computed<EnergyAnalysisPoint[]>(() => {
  const actualSeries = actual.value?.series.find((item) => item.buildingId === props.context?.buildingId)
  const baselineSeries = baseline.value?.series.find((item) => item.buildingId === props.context?.buildingId)
  const timestamps = new Set<string>()
  actualSeries?.points.forEach((point) => timestamps.add(point.timestamp))
  actualSeries?.missingTimestamps.forEach((timestamp) => timestamps.add(timestamp))
  baselineSeries?.points.forEach((point) => timestamps.add(point.timestamp))
  baselineSeries?.missingTimestamps.forEach((timestamp) => timestamps.add(timestamp))
  const actualByTime = new Map(actualSeries?.points.map((point) => [point.timestamp, point.value]) ?? [])
  const baselineByTime = new Map(baselineSeries?.points.map((point) => [point.timestamp, point.value]) ?? [])
  return [...timestamps]
    .sort((left, right) => Date.parse(left) - Date.parse(right))
    .map((timestamp) => ({
      timestamp,
      actual: actualByTime.get(timestamp) ?? null,
      baseline: baselineByTime.get(timestamp) ?? null,
    }))
})

const actualSeries = computed(() => actual.value?.series.find((item) => item.buildingId === props.context?.buildingId) ?? null)
const baselineSeries = computed(() => baseline.value?.series.find((item) => item.buildingId === props.context?.buildingId) ?? null)
const actualTotal = computed(() => actual.value?.status === 'UNAVAILABLE'
  ? null
  : actualSeries.value?.points.reduce((sum, point) => sum + point.value, 0) ?? null)
const baselineTotal = computed(() => baseline.value?.status === 'UNAVAILABLE'
  ? null
  : baselineSeries.value?.points.reduce((sum, point) => sum + point.value, 0) ?? null)
const hasUnmatchedBuckets = computed(() => chartPoints.value.some((point) => point.actual == null || point.baseline == null))
const energyWindowDeviation = computed(() => {
  if (actual.value?.status !== 'AVAILABLE' || baseline.value?.status !== 'AVAILABLE'
    || hasUnmatchedBuckets.value || baselineTotal.value == null || baselineTotal.value === 0 || actualTotal.value == null) return null
  return (actualTotal.value - baselineTotal.value) * 100 / baselineTotal.value
})
const incompleteEnergy = computed(() => actual.value?.status === 'PARTIAL' || baseline.value?.status === 'PARTIAL')
const energyLoading = computed(() => loading.value.actual || loading.value.baseline)
const energyRetryLoading = computed(() => (Boolean(errors.value.actual) && loading.value.actual)
  || (Boolean(errors.value.baseline) && loading.value.baseline))
const evidenceLoading = computed(() => loading.value.evidence)
const chartStatus = computed(() => {
  if (energyLoading.value) return '正在读取单楼宇小时数据…'
  if (errors.value.actual && errors.value.baseline) return `${errors.value.actual} ${errors.value.baseline} 未绘制曲线。`
  if (errors.value.actual) return `${errors.value.actual} 仅展示可用基线，不形成偏差结论。`
  if (errors.value.baseline) return `${errors.value.baseline} 仅展示实际用电，不绘制正常范围。`
  if (incompleteEnergy.value || hasUnmatchedBuckets.value) return '部分小时缺失，曲线保留断点，窗口偏差不作完整结论。'
  return '实际用电与基线来自同一楼宇、同一小时窗口。'
})

function overviewStatus(domain: AnomalyDomain): AnomalyDomainStatus {
  return props.context?.overviewDomainStatus[domain] ?? 'UNAVAILABLE'
}

function evidenceStatus(domain: AnomalyDomain): AnomalyDomainStatus {
  return evidence.value?.domainStatus[domain] ?? 'UNAVAILABLE'
}

function hasEvidenceRows(domain: AnomalyDomain): boolean {
  return evidenceStatus(domain) !== 'UNAVAILABLE'
}

function overviewCountText(domain: 'alerts' | 'devices', value: number, unit: string): string {
  const status = overviewStatus(domain)
  if (status === 'UNAVAILABLE') return '未取得'
  if (status === 'PARTIAL') return value > 0 ? `至少 ${formatNumber(value, 0)} ${unit}` : '总数未知'
  return `${formatNumber(value, 0)} ${unit}`
}

const overviewDeviationText = computed(() => {
  const deviation = props.context?.summary?.energyDeviationPct
  const status = overviewStatus('energy')
  if (status === 'UNAVAILABLE') return '未取得'
  if (deviation == null) return status === 'PARTIAL' ? '偏差未知' : '—'
  const value = `${formatNumber(Math.abs(deviation))}%`
  return status === 'PARTIAL' ? `已观测 ${value}` : value
})

const overviewAlertCountText = computed(() => {
  const count = props.context?.summary?.alertCount
  return count == null ? '未取得' : overviewCountText('alerts', count, '条')
})

const observation = computed(() => {
  const context = props.context
  if (!context) return '请先从园区总览选择一个楼宇。'
  const facts: string[] = []
  const deviation = context.summary?.energyDeviationPct
  if (overviewStatus('energy') === 'UNAVAILABLE') {
    facts.push(`${context.buildingName}的能耗总览数据未取得`)
  } else if (deviation != null) {
    const scope = overviewStatus('energy') === 'PARTIAL' ? '已观测的部分能耗' : '能耗'
    facts.push(`${context.buildingName}在异常总览窗口内${scope}较基线${deviation >= 0 ? '高' : '低'} ${formatNumber(Math.abs(deviation))}%`)
  } else {
    facts.push(`${context.buildingName}当前未取得可展示的总览基线偏差`)
  }
  if (context.summary) {
    facts.push(`同期未处理告警 ${overviewCountText('alerts', context.summary.alertCount, '条')}`)
    facts.push(`同期离线设备 ${overviewCountText('devices', context.summary.offlineDeviceCount, '台')}`)
  }
  return `${facts.join('；')}。这是基于受治理事实的运营观察，不是模型生成的原因判断。`
})

const analysisSummary = computed(() => analysis.dto.value?.status === 'COMPLETED'
  ? analysis.dto.value.summary?.trim() || '本次模型分析已完成，但未返回可展示的结论摘要。'
  : null)
const safeAnalysisError = computed(() => analysis.error.value
  ? '模型或分析接口暂不可用，请稍后重试'
  : '本次分析未完成')

const causes = computed(() => {
  const rows: string[] = []
  const summary = props.context?.summary
  if (overviewStatus('energy') !== 'UNAVAILABLE' && summary?.energyDeviationPct != null && summary.energyDeviationPct > 0) {
    rows.push('用电高于基线的时段与现场运行计划是否一致，仍需人工核查。')
  }
  if (overviewStatus('devices') !== 'UNAVAILABLE' && (summary?.offlineDeviceCount ?? 0) > 0) {
    rows.push('离线设备与能耗变化是否相关尚未确认，应先核对设备状态和采集完整性。')
  }
  const categories = [...new Set((hasEvidenceRows('alerts') ? evidence.value?.alerts ?? [] : [])
    .map((item) => textValue(item.category)).filter((item): item is string => item != null))]
  if (categories.length) rows.push(`窗口内存在 ${categories.join('、')} 类告警；它们是关联线索，不等同于已确认故障原因。`)
  if (!rows.length) rows.push('现有事实不足以确认具体原因，暂不推断设备故障或运行违规。')
  return rows
})

const suggestions = computed(() => props.context ? customerRecommendations(props.context) : [])

interface RelatedDeviceRow {
  id: string
  kind: string
  status: string
  observedAt: string | null
  reading: string
}

const relatedDevices = computed<RelatedDeviceRow[]>(() => {
  const rows = new Map<string, RelatedDeviceRow>()
  for (const item of hasEvidenceRows('devices') ? evidence.value?.devices ?? [] : []) {
    const id = textValue(item.deviceId)
    if (!id) continue
    rows.set(id, {
      id,
      kind: textValue(item.deviceType) ?? '设备类型未知',
      status: textValue(item.status) === 'OFFLINE' ? '离线' : '状态未知',
      observedAt: textValue(item.snapshotAt),
      reading: '未提供设备级用电',
    })
  }
  for (const item of hasEvidenceRows('alerts') ? evidence.value?.alerts ?? [] : []) {
    const id = textValue(item.deviceId)
    if (!id || rows.has(id)) continue
    rows.set(id, {
      id,
      kind: '告警关联设备',
      status: '设备状态未提供',
      observedAt: textValue(item.occurredAt),
      reading: `关联${textValue(item.category) ?? '类别未知'}告警`,
    })
  }
  for (const item of hasEvidenceRows('energy') ? evidence.value?.energy ?? [] : []) {
    const id = textValue(item.meterId)
    if (!id) continue
    const kwh = finiteNumber(item.kwh)
    const observedAt = textValue(item.measuredAt)
    const existing = rows.get(id)
    if (existing?.kind === '能耗计量点') {
      const existingTime = Date.parse(existing.observedAt ?? '')
      const candidateTime = Date.parse(observedAt ?? '')
      if (!Number.isFinite(candidateTime)
        || (Number.isFinite(existingTime) && existingTime >= candidateTime)) continue
    }
    rows.set(id, {
      id,
      kind: '能耗计量点',
      status: '已取得观测',
      observedAt,
      reading: kwh == null ? '读数缺失' : `${formatNumber(kwh, 2)} kWh`,
    })
  }
  return [...rows.values()].sort((left, right) => left.id.localeCompare(right.id))
})

const relatedRecords = computed(() => [
  ...(hasEvidenceRows('alerts') ? evidence.value?.alerts ?? [] : []).map((item) => ({
    id: textValue(item.alertId) ?? '告警标识缺失',
    label: `${textValue(item.category) ?? '类别未知'}告警 · ${textValue(item.status) === 'OPEN' ? '未处理' : '状态未知'}`,
    at: textValue(item.occurredAt),
  })),
  ...(hasEvidenceRows('energy') ? evidence.value?.energy ?? [] : []).map((item) => ({
    id: textValue(item.meterId) ?? '计量点标识缺失',
    label: finiteNumber(item.deviationPct) == null ? '能耗观测' : `能耗观测 · 基线偏差 ${formatNumber(finiteNumber(item.deviationPct))}%`,
    at: textValue(item.measuredAt),
  })),
].sort((left, right) => Date.parse(right.at ?? '') - Date.parse(left.at ?? '')).slice(0, 8))

const evidenceNotices = computed(() => {
  if (!evidence.value) return []
  const labels: Record<AnomalyDomain, string> = { alerts: '告警', devices: '设备', energy: '能耗' }
  return (['alerts', 'devices', 'energy'] as const).flatMap((domain) => {
    const status = evidenceStatus(domain)
    if (status === 'UNAVAILABLE') return [`${labels[domain]}依据暂不可用，当前不能确认该域是否没有相关记录。`]
    if (status === 'PARTIAL') return [`${labels[domain]}依据仅部分可用，已展示取得的记录，列表可能不完整。`]
    return []
  })
})

const relatedDevicesEmptyText = computed(() => {
  if (errors.value.evidence) return errors.value.evidence
  if (!evidence.value) return '当前未取得合法关联的设备或计量点'
  if ((['alerts', 'devices', 'energy'] as const).some((domain) => evidenceStatus(domain) !== 'OK')) {
    return '关联设备依据未完整取得，暂不能确认是否无相关设备或计量点'
  }
  return '当前窗口暂无相关设备或计量点'
})

const relatedRecordsEmptyText = computed(() => {
  if (errors.value.evidence) return errors.value.evidence
  if (!evidence.value) return '当前未取得相关记录'
  if ((['alerts', 'energy'] as const).some((domain) => evidenceStatus(domain) !== 'OK')) {
    return '关联记录依据未完整取得，暂不能确认当前窗口无相关记录'
  }
  return '当前窗口暂无相关记录'
})

function scrollToSection(id: string): void {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function runAiAnalysis(): void {
  const context = props.context
  if (!context || analysis.phase.value === 'running' || analysis.phase.value === 'clarification') return
  const question = `building_id=${context.buildingId} 从 ${context.energyWindow.from} 到 ${context.energyWindow.to} 的能耗基线偏差率`
  void analysis.submit(question)
}

function clarificationOptions(index: number): string[] {
  return analysis.dto.value?.clarificationOptions?.[index] ?? ['energy_deviation_pct']
}

function metricLabel(metric: string): string {
  const labels: Record<string, string> = {
    energy_kwh: '实际用电量',
    energy_baseline_kwh: '基线用电量',
    energy_deviation_pct: '能耗基线偏差率',
  }
  return labels[metric] ?? '后端提供的指标口径'
}

function submitClarification(): void {
  const questions = analysis.dto.value?.clarificationQuestions ?? []
  analysis.selections.value = questions.map((question, index) => ({
    term: question,
    metric: clarificationSelections.value[index] ?? clarificationOptions(index)[0]!,
  }))
  void analysis.clarify()
}

const refreshContextKey = computed(() => JSON.stringify(props.context))
const analysisQueryContextKey = computed(() => {
  const context = props.context
  return context ? JSON.stringify({
    buildingId: context.buildingId,
    source: context.source,
    energyWindow: context.energyWindow,
  }) : ''
})
let lastAnalysisQueryContextKey = ''

watch(() => analysis.dto.value?.clarificationQuestions, (questions) => {
  clarificationSelections.value = (questions ?? []).map((_, index) => clarificationOptions(index)[0]!)
})

watch(analysisQueryContextKey, (contextKey) => {
  if (!contextKey) return
  if (lastAnalysisQueryContextKey && lastAnalysisQueryContextKey !== contextKey) analysis.reset()
  lastAnalysisQueryContextKey = contextKey
}, { immediate: true })

watch(
  [() => props.active, () => refreshContextKey.value],
  ([active]) => {
    if (active) void refresh()
    else {
      requestGeneration++
      loading.value = { actual: false, baseline: false, evidence: false }
    }
  },
  { immediate: true },
)
</script>

<template>
  <main id="customer-analysis-main" class="energy-analysis" data-customer-page="analysis">
    <section v-if="!context" class="customer-card energy-analysis__empty" role="status">
      <DataAnalysis aria-hidden="true" />
      <h1>请先选择需要分析的楼宇</h1>
      <p>从园区总览的重点关注或楼宇地图进入，页面才会继承对应对象与两类查询周期。</p>
      <button type="button" @click="$emit('back')"><ArrowLeft aria-hidden="true" /> 返回园区总览</button>
    </section>

    <template v-else>
      <nav class="energy-analysis__tabs" aria-label="分析页内容导航">
        <button type="button" class="is-current" @click="scrollToSection('analysis-conclusion')">综合分析</button>
        <button type="button" @click="scrollToSection('related-devices')">设备详情</button>
        <button type="button" @click="scrollToSection('analysis-actions')">处理建议</button>
        <button type="button" @click="scrollToSection('related-records')">相关记录</button>
      </nav>

      <section id="analysis-conclusion" class="customer-card energy-analysis__conclusion">
        <header>
          <div><span class="energy-analysis__ai">AI</span><h2>{{ analysisSummary ? 'AI 分析结论' : '运营观察' }}</h2></div>
          <button
            type="button"
            data-run-ai-analysis
            :disabled="analysis.phase.value === 'running' || analysis.phase.value === 'clarification'"
            @click="runAiAnalysis"
          >
            {{ analysis.phase.value === 'running' ? '分析中…' : analysis.phase.value === 'clarification' ? '等待口径确认' : analysisSummary ? '重新分析' : '运行 AI 分析' }}
          </button>
        </header>
        <p class="energy-analysis__lead">{{ analysisSummary ?? observation }}</p>
        <p v-if="analysis.phase.value === 'idle'" class="energy-analysis__boundary">当前先展示规则化运营观察；只有点击按钮后才会调用现有受控分析链路。</p>
        <p v-else-if="analysis.phase.value === 'running'" class="energy-analysis__boundary" role="status">正在执行受控只读分析，页面不会展示技术轨迹。</p>
        <p v-else-if="analysis.phase.value === 'failed'" class="energy-analysis__error" role="alert">AI 分析未完成：{{ safeAnalysisError }}。已有业务事实仍可继续查看。</p>
        <section v-if="analysis.phase.value === 'clarification'" class="energy-analysis__clarification">
          <strong>需要确认指标口径</strong>
          <p v-if="analysis.error.value" class="energy-analysis__error" role="alert">口径提交未完成，请保留当前选择并重试。</p>
          <label v-for="(question, index) in analysis.dto.value?.clarificationQuestions ?? []" :key="question">
            <span>{{ question }}</span>
            <select v-model="clarificationSelections[index]">
              <option v-for="metric in clarificationOptions(index)" :key="metric" :value="metric">{{ metricLabel(metric) }}</option>
            </select>
          </label>
          <button type="button" @click="submitClarification">按所选口径继续</button>
        </section>
        <div class="energy-analysis__summary-grid">
          <div data-overview-energy-status><WarningFilled aria-hidden="true" /><span><strong>{{ overviewDeviationText }}</strong><small>异常总览窗口基线偏差</small></span></div>
          <div data-overview-alert-status><Calendar aria-hidden="true" /><span><strong>{{ overviewAlertCountText }}</strong><small>异常总览窗口未处理告警</small></span></div>
          <div><DataAnalysis aria-hidden="true" /><span><strong>{{ formatNumber(actualTotal) }} {{ actual?.unit ?? 'kWh' }}</strong><small>单楼宇能耗窗口已观测值</small></span></div>
        </div>
      </section>

      <section class="customer-card energy-analysis__trend" aria-labelledby="energy-trend-title">
        <header>
          <div><h2 id="energy-trend-title">实际用电与基线对比</h2><p>能耗周期：{{ formatWindow(context.energyWindow.from, context.energyWindow.to, context.energyWindow.timezone) }} · 小时粒度</p></div>
          <span v-if="energyWindowDeviation != null">完整窗口偏差 {{ energyWindowDeviation >= 0 ? '+' : '' }}{{ formatNumber(energyWindowDeviation) }}%</span>
        </header>
        <div class="energy-analysis__notice" :class="{ 'is-error': errors.actual || errors.baseline }">
          <span>{{ chartStatus }}</span>
          <button v-if="errors.actual || errors.baseline" type="button" data-retry-energy :disabled="energyRetryLoading" @click="refresh"><Refresh aria-hidden="true" /> 重试</button>
        </div>
        <EnergyAnalysisChart v-if="chartPoints.length" :points="chartPoints" :timezone="context.energyWindow.timezone" :unit="actual?.unit ?? baseline?.unit ?? 'kWh'" />
        <p v-else class="customer-state">{{ energyLoading ? '正在读取单楼宇小时数据…' : '当前能耗窗口没有可绘制数据' }}</p>
        <details class="energy-analysis__basis" data-analysis-basis>
          <summary>查看数据依据与缺失说明</summary>
          <dl>
            <div><dt>异常依据周期</dt><dd>{{ formatWindow(context.anomalyWindow.from, context.anomalyWindow.to, context.anomalyWindow.timezone) }}</dd></div>
            <div><dt>能耗图周期</dt><dd>{{ formatWindow(context.energyWindow.from, context.energyWindow.to, context.energyWindow.timezone) }}</dd></div>
            <div><dt>指标与单位</dt><dd>实际用电、基线用电 · {{ actual?.unit ?? baseline?.unit ?? 'kWh' }}</dd></div>
            <div><dt>来源</dt><dd>{{ actual?.source.system ?? baseline?.source.system ?? context.source }} · 受治理小时事实</dd></div>
            <div><dt>数据完整性</dt><dd>{{ chartStatus }}</dd></div>
          </dl>
        </details>
      </section>

      <section class="energy-analysis__two-column">
        <article class="customer-card energy-analysis__list">
          <header><span class="is-amber">?</span><h2>可能原因</h2><small>线索，不是故障定论</small></header>
          <ol><li v-for="cause in causes" :key="cause">{{ cause }}</li></ol>
        </article>
        <article id="analysis-actions" class="customer-card energy-analysis__list">
          <header><span class="is-green"><Checked aria-hidden="true" /></span><h2>建议处理措施</h2><small>需人工确认</small></header>
          <ol><li v-for="suggestion in suggestions" :key="suggestion">{{ suggestion }}</li></ol>
          <button type="button" class="energy-analysis__work-order-entry" data-open-work-orders @click="emit('open-work-orders', context)">
            查看同一事件并人工确认
          </button>
        </article>
      </section>

      <section id="related-devices" class="customer-card energy-analysis__table-card">
        <header><div><h2>相关设备与计量点</h2><p>{{ context.buildingId }} · {{ context.buildingName }} · 合法关联来自告警、设备快照与能耗证据</p></div></header>
        <div v-if="errors.evidence" class="energy-analysis__inline-error">{{ errors.evidence }} <button type="button" :disabled="evidenceLoading" @click="refresh"><Refresh aria-hidden="true" /> 重试</button></div>
        <ul v-if="evidenceNotices.length" class="energy-analysis__availability" data-evidence-availability role="status">
          <li v-for="notice in evidenceNotices" :key="notice">{{ notice }}</li>
        </ul>
        <div class="energy-analysis__table-wrap">
          <table v-if="relatedDevices.length">
            <thead><tr><th>设备/计量点</th><th>类型</th><th>位置</th><th>状态</th><th>可用读数/关联</th><th>观测时间</th></tr></thead>
            <tbody><tr v-for="item in relatedDevices" :key="item.id"><td>{{ item.id }}</td><td>{{ item.kind }}</td><td>{{ context.buildingName }}</td><td>{{ item.status }}</td><td>{{ item.reading }}</td><td>{{ formatTime(item.observedAt) }}</td></tr></tbody>
          </table>
          <p v-else class="customer-state">{{ evidenceLoading ? '正在读取相关设备…' : relatedDevicesEmptyText }}</p>
        </div>
      </section>

      <section id="related-records" class="customer-card energy-analysis__records">
        <header><div><h2>相关记录</h2><p>仅展示客户可读的业务依据，不展示内部技术执行细节。</p></div></header>
        <div v-for="record in relatedRecords" :key="`${record.id}:${record.at}`"><strong>{{ record.id }}</strong><span>{{ record.label }}</span><time>{{ formatTime(record.at) }}</time></div>
        <p v-if="!evidenceLoading && !relatedRecords.length" class="customer-state is-compact">{{ relatedRecordsEmptyText }}</p>
      </section>
    </template>
  </main>
</template>

<style scoped src="./energy-analysis.css"></style>
