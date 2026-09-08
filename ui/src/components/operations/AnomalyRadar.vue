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
  } catch {
    if (generation !== requestGeneration) return
    overview.value = null
    error.value = '异常聚合暂不可用，请稍后重试。'
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

function dateLabel(value: string | null, timezone: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return value
  try {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date)
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

function breakdownTitle(name: string): string {
  return name === 'riskLevels' ? '风险等级' : name === 'categories' ? '告警类别' : name === 'statuses' ? '告警状态' : '离线设备类型'
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
  const labels: Array<[FilterKey, string]> = [['riskLevel', '风险等级'], ['category', '告警类别'], ['status', '告警状态'], ['deviceType', '设备类型']]
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

const hasPartialData = computed(() => Object.values(overview.value?.domainStatus ?? {}).some((status) => status === 'UNAVAILABLE' || status === 'PARTIAL'))
const energyBuildings = computed(() => overview.value?.buildings.filter((building) => building.energyDeviationPct != null) ?? [])
const energyScaleMax = computed(() => Math.max(1, ...energyBuildings.value.map((building) => Math.abs(building.energyDeviationPct ?? 0))))

watch(() => props.role, () => { facetOptions.value = {} })
watch([() => props.active, () => props.role], ([active]) => { if (active) void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="panel anomaly-radar" data-anomaly-radar>
    <div class="anomaly-radar__heading">
      <div><span class="eyebrow">ANOMALY RADAR / SOURCE OF TRUTH</span><h2>园区运营异常雷达</h2><p v-if="overview">数据窗口：{{ dateLabel(overview.window.from, overview.window.timezone) }} ~ {{ dateLabel(overview.window.to, overview.window.timezone) }} · 设备快照：{{ dateLabel(overview.asOf, overview.window.timezone) }}</p></div>
      <button type="button" class="anomaly-radar__retry" :disabled="loading || !props.active" @click="load">{{ loading ? '同步中…' : '刷新真实数据' }}</button>
    </div>
    <p v-if="error" class="anomaly-radar__state anomaly-radar__state--error">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="loading && !overview" class="anomaly-radar__state">正在读取异常聚合…</p>
    <p v-else-if="!overview" class="anomaly-radar__state">异常雷达暂不可用，请确认运营分析能力已启用。</p>
    <template v-else>
      <div class="anomaly-radar__cards">
        <article><span>近 7 天告警</span><strong>{{ valueOrDash(overview.summary.alertCount, 'alerts') }}</strong><small>ALERTS</small></article>
        <article class="is-danger"><span>近 7 天高风险告警</span><strong>{{ valueOrDash(overview.summary.highRiskAlertCount, 'alerts') }}</strong><small>HIGH RISK</small></article>
        <article><span>最近 1 天离线设备</span><strong>{{ valueOrDash(overview.summary.offlineDeviceCount, 'devices') }}</strong><small>OFFLINE</small></article>
        <article><span>受影响楼宇</span><strong>{{ overview.summary.affectedBuildingCount }}</strong><small>{{ hasPartialData ? 'PARTIAL · 部分数据' : 'COMPOSITE' }}</small></article>
      </div>

      <div class="anomaly-radar__controlbar">
        <div class="anomaly-radar__filters" aria-label="异常筛选">
          <label v-for="filter in filterDefinitions" :key="filter.key"><span>{{ filter.label }}</span><select :data-anomaly-filter="filter.key" :value="filters[filter.key] ?? ''" @change="onFilterChange(filter.key, $event)"><option value="">全部</option><option v-for="option in filterOptions(filter.breakdown)" :key="option" :value="option">{{ option }}</option></select></label>
        </div>
        <button type="button" :disabled="loading" @click="emit('open-analysis', analysisQuestion())">让 Agent 分析当前筛选</button>
      </div>
      <div class="anomaly-radar__status" aria-label="数据域状态"><span v-for="(status, domain) in overview.domainStatus" :key="domain" :data-domain-status="status">{{ domain }}：{{ status }}</span></div>

      <div class="anomaly-radar__cockpit">
        <section class="anomaly-radar__energy" data-radar-panel="energy-deviation">
          <div class="anomaly-radar__panel-head"><div><small>ADAPTED · 真实指标</small><h3>楼宇能耗基线偏差</h3></div><button type="button" @click="emit('open-analysis', '过去5天各楼宇能耗基线偏差')">打开完整分析 ↗</button></div>
          <p v-if="domainUnavailable('energy')" class="anomaly-radar__state anomaly-radar__state--warning">能耗数据暂不可用</p>
          <div v-else-if="energyBuildings.length" class="anomaly-radar__energy-bars">
            <div v-for="building in energyBuildings" :key="building.buildingId"><span>{{ building.buildingId }}</span><progress :value="Math.abs(building.energyDeviationPct ?? 0)" :max="energyScaleMax"></progress><strong>{{ energyLabel(building.energyDeviationPct) }}</strong></div>
          </div>
          <p v-else class="anomaly-radar__state">当前窗口暂无能耗偏差数据。</p>
          <small class="anomaly-radar__truth-note">仅展示 anomaly overview 返回的基线偏差；当前接口不提供时序 DTO，因此不生成趋势曲线。</small>
        </section>

        <section class="anomaly-radar__buildings" data-radar-panel="building-ranking">
          <div class="anomaly-radar__panel-head"><div><small>DIRECT_REUSE</small><h3>异常楼宇排行</h3></div></div>
          <button v-for="building in overview.buildings" :key="building.buildingId" type="button" :data-anomaly-building="building.buildingId" :disabled="loading" :data-domain-status="loading ? 'LOADING' : undefined" @click="emit('open-building', building.buildingId, evidenceFilters())"><span>{{ building.buildingId }}</span><small>告警 {{ valueOrDash(building.alertCount, 'alerts') }} · 高风险 {{ valueOrDash(building.highRiskAlertCount, 'alerts') }} · 离线 {{ valueOrDash(building.offlineDeviceCount, 'devices') }} · 能耗偏差 {{ energyLabel(building.energyDeviationPct) }}</small></button>
          <small v-if="overview.buildings.length === 0">当前窗口暂无异常楼宇</small>
        </section>

        <section class="anomaly-radar__breakdowns" data-radar-panel="risk-distribution">
          <div class="anomaly-radar__panel-head"><div><small>DIRECT_REUSE</small><h3>风险 / 类别 / 状态分布</h3></div></div>
          <div class="anomaly-radar__breakdown-grid">
            <div v-for="(items, name) in overview.breakdowns" :key="name" class="anomaly-radar__breakdown"><strong>{{ breakdownTitle(name) }}</strong><span v-for="item in items" :key="item.key"><i>{{ item.key }}</i><b>{{ item.count }}</b></span><small v-if="items.length === 0 && breakdownUnavailable(name)">{{ breakdownUnavailableLabel(name) }}</small><small v-else-if="items.length === 0">暂无数据</small></div>
          </div>
        </section>

        <section class="anomaly-radar__device" data-radar-panel="device-health" data-feature-state="ADAPTED">
          <div class="anomaly-radar__panel-head"><div><small>ADAPTED · 不生成健康分</small><h3>设备健康研判</h3></div></div>
          <strong>{{ valueOrDash(overview.summary.offlineDeviceCount, 'devices') }}</strong><span>最近 1 天离线设备</span>
          <p v-if="domainUnavailable('devices')">设备数据暂不可用，不能推断健康状态。</p>
          <p v-else>结合离线设备、设备类型与关联告警作为可解释信号；需要设备评分模型后才能展示健康度。</p>
          <button type="button" :disabled="domainUnavailable('devices')" @click="emit('open-analysis', '各设备类型离线设备数')">查看设备类型证据 ↗</button>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.anomaly-radar { padding: 24px; overflow: hidden; }
.anomaly-radar__heading, .anomaly-radar__panel-head, .anomaly-radar__controlbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.anomaly-radar__heading h2 { margin: 5px 0; font-size: 1.6rem; }
.anomaly-radar__heading p { margin: 0; color: var(--showcase-muted); font-size: .78rem; }
.anomaly-radar__retry, .anomaly-radar__controlbar > button, .anomaly-radar__panel-head button, .anomaly-radar__device button { padding: 8px 11px; color: var(--showcase-cyan); border: 1px solid rgba(112, 232, 255, .32); background: rgba(22, 94, 125, .16); cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .5; }
.anomaly-radar__state { color: var(--showcase-muted); }
.anomaly-radar__state--error { color: var(--showcase-danger, #ff8a8a); }
.anomaly-radar__state--warning { color: #f4c46b; }
.anomaly-radar__state button { margin-left: 8px; color: var(--showcase-cyan); border: 0; background: transparent; cursor: pointer; }
.anomaly-radar__cards { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 18px; }
.anomaly-radar__cards article { position: relative; min-height: 116px; padding: 16px; overflow: hidden; border: 1px solid rgba(112, 232, 255, .17); background: linear-gradient(145deg, rgba(10, 25, 41, .8), rgba(6, 13, 25, .72)); }
.anomaly-radar__cards article::after { content: ''; position: absolute; right: -24px; bottom: -28px; width: 70px; height: 70px; border: 1px solid rgba(112, 232, 255, .12); border-radius: 50%; }
.anomaly-radar__cards span, .anomaly-radar__cards strong, .anomaly-radar__cards small { display: block; }
.anomaly-radar__cards span { color: var(--showcase-muted); font-size: .78rem; }
.anomaly-radar__cards strong { margin-top: 9px; color: var(--showcase-cyan); font-size: 2rem; }
.anomaly-radar__cards .is-danger strong { color: #ff8c9b; }
.anomaly-radar__cards small { margin-top: 4px; color: #66899f; font-size: .62rem; letter-spacing: .1em; }
.anomaly-radar__controlbar { margin-top: 10px; padding: 10px 0; border-bottom: 1px solid var(--showcase-border-soft); }
.anomaly-radar__filters { display: flex; flex-wrap: wrap; gap: 8px; }
.anomaly-radar__filters label { display: grid; gap: 4px; color: var(--showcase-muted); font-size: .7rem; }
.anomaly-radar__filters select { min-width: 112px; padding: 6px 8px; color: var(--showcase-ivory); border: 1px solid var(--showcase-border-soft); background: #0a1524; }
.anomaly-radar__status { display: flex; flex-wrap: wrap; gap: 7px; margin: 10px 0; color: var(--showcase-muted); font-size: .68rem; }
.anomaly-radar__status span { padding: 4px 7px; border: 1px solid var(--showcase-border-soft); }
.anomaly-radar__status span[data-domain-status='UNAVAILABLE'] { color: #f4c46b; border-color: rgba(244, 196, 107, .28); }
.anomaly-radar__status span[data-domain-status='PARTIAL'] { color: #b9a3ff; }
.anomaly-radar__cockpit { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(290px, .65fr); gap: 10px; }
.anomaly-radar__energy, .anomaly-radar__buildings, .anomaly-radar__breakdowns, .anomaly-radar__device { min-width: 0; padding: 16px; border: 1px solid var(--showcase-border-soft); background: rgba(6, 15, 28, .58); }
.anomaly-radar__panel-head small { color: #64d8f3; font-size: .62rem; letter-spacing: .1em; }
.anomaly-radar__panel-head h3 { margin: 4px 0 0; font-size: 1rem; }
.anomaly-radar__panel-head button { padding: 5px 8px; font-size: .7rem; }
.anomaly-radar__energy-bars { display: grid; gap: 13px; margin-top: 22px; }
.anomaly-radar__energy-bars div { display: grid; grid-template-columns: 82px minmax(0, 1fr) 64px; gap: 10px; align-items: center; }
.anomaly-radar__energy-bars span { overflow: hidden; color: #a9bbc9; font-size: .75rem; text-overflow: ellipsis; }
.anomaly-radar__energy-bars strong { color: var(--showcase-cyan); text-align: right; }
.anomaly-radar__energy-bars progress { width: 100%; height: 8px; border: 0; border-radius: 8px; overflow: hidden; background: rgba(112, 232, 255, .08); }
.anomaly-radar__energy-bars progress::-webkit-progress-bar { background: rgba(112, 232, 255, .08); }
.anomaly-radar__energy-bars progress::-webkit-progress-value { background: linear-gradient(90deg, #537bff, #60e8ff); }
.anomaly-radar__truth-note { display: block; margin-top: 18px; color: #718799; line-height: 1.5; }
.anomaly-radar__buildings > button { display: block; width: 100%; padding: 11px 0; color: var(--showcase-ivory); text-align: left; border: 0; border-bottom: 1px solid var(--showcase-border-soft); background: transparent; cursor: pointer; }
.anomaly-radar__buildings > button:hover { color: var(--showcase-cyan); }
.anomaly-radar__buildings > button span, .anomaly-radar__buildings > button small { display: block; }
.anomaly-radar__buildings > button small, .anomaly-radar__buildings > small { margin-top: 4px; color: var(--showcase-muted); line-height: 1.4; }
.anomaly-radar__breakdown-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 14px; }
.anomaly-radar__breakdown { min-width: 0; padding: 10px; border: 1px solid rgba(112, 232, 255, .1); background: rgba(10, 24, 39, .52); }
.anomaly-radar__breakdown > strong { display: block; margin-bottom: 7px; font-size: .76rem; }
.anomaly-radar__breakdown span { display: flex; justify-content: space-between; gap: 6px; padding: 5px 0; color: var(--showcase-muted); border-bottom: 1px solid var(--showcase-border-soft); font-size: .72rem; }
.anomaly-radar__breakdown i { overflow: hidden; font-style: normal; text-overflow: ellipsis; }
.anomaly-radar__breakdown b { color: var(--showcase-ivory); }
.anomaly-radar__breakdown small { color: var(--showcase-muted); }
.anomaly-radar__device > strong { display: block; margin-top: 18px; color: var(--showcase-cyan); font-size: 2.5rem; }
.anomaly-radar__device > span { color: var(--showcase-muted); }
.anomaly-radar__device p { color: var(--showcase-muted); font-size: .78rem; line-height: 1.55; }
.anomaly-radar__device button { margin-top: 8px; }
@media (max-width: 980px) { .anomaly-radar__cockpit { grid-template-columns: 1fr; } .anomaly-radar__breakdown-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .anomaly-radar { padding: 16px; } .anomaly-radar__heading, .anomaly-radar__controlbar { align-items: stretch; flex-direction: column; } .anomaly-radar__cards, .anomaly-radar__breakdown-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .anomaly-radar__controlbar > button { align-self: flex-start; } }
@media (max-width: 460px) { .anomaly-radar__cards, .anomaly-radar__breakdown-grid { grid-template-columns: 1fr; } }
</style>
