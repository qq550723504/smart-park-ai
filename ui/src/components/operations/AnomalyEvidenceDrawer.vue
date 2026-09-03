<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import type { DemoRole } from '../../types/workflow'
import type { AnomalyEvidence, AnomalyFilters } from '../../types/operationsAnomaly'
import { getAnomalyEvidence } from '../../services/operationsAnomalyApi'

const props = withDefaults(defineProps<{
  role: DemoRole
  buildingId: string | null
  filters?: AnomalyFilters
  open?: boolean
}>(), { filters: () => ({}), open: false })
const emit = defineEmits<{
  close: []
  'open-analysis': [question: string]
  'open-trace': [runId: string]
}>()

const evidence = ref<AnomalyEvidence | null>(null)
const loading = ref(false)
const error = ref('')
let requestGeneration = 0

async function load(): Promise<void> {
  if (!props.open || !props.buildingId) return
  const generation = ++requestGeneration
  loading.value = true
  error.value = ''
  try {
    const value = await getAnomalyEvidence(props.role, props.buildingId, props.filters)
    if (generation !== requestGeneration) return
    evidence.value = value
  } catch (cause) {
    if (generation !== requestGeneration) return
    evidence.value = null
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

function close(): void {
  requestGeneration += 1
  evidence.value = null
  error.value = ''
  emit('close')
}

function value(item: Record<string, unknown>, key: string): string {
  const raw = item[key]
  return raw == null ? '—' : String(raw)
}

watch([() => props.open, () => props.buildingId, () => props.role], ([open]) => {
  if (open) void load()
})
onMounted(() => { void load() })
</script>

<template>
  <aside v-if="props.open" class="panel anomaly-evidence" data-anomaly-evidence aria-label="异常证据链">
    <div class="section-heading compact">
      <div><span class="eyebrow">异常下钻 · 只读证据</span><h2>{{ props.buildingId }} 异常证据链</h2></div>
      <button type="button" class="anomaly-evidence__close" @click="close">关闭</button>
    </div>
    <p v-if="loading && !evidence" class="anomaly-evidence__state">正在读取楼宇证据…</p>
    <p v-else-if="error" class="anomaly-evidence__state anomaly-evidence__state--error">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="!evidence" class="anomaly-evidence__state">暂无证据数据。</p>
    <template v-else>
      <p class="anomaly-evidence__window">数据窗口：{{ evidence.window.from }} ~ {{ evidence.window.to }} · 快照：{{ evidence.asOf }}</p>
      <div class="anomaly-evidence__status"><span v-for="(status, domain) in evidence.domainStatus" :key="domain" :data-domain-status="status">{{ domain }}：{{ status }}</span></div>
      <p v-if="evidence.domainStatus.alerts === 'UNAVAILABLE'" class="anomaly-evidence__state anomaly-evidence__state--warning">告警数据暂不可用</p>
      <p v-if="evidence.domainStatus.devices === 'UNAVAILABLE'" class="anomaly-evidence__state anomaly-evidence__state--warning">设备数据暂不可用</p>
      <p v-if="evidence.domainStatus.energy === 'UNAVAILABLE'" class="anomaly-evidence__state anomaly-evidence__state--warning">能耗数据暂不可用</p>
      <section class="anomaly-evidence__section"><h3>告警证据</h3><article v-for="item in evidence.alerts" :key="value(item, 'alertId')"><strong>{{ value(item, 'category') }} · {{ value(item, 'riskLevel') }}</strong><small>{{ value(item, 'occurredAt') }} · {{ value(item, 'status') }}</small><p>{{ value(item, 'redactedSummary') }}</p><button v-if="value(item, 'executionRunId') !== '—'" type="button" :data-evidence-trace="value(item, 'executionRunId')" @click="emit('open-trace', value(item, 'executionRunId'))">打开执行轨迹</button></article><small v-if="evidence.alerts.length === 0">暂无告警引用</small></section>
      <section class="anomaly-evidence__section"><h3>设备证据</h3><article v-for="item in evidence.devices" :key="value(item, 'deviceId')"><strong>{{ value(item, 'deviceType') }} · {{ value(item, 'status') }}</strong><small>{{ value(item, 'deviceId') }} · {{ value(item, 'snapshotAt') }}</small><p>{{ value(item, 'redactedSummary') }}</p></article><small v-if="evidence.devices.length === 0">暂无设备引用</small></section>
      <section class="anomaly-evidence__section"><h3>能耗证据</h3><article v-for="item in evidence.energy" :key="value(item, 'meterId') + value(item, 'measuredAt')"><strong>偏差 {{ value(item, 'deviationPct') }}%</strong><small>{{ value(item, 'meterId') }} · {{ value(item, 'measuredAt') }}</small><p>{{ value(item, 'redactedSummary') }}</p></article><small v-if="evidence.energy.length === 0">暂无能耗引用</small></section>
      <div class="anomaly-evidence__actions"><button type="button" @click="emit('open-analysis', '过去7天各楼宇告警数量排行')">进入楼宇分析</button></div>
    </template>
  </aside>
</template>

<style scoped>
.anomaly-evidence { position: relative; margin-bottom: 18px; padding: 24px; border-color: var(--showcase-cyan); }
.anomaly-evidence__close { border: 1px solid var(--showcase-border-soft); color: var(--showcase-muted); background: transparent; padding: 7px 11px; cursor: pointer; }
.anomaly-evidence__window, .anomaly-evidence__state { color: var(--showcase-muted); }
.anomaly-evidence__state--error { color: var(--showcase-danger, #ff8a8a); }
.anomaly-evidence__state--warning { color: #f4c46b; }
.anomaly-evidence__state button { border: 0; color: var(--showcase-cyan); background: transparent; cursor: pointer; }
.anomaly-evidence__status { display: flex; gap: 8px; flex-wrap: wrap; color: var(--showcase-muted); font-size: .78rem; }
.anomaly-evidence__status span { padding: 4px 8px; border: 1px solid var(--showcase-border-soft); }
.anomaly-evidence__status span[data-domain-status="UNAVAILABLE"] { color: #f4c46b; }
.anomaly-evidence__section { margin-top: 14px; border-top: 1px solid var(--showcase-border-soft); padding-top: 12px; }
.anomaly-evidence__section h3 { margin: 0 0 8px; }
.anomaly-evidence__section article { padding: 10px 0; border-bottom: 1px solid var(--showcase-border-soft); }
.anomaly-evidence__section strong, .anomaly-evidence__section small, .anomaly-evidence__section p { display: block; }
.anomaly-evidence__section small, .anomaly-evidence__section p { color: var(--showcase-muted); }
.anomaly-evidence__section p { margin: 5px 0; font-size: .9rem; }
.anomaly-evidence__section button, .anomaly-evidence__actions button { border: 1px solid var(--showcase-cyan); color: var(--showcase-cyan); background: transparent; padding: 6px 10px; cursor: pointer; }
.anomaly-evidence__actions { margin-top: 14px; }
</style>
