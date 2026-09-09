<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { getDeviceHealth, getDeviceTelemetry } from '../../services/deviceTelemetryApi'
import type { DeviceHealthResponse, DeviceTelemetryResponse, TelemetryStatus } from '../../types/deviceTelemetry'
import type { DemoRole } from '../../types/workflow'

const props = withDefaults(defineProps<{ role: DemoRole; active?: boolean }>(), { active: true })
const emit = defineEmits<{ 'telemetry-status': [status: TelemetryStatus]; 'health-status': [status: TelemetryStatus] }>()
const demoDevices = [
  { id: 'AC-B1-07', label: 'B1 · HVAC AC-B1-07' },
  { id: 'HUM-B2-11', label: 'B2 · HVAC HUM-B2-11' },
  { id: 'AC-B3-03', label: 'B3 · HVAC AC-B3-03' },
]
const selectedDeviceId = ref(demoDevices[0].id)
const telemetry = ref<DeviceTelemetryResponse | null>(null)
const health = ref<DeviceHealthResponse | null>(null)
const loading = ref(false)
const error = ref('')
const container = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null
let requestGeneration = 0

const series = computed(() => telemetry.value?.series.find((item) => item.deviceId === selectedDeviceId.value) ?? null)
const missingCount = computed(() => series.value?.missingTimestamps.length ?? 0)
const canRender = computed(() => telemetry.value?.status !== 'UNAVAILABLE' && (series.value?.points.length ?? 0) > 0)

async function load(): Promise<void> {
  if (!props.active) return
  const generation = ++requestGeneration
  loading.value = true
  error.value = ''
  telemetry.value = null
  health.value = null
  disposeChart()
  const [telemetryResult, healthResult] = await Promise.allSettled([
    getDeviceTelemetry(props.role, [selectedDeviceId.value]),
    getDeviceHealth(props.role, selectedDeviceId.value),
  ])
  if (generation !== requestGeneration) return
  if (telemetryResult.status === 'fulfilled') {
    telemetry.value = telemetryResult.value
    emit('telemetry-status', telemetryResult.value.status)
  } else emit('telemetry-status', 'UNAVAILABLE')
  if (healthResult.status === 'fulfilled') {
    health.value = healthResult.value
    emit('health-status', healthResult.value.availability)
  } else emit('health-status', 'UNAVAILABLE')
  if (telemetryResult.status === 'rejected' || healthResult.status === 'rejected') {
    error.value = '部分设备证据暂不可用；未返回的域不会被推断。'
  }
  loading.value = false
}

function disposeChart(): void {
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
}

function render(): void {
  if (!container.value || !telemetry.value || !series.value || !canRender.value) {
    disposeChart()
    return
  }
  try {
    chart = chart ?? echarts.init(container.value)
    const current = telemetry.value
    const points = new Map(series.value.points.map((point) => [point.timestamp, point.value]))
    const data = [...series.value.points.map((point) => point.timestamp), ...series.value.missingTimestamps]
      .sort().map((timestamp) => [timestamp, points.get(timestamp) ?? null] as [string, number | null])
    chart.setOption({
      backgroundColor: 'transparent',
      color: ['#70e8ff'],
      tooltip: {
        trigger: 'axis',
        formatter: (raw: unknown) => {
          const items = Array.isArray(raw) ? raw as Array<{ axisValue?: string; data?: [string, number | null] }> : []
          const first = items[0]
          const value = first?.data?.[1]
          return `${first?.axisValue ?? ''}<br/>${value == null ? '缺失' : `${value} ${current.unit}`}<br/>来源 ${current.source.system}`
        },
      },
      xAxis: { type: 'time', axisLabel: { color: '#98a4b6' }, axisLine: { lineStyle: { color: 'rgba(176,190,208,.28)' } } },
      yAxis: { type: 'value', name: current.unit, nameTextStyle: { color: '#c8d3e0' }, axisLabel: { color: '#98a4b6' }, splitLine: { lineStyle: { color: 'rgba(176,190,208,.12)' } } },
      series: [{ name: current.telemetryType, type: 'line', connectNulls: false, showSymbol: true, symbolSize: 4, data }],
    }, true)
    if (typeof ResizeObserver === 'function' && !observer) {
      observer = new ResizeObserver(() => chart?.resize())
      observer.observe(container.value)
    }
  } catch {
    // Textual evidence remains available if canvas is unavailable.
  }
}

function timeLabel(value: string | null | undefined): string {
  if (!value) return '—'
  try {
    return new Intl.DateTimeFormat('zh-CN', { timeZone: telemetry.value?.timezone ?? 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
  } catch { return value }
}

watch(telemetry, render, { flush: 'post' })
watch(container, render, { flush: 'post' })
watch([() => props.active, () => props.role, selectedDeviceId], ([active]) => {
  if (active) void load()
  else { requestGeneration++; disposeChart() }
})
onMounted(() => { void load() })
onBeforeUnmount(() => { requestGeneration++; disposeChart() })
</script>

<template>
  <section class="panel device-evidence" data-device-evidence>
    <header>
      <div><span class="eyebrow">DEVICE TELEMETRY → HEALTH → EVIDENCE</span><h2>设备状态与可解释健康</h2><p>确定性 Demo 温度遥测用于契约验证，不是生产 IoT 数据；振动与预测性维护仍为 NOT_READY。</p></div>
      <label>设备<select v-model="selectedDeviceId" data-device-select><option v-for="device in demoDevices" :key="device.id" :value="device.id">{{ device.label }}</option></select></label>
      <button type="button" :disabled="loading || !props.active" @click="load">{{ loading ? '同步中…' : '刷新证据' }}</button>
    </header>
    <p v-if="error" class="device-evidence__notice is-warning" role="alert">{{ error }}</p>
    <div class="device-evidence__capabilities">
      <span :data-status="telemetry?.status ?? 'UNAVAILABLE'">温度遥测 · {{ telemetry?.status ?? 'UNAVAILABLE' }}</span>
      <span data-status="NOT_READY">振动遥测 · NOT_READY</span>
      <span :data-status="health?.availability ?? 'UNAVAILABLE'">设备健康 · {{ health?.availability ?? 'UNAVAILABLE' }}</span>
      <span data-status="NOT_READY">预测性维护模型 · NOT_READY</span>
    </div>
    <p v-if="loading && !telemetry && !health" class="device-evidence__notice" role="status">正在读取设备证据…</p>
    <div v-else class="device-evidence__grid">
      <section>
        <div class="device-evidence__title"><h3>温度时序</h3><span v-if="telemetry">{{ telemetry.status }} · {{ telemetry.unit }} · {{ series?.freshness }}</span></div>
        <p v-if="telemetry?.status === 'UNAVAILABLE' || !canRender" class="device-evidence__notice" data-telemetry-unavailable>UNAVAILABLE · 当前窗口没有可绘制的真实点，不生成趋势。</p>
        <p v-else-if="telemetry?.status === 'PARTIAL'" class="device-evidence__notice is-warning" data-telemetry-partial>PARTIAL · {{ missingCount }} 个时间点缺失；缺口断线，不补零、不插值。</p>
        <div v-if="canRender" ref="container" class="device-evidence__chart" data-telemetry-chart aria-label="设备温度遥测折线图"></div>
        <footer v-if="telemetry"><span>来源 {{ telemetry.source.system }}</span><span>{{ telemetry.source.datasetKind }}</span><span>截至 {{ timeLabel(telemetry.asOf) }}</span></footer>
      </section>
      <section class="device-evidence__health">
        <div class="device-evidence__title"><h3>Health State</h3><strong :data-health-status="health?.healthStatus ?? 'UNKNOWN'">{{ health?.healthStatus ?? 'UNKNOWN' }}</strong></div>
        <p v-if="!health" class="device-evidence__notice">UNKNOWN · 当前没有足够证据。</p>
        <template v-else>
          <ul><li v-for="reason in health.reasons" :key="reason">{{ reason }}</li></ul>
          <div class="device-evidence__sources"><span v-for="source in health.sources" :key="source.system">{{ source.system }} · {{ source.status }}</span></div>
          <details><summary>{{ health.evidence.length }} 条证据</summary><ol><li v-for="item in health.evidence" :key="item.reference"><strong>{{ item.type }}</strong><span>{{ item.summary }}</span><small>{{ item.reference }} · {{ timeLabel(item.occurredAt) }}</small></li></ol></details>
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.device-evidence { display: grid; gap: 14px; padding: 22px; }
header, .device-evidence__title, footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
header h2 { margin: 6px 0; } header p { max-width: 760px; margin: 0; color: var(--showcase-muted); }
header label { display: grid; gap: 5px; color: var(--showcase-muted); font-size: .72rem; } header select, header button { padding: 8px 10px; color: var(--showcase-ivory); border: 1px solid var(--showcase-border-soft); background: #0a1524; }
header button { color: var(--showcase-cyan); cursor: pointer; } header button:disabled { cursor: not-allowed; opacity: .5; }
.device-evidence__capabilities, .device-evidence__sources, footer { display: flex; flex-wrap: wrap; gap: 8px; }
.device-evidence__capabilities span, .device-evidence__sources span { padding: 5px 8px; color: var(--showcase-muted); border: 1px solid var(--showcase-border-soft); font-size: .7rem; }
.device-evidence__capabilities span[data-status='AVAILABLE'], .device-evidence__capabilities span[data-status='PARTIAL'] { color: var(--showcase-cyan); }
.device-evidence__capabilities span[data-status='NOT_READY'], .device-evidence__capabilities span[data-status='UNAVAILABLE'] { color: var(--showcase-amber); }
.device-evidence__grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(320px, .65fr); gap: 12px; }
.device-evidence__grid > section { min-width: 0; padding: 16px; border: 1px solid var(--showcase-border-soft); background: rgba(6,15,28,.58); }
.device-evidence__title h3 { margin: 0; } .device-evidence__title span, footer { color: var(--showcase-muted); font-size: .72rem; }
.device-evidence__title strong { color: var(--showcase-cyan); letter-spacing: .08em; }
.device-evidence__title strong[data-health-status='CRITICAL'] { color: #ff8c9b; } .device-evidence__title strong[data-health-status='UNKNOWN'] { color: var(--showcase-amber); }
.device-evidence__notice { padding: 10px; color: var(--showcase-muted); border: 1px solid var(--showcase-border-soft); } .device-evidence__notice.is-warning { color: var(--showcase-amber); }
.device-evidence__chart { height: 300px; } footer { justify-content: flex-start; margin-top: 8px; }
.device-evidence__health ul { padding-left: 20px; color: var(--showcase-muted); line-height: 1.6; }
details { margin-top: 14px; color: var(--showcase-muted); } details ol { padding-left: 20px; } details li { margin: 8px 0; } details strong, details span, details small { display: block; } details strong { color: var(--showcase-ivory); } details small { margin-top: 3px; overflow-wrap: anywhere; }
@media (max-width: 980px) { .device-evidence__grid { grid-template-columns: 1fr; } header { align-items: stretch; flex-direction: column; } header button { align-self: flex-start; } }
</style>
