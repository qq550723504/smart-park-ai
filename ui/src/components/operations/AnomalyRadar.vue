<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { DemoRole } from '../../types/workflow'
import type { AnomalyFilters, AnomalyOverview } from '../../types/operationsAnomaly'
import { getAnomalyOverview } from '../../services/operationsAnomalyApi'

const props = withDefaults(defineProps<{ role: DemoRole; active?: boolean }>(), { active: true })
const emit = defineEmits<{
  'open-building': [buildingId: string, filters: AnomalyFilters]
  'open-analysis': [question: string]
  'open-trace': [runId: string]
}>()

const overview = ref<AnomalyOverview | null>(null)
const loading = ref(false)
const error = ref('')
type FilterKey = 'riskLevel' | 'category' | 'status' | 'deviceType'
const filterDefinitions: Array<{ key: FilterKey; label: string; breakdown: string }> = [
  { key: 'riskLevel', label: '风险等级', breakdown: 'riskLevels' },
  { key: 'category', label: '告警类别', breakdown: 'categories' },
  { key: 'status', label: '告警状态', breakdown: 'statuses' },
  { key: 'deviceType', label: '设备类型', breakdown: 'deviceTypes' },
]
const filters = ref<AnomalyFilters>({})
const facetOptions = ref<Record<string, string[]>>({})
let requestGeneration = 0

async function load(): Promise<void> {
  if (!props.active) return
  const generation = ++requestGeneration
  loading.value = true
  error.value = ''
  overview.value = null
  try {
    const value = await getAnomalyOverview(props.role, filters.value)
    if (generation !== requestGeneration) return
    rememberFacetOptions(value)
    overview.value = value
  } catch (cause) {
    if (generation !== requestGeneration) return
    overview.value = null
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

function dateLabel(value: string | null, timezone: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return value
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(date)
    const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
    return `${values.year}/${values.month}/${values.day}`
  } catch {
    return value
  }
}

function domainUnavailable(domain: string): boolean {
  return overview.value?.domainStatus[domain] === 'UNAVAILABLE'
}

function valueOrDash(value: number, domain: string): string {
  return domainUnavailable(domain) || value == null ? '—' : String(value)
}

function energyLabel(value: number | null): string {
  return domainUnavailable('energy') || value == null ? '—' : `${value}%`
}

const supportedCategories = new Set(['TEMPERATURE', 'POWER', 'HUMIDITY', 'ACCESS'])

function filterOptions(breakdown: string): string[] {
  const options = facetOptions.value[breakdown] ?? []
  if (breakdown === 'categories') return options.filter((option) => supportedCategories.has(option))
  if (breakdown === 'riskLevels') return options.filter((option) => ['LOW', 'MEDIUM', 'HIGH'].includes(option))
  if (breakdown === 'statuses') return options.filter((option) => ['OPEN', 'RESOLVED'].includes(option))
  return options
}

function rememberFacetOptions(value: AnomalyOverview): void {
  Object.entries(value.breakdowns).forEach(([name, items]) => {
    const known = facetOptions.value[name] ?? []
    facetOptions.value[name] = [...new Set([...known, ...items.map((item) => item.key)])]
  })
}

function onFilterChange(key: FilterKey, event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  const next = { ...filters.value }
  if (value) next[key] = value
  else delete next[key]
  if (value && key === 'deviceType') {
    delete next.riskLevel
    delete next.category
    delete next.status
  } else if (value) {
    delete next.deviceType
  }
  filters.value = next
  void load()
}

function evidenceFilters(): AnomalyFilters {
  if (!overview.value) return { ...filters.value }
  return { ...filters.value, from: overview.value.window.from, to: overview.value.window.to }
}

function breakdownDomain(name: string): string | null {
  return name === 'deviceTypes' ? 'devices' : ['riskLevels', 'categories', 'statuses'].includes(name) ? 'alerts' : null
}

function breakdownUnavailable(name: string): boolean {
  const domain = breakdownDomain(name)
  return domain ? domainUnavailable(domain) : false
}

function breakdownUnavailableLabel(name: string): string {
  return breakdownDomain(name) === 'devices' ? '设备数据暂不可用' : '告警数据暂不可用'
}

function riskLabel(value: string): string {
  return ({ HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' } as Record<string, string>)[value] ?? value
}

function analysisQuestion(): string {
  const metric = filters.value.deviceType ? '离线设备数量' : '告警数量'
  const labels: Array<[FilterKey, string]> = [
    ['riskLevel', '风险等级'], ['category', '告警类别'],
    ['status', '告警状态'], ['deviceType', '设备类型'],
  ]
  const context = labels
    .filter(([key]) => metric === '离线设备数量' ? key === 'deviceType' : key !== 'deviceType')
    .filter(([key]) => filters.value[key])
    .map(([key, label]) => `${label}：${key === 'riskLevel' ? riskLabel(filters.value[key]!) : filters.value[key]}`)
  const overviewWindow = overview.value?.window
  const deviceFrom = overviewWindow
    ? new Date(Math.max(Date.parse(overviewWindow.from), Date.parse(overviewWindow.to) - 24 * 60 * 60 * 1000)).toISOString()
    : null
  const from = metric === '离线设备数量' ? deviceFrom : overviewWindow?.from
  const to = overviewWindow?.to
  const window = from && to ? `时间范围：${from}至${to}` : '过去7天'
  return `${window}各${metric === '离线设备数量' ? '楼宇离线设备数量' : '楼宇告警数量'}${context.length ? `（${context.join('；')}）` : ''}`
}

const hasPartialData = computed(() => Object.values(overview.value?.domainStatus ?? {})
  .some((status) => status === 'UNAVAILABLE' || status === 'PARTIAL'))

watch(() => props.role, () => {
  facetOptions.value = {}
})
watch([() => props.active, () => props.role], ([active]) => {
  if (active) void load()
})
onMounted(() => { void load() })
</script>

<template>
  <section class="panel anomaly-radar" data-anomaly-radar>
    <div class="section-heading compact">
      <div><span class="eyebrow">异常雷达 · 只读聚合</span><h2>运营异常总览</h2></div>
      <button type="button" class="anomaly-radar__retry" :disabled="loading || !props.active" @click="load">{{ loading ? '同步中…' : '刷新' }}</button>
    </div>
    <p v-if="overview" class="anomaly-radar__window">数据窗口：{{ dateLabel(overview.window.from, overview.window.timezone) }} ~ {{ dateLabel(overview.window.to, overview.window.timezone) }} · 设备快照：{{ dateLabel(overview.asOf, overview.window.timezone) }}</p>
    <p v-if="error" class="anomaly-radar__state anomaly-radar__state--error">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="loading && !overview" class="anomaly-radar__state">正在读取异常聚合…</p>
    <p v-else-if="!overview" class="anomaly-radar__state">异常雷达暂不可用，请确认运营分析能力已启用。</p>
    <template v-else>
      <div class="anomaly-radar__cards">
        <article class="anomaly-radar__card"><span>近 7 天告警</span><strong>{{ valueOrDash(overview.summary.alertCount, 'alerts') }}</strong></article>
        <article class="anomaly-radar__card anomaly-radar__card--danger"><span>近 7 天高风险告警</span><strong>{{ valueOrDash(overview.summary.highRiskAlertCount, 'alerts') }}</strong></article>
        <article class="anomaly-radar__card"><span>最近 1 天离线设备</span><strong>{{ valueOrDash(overview.summary.offlineDeviceCount, 'devices') }}</strong></article>
        <article class="anomaly-radar__card"><span>受影响楼宇</span><strong>{{ overview.summary.affectedBuildingCount }}</strong><small v-if="hasPartialData">部分数据</small></article>
      </div>
      <div class="anomaly-radar__filters" aria-label="异常筛选">
        <label v-for="filter in filterDefinitions" :key="filter.key">
          <span>{{ filter.label }}</span>
          <select :data-anomaly-filter="filter.key" :value="filters[filter.key] ?? ''" @change="onFilterChange(filter.key, $event)">
            <option value="">全部</option>
            <option v-for="option in filterOptions(filter.breakdown)" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>
      </div>
      <div class="anomaly-radar__actions"><button type="button" :disabled="loading" @click="emit('open-analysis', analysisQuestion())">分析当前筛选</button></div>
      <div class="anomaly-radar__status" aria-label="数据域状态">
        <span v-for="(status, domain) in overview.domainStatus" :key="domain" :data-domain-status="status">{{ domain }}：{{ status }}</span>
      </div>
      <p v-if="domainUnavailable('energy')" class="anomaly-radar__state anomaly-radar__state--warning">能耗数据暂不可用</p>
      <div class="anomaly-radar__body">
        <div class="anomaly-radar__breakdowns">
          <div v-for="(items, name) in overview.breakdowns" :key="name" class="anomaly-radar__breakdown">
            <strong>{{ name === 'riskLevels' ? '风险等级' : name === 'categories' ? '告警类别' : name === 'statuses' ? '告警状态' : '离线设备类型' }}</strong>
            <span v-for="item in items" :key="item.key"><i>{{ item.key }}</i><b>{{ item.count }}</b></span>
            <small v-if="items.length === 0 && breakdownUnavailable(name)">{{ breakdownUnavailableLabel(name) }}</small>
            <small v-else-if="items.length === 0">暂无数据</small>
          </div>
        </div>
        <div class="anomaly-radar__buildings">
          <strong>异常楼宇排行</strong>
          <button v-for="building in overview.buildings" :key="building.buildingId" type="button" :data-anomaly-building="building.buildingId" :disabled="loading" :data-domain-status="loading ? 'LOADING' : undefined" @click="emit('open-building', building.buildingId, evidenceFilters())">
            <span>{{ building.buildingId }}</span><small>告警 {{ valueOrDash(building.alertCount, 'alerts') }} · 离线 {{ valueOrDash(building.offlineDeviceCount, 'devices') }} · 能耗偏差 {{ energyLabel(building.energyDeviationPct) }}</small>
          </button>
          <small v-if="overview.buildings.length === 0">当前窗口暂无异常楼宇</small>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.anomaly-radar { margin-bottom: 18px; padding: 24px; }
.anomaly-radar__retry { border: 1px solid var(--showcase-cyan); color: var(--showcase-cyan); background: transparent; padding: 8px 13px; cursor: pointer; }
.anomaly-radar__retry:disabled { opacity: .55; cursor: not-allowed; }
.anomaly-radar__window, .anomaly-radar__state { color: var(--showcase-muted); }
.anomaly-radar__state--error { color: var(--showcase-danger, #ff8a8a); }
.anomaly-radar__state--warning { color: #f4c46b; }
.anomaly-radar__state button { margin-left: 8px; color: var(--showcase-cyan); border: 0; background: transparent; cursor: pointer; }
.anomaly-radar__cards { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 16px; }
.anomaly-radar__card { padding: 14px; border: 1px solid var(--showcase-border-soft); background: rgba(12, 17, 26, .42); }
.anomaly-radar__card span, .anomaly-radar__card strong { display: block; }
.anomaly-radar__card span { color: var(--showcase-muted); font-size: .82rem; }
.anomaly-radar__card strong { margin-top: 6px; color: var(--showcase-cyan); font-size: 1.55rem; }
.anomaly-radar__card--danger strong { color: var(--showcase-danger, #ff8a8a); }
.anomaly-radar__card small { display: block; margin-top: 4px; color: #f4c46b; }
.anomaly-radar__filters { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
.anomaly-radar__filters label { display: grid; gap: 4px; color: var(--showcase-muted); font-size: .78rem; }
.anomaly-radar__filters select { min-width: 120px; padding: 6px 8px; color: var(--showcase-ivory); border: 1px solid var(--showcase-border-soft); background: rgba(12, 17, 26, .72); }
.anomaly-radar__status { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; color: var(--showcase-muted); font-size: .78rem; }
.anomaly-radar__status span { padding: 4px 8px; border: 1px solid var(--showcase-border-soft); }
.anomaly-radar__status span[data-domain-status="UNAVAILABLE"] { color: #f4c46b; }
.anomaly-radar__body { display: grid; grid-template-columns: 1.25fr .75fr; gap: 14px; }
.anomaly-radar__breakdowns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.anomaly-radar__breakdown, .anomaly-radar__buildings { border: 1px solid var(--showcase-border-soft); padding: 13px; background: rgba(12, 17, 26, .28); }
.anomaly-radar__breakdown > strong, .anomaly-radar__buildings > strong { display: block; margin-bottom: 8px; }
.anomaly-radar__breakdown span { display: flex; justify-content: space-between; padding: 5px 0; color: var(--showcase-muted); border-bottom: 1px solid var(--showcase-border-soft); }
.anomaly-radar__breakdown i { font-style: normal; }
.anomaly-radar__breakdown b { color: var(--showcase-ivory); }
.anomaly-radar__breakdown small, .anomaly-radar__buildings > small { color: var(--showcase-muted); }
.anomaly-radar__buildings button { display: block; width: 100%; padding: 9px 0; color: var(--showcase-ivory); text-align: left; border: 0; border-bottom: 1px solid var(--showcase-border-soft); background: transparent; cursor: pointer; }
.anomaly-radar__buildings button:hover { color: var(--showcase-cyan); }
.anomaly-radar__buildings button span, .anomaly-radar__buildings button small { display: block; }
.anomaly-radar__buildings button small { margin-top: 3px; color: var(--showcase-muted); }
@media (max-width: 850px) { .anomaly-radar__cards { grid-template-columns: repeat(2, minmax(0, 1fr)); } .anomaly-radar__body { grid-template-columns: 1fr; } }
@media (max-width: 520px) { .anomaly-radar__cards, .anomaly-radar__breakdowns { grid-template-columns: 1fr; } }
</style>
