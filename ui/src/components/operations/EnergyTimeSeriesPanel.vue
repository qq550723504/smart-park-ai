<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { getEnergyTimeSeries } from '../../services/energyTimeSeriesApi'
import type { DemoRole } from '../../types/workflow'
import type { EnergyTimeSeriesResponse, EnergyTimeSeriesStatus } from '../../types/energyTimeSeries'

const props = withDefaults(defineProps<{
  role: DemoRole
  active?: boolean
  buildingIds?: string[]
}>(), { active: true, buildingIds: () => ['B1', 'B2', 'B3'] })
const emit = defineEmits<{ status: [status: EnergyTimeSeriesStatus] }>()

const response = ref<EnergyTimeSeriesResponse | null>(null)
const loading = ref(false)
const error = ref('')
const container = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let requestGeneration = 0

const hasPoints = computed(() => response.value?.series.some((series) => series.points.length > 0) ?? false)
const canRender = computed(() => response.value?.status !== 'UNAVAILABLE' && hasPoints.value)
const missingPointCount = computed(() => response.value?.series
  .reduce((total, series) => total + series.missingTimestamps.length, 0) ?? 0)

async function load(): Promise<void> {
  if (!props.active) return
  const generation = ++requestGeneration
  loading.value = true
  error.value = ''
  response.value = null
  disposeChart()
  try {
    const value = await getEnergyTimeSeries(props.role, {
      buildingIds: props.buildingIds,
      metric: 'energy_kwh',
      granularity: 'HOUR',
    })
    if (generation !== requestGeneration) return
    response.value = value
    emit('status', value.status)
  } catch {
    if (generation !== requestGeneration) return
    error.value = '能耗时序暂不可用，请稍后重试。'
    emit('status', 'UNAVAILABLE')
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

function disposeChart(): void {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
}

function render(): void {
  if (!container.value || !response.value || !canRender.value) {
    disposeChart()
    return
  }
  const current = response.value
  try {
    chart = chart ?? echarts.init(container.value)
    const series = current.series
      .filter((item) => item.points.length > 0)
      .map((item) => ({
        name: item.buildingId,
        type: 'line' as const,
        showSymbol: true,
        symbolSize: 4,
        connectNulls: false,
        data: [
          ...item.points.map((point) => [point.timestamp, point.value] as [string, number | null]),
          ...item.missingTimestamps.map((timestamp) => [timestamp, null] as [string, number | null]),
        ].sort((left, right) => left[0].localeCompare(right[0])),
      }))
    chart.setOption({
      backgroundColor: 'transparent',
      color: ['#70e8ff', '#8f5cff', '#ffd27a', '#63e6b2'],
      textStyle: { color: '#c8d3e0' },
      legend: { textStyle: { color: '#c8d3e0' } },
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(4, 7, 12, .94)',
        borderColor: 'rgba(112, 232, 255, .35)',
        textStyle: { color: '#fff0d2' },
        valueFormatter: (value: unknown) => {
          const observed = Array.isArray(value) ? value[1] : value
          return observed == null ? '缺失' : `${String(observed)} ${current.unit}`
        },
      },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#98a4b6' },
        axisLine: { lineStyle: { color: 'rgba(176, 190, 208, .28)' } },
      },
      yAxis: {
        type: 'value',
        name: current.unit,
        nameTextStyle: { color: '#c8d3e0' },
        axisLabel: { color: '#98a4b6' },
        splitLine: { lineStyle: { color: 'rgba(176, 190, 208, .12)' } },
      },
      series,
    }, true)
    if (typeof ResizeObserver === 'function' && !resizeObserver) {
      resizeObserver = new ResizeObserver(() => chart?.resize())
      resizeObserver.observe(container.value)
    }
  } catch {
    // Canvas can be unavailable in tests; the textual availability state remains usable.
  }
}

function formatAsOf(value: string | null, timezone: string): string {
  if (!value) return '—'
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: timezone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(new Date(value))
  } catch {
    return value
  }
}

watch(response, render, { flush: 'post' })
watch(container, render, { flush: 'post' })
watch([() => props.active, () => props.role, () => props.buildingIds], ([active]) => {
  if (active) void load()
  else {
    requestGeneration++
    disposeChart()
  }
})
onMounted(() => { void load() })
onBeforeUnmount(() => {
  requestGeneration++
  disposeChart()
})
</script>

<template>
  <section class="panel energy-timeseries" data-energy-timeseries>
    <div class="energy-timeseries__heading">
      <div>
        <span class="eyebrow">ENERGY TIME SERIES / SOURCE OF TRUTH</span>
        <h2>楼宇真实能耗趋势</h2>
        <p v-if="response">{{ response.window.granularity }} · {{ response.timezone }} · 截至 {{ formatAsOf(response.asOf, response.timezone) }}</p>
        <p v-else>读取 Operations Analytics 已登记能耗指标；缺失数据不会补零或插值。</p>
      </div>
      <button type="button" :disabled="loading || !props.active" @click="load">{{ loading ? '同步中…' : '刷新真实数据' }}</button>
    </div>
    <p v-if="error" class="energy-timeseries__state is-error" role="alert">{{ error }}</p>
    <p v-else-if="loading && !response" class="energy-timeseries__state" role="status">正在读取真实能耗时序…</p>
    <p v-else-if="response?.status === 'UNAVAILABLE' || (response && !hasPoints)" class="energy-timeseries__state" data-energy-unavailable>
      UNAVAILABLE · 当前窗口没有可绘制的真实能耗数据。
    </p>
    <template v-else-if="response">
      <p v-if="response.status === 'PARTIAL'" class="energy-timeseries__state is-partial" data-energy-partial>
        PARTIAL · 返回已有真实点，{{ missingPointCount }} 个时间点缺失；图表已断开缺口且未补零。
      </p>
      <div v-if="canRender" ref="container" class="energy-timeseries__chart" data-energy-chart aria-label="真实楼宇能耗折线图"></div>
      <div class="energy-timeseries__footer">
        <span>{{ response.status }}</span>
        <span>单位 {{ response.unit }}</span>
        <span>来源 {{ response.source.system }}</span>
      </div>
    </template>
  </section>
</template>

<style scoped>
.energy-timeseries { display: grid; min-width: 0; gap: 14px; padding: 22px; overflow: hidden; }
.energy-timeseries__heading { display: flex; align-items: start; justify-content: space-between; gap: 20px; }
.energy-timeseries__heading h2 { margin: 6px 0; }
.energy-timeseries__heading p { margin: 0; color: var(--showcase-muted); }
.energy-timeseries__heading button { padding: 9px 12px; color: var(--showcase-cyan); border: 1px solid rgba(112, 232, 255, .35); background: rgba(19, 83, 112, .18); cursor: pointer; }
.energy-timeseries__heading button:disabled { cursor: not-allowed; opacity: .5; }
.energy-timeseries__state { margin: 0; padding: 12px; color: var(--showcase-muted); border: 1px solid var(--showcase-border-soft); background: rgba(7, 16, 29, .58); }
.energy-timeseries__state.is-partial { color: var(--showcase-amber); border-color: rgba(255, 210, 122, .28); }
.energy-timeseries__state.is-error { color: var(--showcase-danger, #ff8a8a); }
.energy-timeseries__chart { width: 100%; min-width: 0; height: 320px; }
.energy-timeseries__footer { display: flex; flex-wrap: wrap; gap: 8px 18px; color: var(--showcase-muted); font-size: .78rem; }
.energy-timeseries__footer span:first-child { color: var(--showcase-cyan); font-weight: 800; }
@media (max-width: 720px) { .energy-timeseries__heading { flex-direction: column; } .energy-timeseries__chart { height: 260px; } }
</style>
