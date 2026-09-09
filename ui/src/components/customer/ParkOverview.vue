<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BellFilled, Checked, DataLine, Document, OfficeBuilding, Refresh, Service, TrendCharts, WarningFilled } from '@element-plus/icons-vue'
import parkAerial from '../../assets/customer/park-aerial-daylight.png'
import ecoOperations from '../../assets/customer/eco-operations.png'
import { getAnomalyEvidence, getAnomalyOverview } from '../../services/operationsAnomalyApi'
import { getEnergyTimeSeries } from '../../services/energyTimeSeriesApi'
import { getOperationsMetrics, listCollaborationWorkItems } from '../../services/workflowApi'
import type { CollaborationWorkItem } from '../../types/collaborationCenter'
import type { EnergyTimeSeriesResponse } from '../../types/energyTimeSeries'
import type { AnomalyBuildingSummary, AnomalyEvidence, AnomalyOverview } from '../../types/operationsAnomaly'
import type { OperationsMetrics } from '../../types/workflow'
import CustomerOverviewChart, { type CustomerChartDatum } from './CustomerOverviewChart.vue'

const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })

const overview = ref<AnomalyOverview | null>(null)
const energy = ref<EnergyTimeSeriesResponse | null>(null)
const metrics = ref<OperationsMetrics | null>(null)
const workItems = ref<CollaborationWorkItem[]>([])
const evidence = ref<AnomalyEvidence | null>(null)
const selectedBuildingId = ref<string | null>(null)
const loading = ref(false)
const energyLoading = ref(false)
const metricsLoading = ref(false)
const workItemsLoading = ref(false)
const detailLoading = ref(false)
const errors = ref({ overview: '', energy: '', metrics: '', workItems: '', evidence: '' })
let requestGeneration = 0
let evidenceGeneration = 0

const terminalWorkItemStatuses = new Set<CollaborationWorkItem['status']>([
  'COMPLETED',
  'REJECTED',
  'FAILED',
  'WORK_ORDER_FAILED',
  'RESOLVED',
  'CLOSED',
  'CANCELLED',
])

const buildingCatalog: Record<string, { name: string; position: { left: string; top: string } }> = {
  B1: { name: '创新中心', position: { left: '30%', top: '65%' } },
  B2: { name: '研发大厦', position: { left: '58%', top: '46%' } },
  B3: { name: '运营中心', position: { left: '76%', top: '24%' } },
}

function buildingName(id: string): string {
  return buildingCatalog[id]?.name ?? id
}

function locationLabel(item: CollaborationWorkItem): string {
  return item.buildingId ? buildingName(item.buildingId) : '园区事项'
}

function domainUsable(name: 'alerts' | 'devices' | 'energy'): boolean {
  return overview.value?.domainStatus[name] === 'OK' || overview.value?.domainStatus[name] === 'PARTIAL'
}

function formatNumber(value: number | null | undefined, maximumFractionDigits = 1): string {
  return value == null || !Number.isFinite(value) ? '—' : value.toLocaleString('zh-CN', { maximumFractionDigits })
}

function formatTime(value: string | null | undefined, options: Intl.DateTimeFormatOptions = {}): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, ...options }).format(date)
}

function last24Hours(window: AnomalyOverview['window']): { from: string; to: string } | null {
  const to = Date.parse(window.to)
  const from = Date.parse(window.from)
  if (!Number.isFinite(to) || !Number.isFinite(from)) return null
  const hour = 60 * 60 * 1000
  const alignedTo = Math.floor(to / hour) * hour
  const alignedFrom = Math.max(Math.ceil(from / hour) * hour, alignedTo - 24 * hour)
  if (alignedFrom >= alignedTo) return null
  return { from: new Date(alignedFrom).toISOString(), to: new Date(alignedTo).toISOString() }
}

async function loadEvidence(buildingId: string, currentGeneration = requestGeneration): Promise<void> {
  const generation = ++evidenceGeneration
  evidence.value = null
  errors.value.evidence = ''
  detailLoading.value = true
  try {
    const window = overview.value?.window
    const nextEvidence = await getAnomalyEvidence('VIEWER', buildingId, window ? { from: window.from, to: window.to } : {})
    if (generation !== evidenceGeneration || currentGeneration !== requestGeneration || selectedBuildingId.value !== buildingId) return
    evidence.value = nextEvidence
  } catch {
    if (generation !== evidenceGeneration || currentGeneration !== requestGeneration) return
    errors.value.evidence = '所选楼宇的最新事件暂不可用。'
  } finally {
    if (generation === evidenceGeneration) detailLoading.value = false
  }
}

async function selectBuilding(buildingId: string): Promise<void> {
  selectedBuildingId.value = buildingId
  await loadEvidence(buildingId)
}

async function loadMetrics(generation: number): Promise<void> {
  metricsLoading.value = true
  try {
    const nextMetrics = await getOperationsMetrics()
    if (generation !== requestGeneration) return
    metrics.value = nextMetrics
  } catch {
    if (generation !== requestGeneration) return
    errors.value.metrics = '服务请求统计暂不可用。'
  } finally {
    if (generation === requestGeneration) metricsLoading.value = false
  }
}

async function loadWorkItems(generation: number): Promise<void> {
  workItemsLoading.value = true
  try {
    const nextWorkItems = await listCollaborationWorkItems('CUSTOMER_AGENT', { limit: 50, sort: 'sla' })
    if (generation !== requestGeneration) return
    workItems.value = nextWorkItems
      .filter((item) => !terminalWorkItemStatuses.has(item.status))
      .slice(0, 4)
  } catch {
    if (generation !== requestGeneration) return
    errors.value.workItems = '我的待办暂不可用。'
  } finally {
    if (generation === requestGeneration) workItemsLoading.value = false
  }
}

async function refresh(): Promise<void> {
  if (!props.active) return
  const generation = ++requestGeneration
  evidenceGeneration++
  loading.value = true
  energyLoading.value = false
  metricsLoading.value = true
  workItemsLoading.value = true
  detailLoading.value = false
  energy.value = null
  metrics.value = null
  workItems.value = []
  evidence.value = null
  errors.value = { overview: '', energy: '', metrics: '', workItems: '', evidence: '' }
  void loadMetrics(generation)
  void loadWorkItems(generation)

  try {
    const nextOverview = await getAnomalyOverview('VIEWER', { status: 'OPEN' })
    if (generation !== requestGeneration) return
    overview.value = nextOverview
    const ids = Object.keys(buildingCatalog)
    const affectedIds = overview.value.buildings.map((building) => building.buildingId)
    selectedBuildingId.value = selectedBuildingId.value && affectedIds.includes(selectedBuildingId.value)
      ? selectedBuildingId.value
      : affectedIds[0] ?? null
    if (ids.length > 0) {
      const window = last24Hours(overview.value.window)
      if (window) {
        energyLoading.value = true
        try {
          const nextEnergy = await getEnergyTimeSeries('VIEWER', {
            buildingIds: ids,
            from: window.from,
            to: window.to,
            granularity: 'HOUR',
          })
          if (generation === requestGeneration) energy.value = nextEnergy
        } catch {
          if (generation === requestGeneration) {
            energy.value = null
            errors.value.energy = '最近 24 小时能耗趋势暂不可用。'
          }
        } finally {
          if (generation === requestGeneration) energyLoading.value = false
        }
      } else {
        energy.value = null
        errors.value.energy = '总览窗口不足以形成小时级能耗趋势。'
      }
      if (generation !== requestGeneration) return
      if (selectedBuildingId.value) void loadEvidence(selectedBuildingId.value, generation)
    } else {
      energy.value = null
      evidence.value = null
    }
  } catch {
    if (generation !== requestGeneration) return
    overview.value = null
    energy.value = null
    selectedBuildingId.value = null
    evidence.value = null
    errors.value.overview = '园区运营总览暂不可用。'
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

const energyTotal = computed(() => energy.value?.status === 'UNAVAILABLE'
  ? null
  : energy.value?.series.flatMap((series) => series.points).reduce((sum, point) => sum + point.value, 0) ?? null)
const openAlertCount = computed(() => domainUsable('alerts')
  ? overview.value?.breakdowns.statuses?.find((item) => item.key === 'OPEN')?.count ?? 0
  : null)
const selectedBuilding = computed(() => overview.value?.buildings.find((building) => building.buildingId === selectedBuildingId.value) ?? null)
const attentionItems = computed(() => [...(overview.value?.buildings ?? [])]
  .sort((left, right) => right.highRiskAlertCount - left.highRiskAlertCount
    || right.offlineDeviceCount - left.offlineDeviceCount
    || Math.abs(right.energyDeviationPct ?? 0) - Math.abs(left.energyDeviationPct ?? 0))
  .slice(0, 4))
const visibleBuildings = computed(() => {
  const rows = new Map((overview.value?.buildings ?? []).map((building) => [building.buildingId, building]))
  return Object.keys(buildingCatalog).map((id) => {
    const definition = buildingCatalog[id]!
    return { id, summary: rows.get(id) ?? null, ...definition }
  })
})
const energyTrend = computed<CustomerChartDatum[]>(() => {
  if (!energy.value?.series.length) return []
  const timelines = new Map<string, Array<number | null>>()
  energy.value.series.forEach((series, seriesIndex) => {
    series.points.forEach((point) => {
      const values = timelines.get(point.timestamp) ?? Array<number | null>(energy.value!.series.length).fill(null)
      values[seriesIndex] = point.value
      timelines.set(point.timestamp, values)
    })
    series.missingTimestamps.forEach((timestamp) => {
      const values = timelines.get(timestamp) ?? Array<number | null>(energy.value!.series.length).fill(null)
      timelines.set(timestamp, values)
    })
  })
  return [...timelines.entries()]
    .sort(([left], [right]) => Date.parse(left) - Date.parse(right))
    .map(([timestamp, values]) => ({
      name: formatTime(timestamp, { hour: '2-digit', minute: '2-digit' }),
      value: values.every((value) => value != null) ? values.reduce((sum, value) => sum + (value ?? 0), 0) : null,
    }))
})
const energyDistribution = computed<CustomerChartDatum[]>(() => energy.value?.series.map((series) => ({
  name: buildingName(series.buildingId),
  value: series.points.reduce((sum, point) => sum + point.value, 0),
})) ?? [])
const eventDistribution = computed<CustomerChartDatum[]>(() => domainUsable('alerts')
  ? (overview.value?.breakdowns.categories ?? []).map((item) => ({ name: item.key, value: item.count }))
  : [])
const latestEvents = computed(() => [
  ...(evidence.value?.alerts ?? []),
  ...(evidence.value?.devices ?? []),
  ...(evidence.value?.energy ?? []),
]
  .sort((left, right) => recordTimestamp(right) - recordTimestamp(left))
  .slice(0, 5))

function attentionTitle(building: AnomalyBuildingSummary): string {
  if (domainUsable('alerts') && building.highRiskAlertCount > 0) return `${buildingName(building.buildingId)}存在高风险告警`
  if (domainUsable('devices') && building.offlineDeviceCount > 0) return `${buildingName(building.buildingId)}有离线设备`
  if (domainUsable('energy') && building.energyDeviationPct != null && Math.abs(building.energyDeviationPct) > 0) return `${buildingName(building.buildingId)}能耗偏离基线`
  if (domainUsable('alerts') && building.alertCount > 0) return `${buildingName(building.buildingId)}存在待关注告警`
  return `${buildingName(building.buildingId)}需要运营关注`
}

function attentionDescription(building: AnomalyBuildingSummary): string {
  const facts: string[] = []
  if (domainUsable('alerts')) facts.push(`告警 ${building.alertCount} 条`)
  if (domainUsable('devices')) facts.push(`离线设备 ${building.offlineDeviceCount} 台`)
  if (domainUsable('energy') && building.energyDeviationPct != null) facts.push(`基线偏差 ${formatNumber(building.energyDeviationPct)}%`)
  return facts.join(' · ') || '当前数据域不足，无法形成进一步判断'
}

function markerState(summary: AnomalyBuildingSummary | null): 'unknown' | 'warning' | 'normal' {
  if (!summary) return 'unknown'
  const hasAlert = domainUsable('alerts') && summary.alertCount > 0
  const hasOfflineDevice = domainUsable('devices') && summary.offlineDeviceCount > 0
  const hasEnergyDeviation = domainUsable('energy')
    && summary.energyDeviationPct != null
    && Math.abs(summary.energyDeviationPct) > 0
  return hasAlert || hasOfflineDevice || hasEnergyDeviation ? 'warning' : 'normal'
}

function attentionSignal(building: AnomalyBuildingSummary): { label: string; className: string } {
  if (domainUsable('alerts') && building.highRiskAlertCount > 0) return { label: '高', className: 'is-high' }
  if (domainUsable('devices') && building.offlineDeviceCount > 0) return { label: '离线', className: 'is-medium' }
  if (domainUsable('energy') && building.energyDeviationPct != null && Math.abs(building.energyDeviationPct) > 0) {
    return { label: '偏差', className: 'is-medium' }
  }
  if (domainUsable('alerts') && building.alertCount > 0) return { label: '告警', className: 'is-medium' }
  return { label: '关注', className: 'is-low' }
}

function recordText(record: Record<string, unknown>): string {
  if (typeof record.redactedSummary === 'string') return record.redactedSummary
  if (typeof record.alertId === 'string') return `告警 ${String(record.category ?? '类别未知')} · ${String(record.status ?? '状态未知')}`
  if (typeof record.deviceId === 'string') return `${String(record.deviceType ?? '设备')} · ${String(record.status ?? '状态未知')}`
  if (typeof record.meterId === 'string') {
    return typeof record.deviationPct === 'number'
      ? `能耗观测 · 基线偏差 ${formatNumber(record.deviationPct)}%`
      : '已取得能耗观测'
  }
  return '已取得一条安全摘要'
}

function recordTimestamp(record: Record<string, unknown>): number {
  const value = record.occurredAt ?? record.snapshotAt ?? record.measuredAt
  if (typeof value !== 'string') return 0
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : 0
}

function recordTime(record: Record<string, unknown>): string {
  const value = record.occurredAt ?? record.snapshotAt ?? record.measuredAt
  return typeof value === 'string'
    ? formatTime(value, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    : '时间未知'
}

watch(() => props.active, (active) => {
  if (active) void refresh()
  else {
    requestGeneration++
    evidenceGeneration++
    loading.value = false
    energyLoading.value = false
    metricsLoading.value = false
    workItemsLoading.value = false
    detailLoading.value = false
  }
}, { immediate: true })
</script>

<template>
  <main id="customer-overview-main" class="park-overview" data-customer-page="overview">
    <section class="park-overview__kpis" aria-label="园区关键指标">
      <article class="customer-card customer-kpi" data-kpi="energy">
        <span class="customer-kpi__icon is-green"><TrendCharts aria-hidden="true" /></span>
        <div><p>最近 24 小时园区能耗</p><strong>{{ formatNumber(energyTotal) }} <small>{{ energy?.unit ?? 'kWh' }}</small></strong><span>{{ energyLoading ? '正在读取能耗观测…' : energy?.status === 'PARTIAL' ? '部分观测，缺口未补零' : errors.energy || '各楼宇小时观测汇总' }}</span></div>
      </article>
      <article class="customer-card customer-kpi" data-kpi="buildings">
        <span class="customer-kpi__icon is-blue"><OfficeBuilding aria-hidden="true" /></span>
        <div><p>受影响楼宇</p><strong>{{ formatNumber(overview?.summary.affectedBuildingCount, 0) }} <small>栋</small></strong><span>{{ overview && Object.values(overview.domainStatus).some((value) => value !== 'OK') ? '部分数据域可用' : errors.overview || '窗口内综合异常信号' }}</span></div>
      </article>
      <article class="customer-card customer-kpi" data-kpi="events">
        <span class="customer-kpi__icon is-coral"><BellFilled aria-hidden="true" /></span>
        <div><p>待处理事件</p><strong>{{ formatNumber(openAlertCount, 0) }} <small>件</small></strong><span>{{ domainUsable('alerts') ? '当前查询窗口 OPEN 状态' : '告警数据暂不可用' }}</span></div>
      </article>
      <article class="customer-card customer-kpi" data-kpi="service-requests">
        <span class="customer-kpi__icon is-violet"><Service aria-hidden="true" /></span>
        <div><p>人工服务请求</p><strong>{{ formatNumber(metrics?.humanTicketCount, 0) }} <small>件</small></strong><span>{{ metricsLoading ? '正在读取运营指标…' : errors.metrics || '当前运行实例累计' }}</span></div>
      </article>
      <article class="customer-eco-card" :style="{ backgroundImage: `url(${ecoOperations})` }">
        <strong>绿色低碳　智慧运营</strong>
        <span>共建更美好的产业社区</span>
      </article>
    </section>

    <div v-if="errors.overview" class="customer-alert" role="status">
      <WarningFilled aria-hidden="true" />
      <span>{{ errors.overview }} 页面不会回填预置成功数据。</span>
      <button type="button" :disabled="loading" @click="refresh"><Refresh aria-hidden="true" /> 重试</button>
    </div>

    <section class="park-overview__main-grid">
      <article class="customer-card customer-attention">
        <header><h2><span class="customer-heading-icon">AI</span> AI 今日关注</h2><small>运营规则聚合 · 未调用模型</small></header>
        <div v-if="loading && !overview" class="customer-state">正在读取园区运营数据…</div>
        <div v-else-if="attentionItems.length === 0" class="customer-state">{{ errors.overview || '当前窗口暂无需要关注的楼宇' }}</div>
        <button
          v-for="building in attentionItems"
          :key="building.buildingId"
          type="button"
          class="customer-attention__item"
          :class="{ 'is-selected': selectedBuildingId === building.buildingId }"
          :data-building-id="building.buildingId"
          @click="selectBuilding(building.buildingId)"
        >
          <span class="customer-attention__level" :class="attentionSignal(building).className">{{ attentionSignal(building).label }}</span>
          <span><strong>{{ attentionTitle(building) }}</strong><small>{{ attentionDescription(building) }}</small></span>
          <span>定位楼宇</span>
        </button>
      </article>

      <article class="customer-campus" :style="{ backgroundImage: `url(${parkAerial})` }" aria-label="演示园区空间示意">
        <header><strong>演示园区</strong><span>空间示意 · 非实时数字孪生</span></header>
        <span class="customer-campus__north" aria-hidden="true">N</span>
        <button
          v-for="building in visibleBuildings"
          :key="building.id"
          type="button"
          class="customer-campus__marker"
          :class="[`is-${markerState(building.summary)}`, { 'is-selected': selectedBuildingId === building.id }]"
          :style="building.position"
          :data-building-marker="building.id"
          :aria-pressed="selectedBuildingId === building.id"
          @click="selectBuilding(building.id)"
        >
          <strong>{{ building.id }} · {{ building.name }}</strong>
          <span>{{ markerState(building.summary) === 'warning' ? '需要关注' : markerState(building.summary) === 'normal' ? '已取得状态' : '状态未取得' }}</span>
        </button>
        <footer>
          <span>当前选择</span>
          <strong>{{ selectedBuildingId ? `${selectedBuildingId} · ${buildingName(selectedBuildingId)}` : '尚无可选楼宇' }}</strong>
          <small v-if="selectedBuilding">告警 {{ domainUsable('alerts') ? selectedBuilding.alertCount : '—' }} · 离线设备 {{ domainUsable('devices') ? selectedBuilding.offlineDeviceCount : '—' }} · 能耗偏差 {{ domainUsable('energy') ? formatNumber(selectedBuilding.energyDeviationPct) : '—' }}%</small>
        </footer>
      </article>

      <aside class="park-overview__aside">
        <article class="customer-card customer-todos">
          <header><h2><Checked aria-hidden="true" /> 我的待办</h2><small>只读队列</small></header>
          <p v-if="errors.workItems" class="customer-state is-compact">{{ errors.workItems }}</p>
          <p v-else-if="workItemsLoading" class="customer-state is-compact">正在读取待办…</p>
          <p v-else-if="workItems.length === 0" class="customer-state is-compact">当前队列暂无待办</p>
          <div v-for="item in workItems" :key="item.id" class="customer-todos__item">
            <span></span><div><strong>{{ item.title }}</strong><small>{{ locationLabel(item) }} · {{ item.status }}</small></div><time>{{ formatTime(item.slaDueAt ?? item.updatedAt, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</time>
          </div>
        </article>
        <article class="customer-report-promo">
          <Document aria-hidden="true" />
          <div><h2>运营简报入口</h2><p>报告中心将在 #72 接入；本项不生成或伪造报告。</p></div>
          <span aria-disabled="true">尚未开放</span>
        </article>
      </aside>
    </section>

    <section class="park-overview__bottom-grid">
      <article class="customer-card customer-chart-card is-wide">
        <header><div><h2>园区能耗趋势</h2><small>最近 24 小时 · 实际观测</small></div><span>{{ energy?.status ?? 'UNAVAILABLE' }}</span></header>
        <CustomerOverviewChart v-if="energyTrend.length" kind="line" :data="energyTrend" :unit="energy?.unit" label="园区最近二十四小时实际能耗趋势，缺失时段保留断点" />
        <p v-else class="customer-state">{{ energyLoading ? '正在读取能耗观测…' : errors.energy || '当前窗口暂无可绘制的能耗观测' }}</p>
      </article>
      <article class="customer-card customer-chart-card">
        <header><div><h2>能耗分布</h2><small>按楼宇已观测值</small></div></header>
        <CustomerOverviewChart v-if="energyDistribution.some((item) => item.value)" kind="donut" :data="energyDistribution" :unit="energy?.unit" label="各楼宇已观测能耗分布" />
        <p v-else class="customer-state">{{ energyLoading ? '正在读取能耗观测…' : errors.energy || '暂无能耗分布数据' }}</p>
      </article>
      <article class="customer-card customer-chart-card">
        <header><div><h2>事件分布</h2><small>当前查询窗口</small></div></header>
        <CustomerOverviewChart v-if="eventDistribution.some((item) => item.value)" kind="donut" :data="eventDistribution" unit="件" label="当前查询窗口事件类别分布" />
        <p v-else class="customer-state">{{ domainUsable('alerts') ? '当前窗口暂无事件' : '告警数据暂不可用' }}</p>
      </article>
      <article class="customer-card customer-latest">
        <header><div><h2>最新事件</h2><small>{{ selectedBuildingId ? `${buildingName(selectedBuildingId)} · 同一业务窗口` : '选择楼宇后查看' }}</small></div></header>
        <p v-if="detailLoading" class="customer-state is-compact">正在读取楼宇事件…</p>
        <p v-else-if="errors.evidence" class="customer-state is-compact">{{ errors.evidence }}</p>
        <p v-else-if="latestEvents.length === 0" class="customer-state is-compact">当前楼宇暂无事件记录</p>
        <div v-for="(event, index) in latestEvents" :key="String(event.alertId ?? event.deviceId ?? event.meterId ?? index)" class="customer-latest__row">
          <span><WarningFilled v-if="event.riskLevel === 'HIGH'" aria-hidden="true" /><DataLine v-else aria-hidden="true" /></span>
          <strong>{{ recordText(event) }}</strong>
          <time>{{ recordTime(event) }}</time>
        </div>
      </article>
    </section>
  </main>
</template>
