<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { cancelOrchestration, getOrchestration, OrchestrationApiError, startOrchestration } from '../../services/orchestrationApi'
import type { OrchestrationInput, OrchestrationRun } from '../../types/orchestration'
import type { DemoRole } from '../../types/workflow'

const props = withDefaults(defineProps<{ role: DemoRole; active?: boolean; available?: boolean }>(), {
  active: true,
  available: false,
})
const emit = defineEmits<{ 'open-trace': [runId: string] }>()

const run = ref<OrchestrationRun | null>(null)
const loading = ref(false)
const restoring = ref(false)
const error = ref('')
let pollHandle: number | null = null
let pollFailures = 0
let generation = 0
let tracedRunId: string | null = null
let disposed = false

const storageKey = computed(() => `smartpark.orchestration.last.${props.role}`)
const pendingKey = computed(() => `smartpark.orchestration.pending.${props.role}`)
const terminal = computed(() => run.value && ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(run.value.status))
const canStart = computed(() => props.role !== 'CUSTOMER_AGENT'
  && props.active && props.available && !loading.value && !restoring.value && (!run.value || terminal.value))

const labels: Record<string, string> = {
  'collect-context': '收集上下文',
  'operations-analysis': '运营分析',
  'energy-time-series': '能耗分析',
  'expert-collaboration': '专家协作',
  'security-review': '安防研判',
  'alert-workflow': '处置工作流',
  'final-summary': '形成结论',
}

function stateLabel(status: string) {
  return ({ RUNNING: '执行中', COMPLETED: '已完成', PARTIAL: '部分完成', FAILED: '失败',
    WAITING_APPROVAL: '等待人工审批', CANCELLED: '已取消', PENDING: '待执行', SKIPPED: '已跳过',
    BLOCKED: '已阻断' } as Record<string, string>)[status] ?? status
}

function nextKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `orchestration-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function launchInput(): OrchestrationInput {
  const canApproveSecurity = ['APPROVER', 'ADMIN'].includes(props.role)
  const canAct = ['OPERATOR', 'APPROVER', 'ADMIN'].includes(props.role)
  return {
    question: canApproveSecurity
      ? '研判 B1 最近5天能耗异常与关联安全风险，并在必要时进入告警处置'
      : '研判 B1 最近5天能耗异常并形成跨域建议',
    alertId: canAct ? 'ALT-ORCH-ENERGY-B1-001' : null,
    buildingIds: ['B1'],
    energyRelated: true,
    crossDomain: true,
    securityRelated: canApproveSecurity,
    requestAction: canAct,
  }
}

function remember(runId: string): void {
  localStorage.setItem(storageKey.value, runId)
}

function trace(current: OrchestrationRun): void {
  if (tracedRunId === current.traceId) return
  tracedRunId = current.traceId
  emit('open-trace', current.traceId)
}

function stopPolling(): void {
  if (pollHandle !== null) window.clearInterval(pollHandle)
  pollHandle = null
}

function startPolling(): void {
  stopPolling()
  if (disposed || !(run.value?.runId ?? localStorage.getItem(storageKey.value))
      || terminal.value || !props.active) return
  const delay = Math.min(1000 * (2 ** pollFailures), 10_000)
  pollHandle = window.setTimeout(async () => {
    pollHandle = null
    await refresh()
    startPolling()
  }, delay)
}

async function refresh(): Promise<void> {
  const runId = run.value?.runId ?? localStorage.getItem(storageKey.value)
  if (!runId || !props.active) return
  const requestGeneration = generation
  try {
    const current = await getOrchestration(props.role, runId)
    if (requestGeneration !== generation) return
    if (run.value && current.revision < run.value.revision) return
    pollFailures = 0
    error.value = ''
    run.value = current
    restoring.value = false
    trace(current)
    if (['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(current.status)) stopPolling()
  } catch (cause) {
    if (requestGeneration !== generation) return
    if (cause instanceof OrchestrationApiError && cause.status === 404) {
      localStorage.removeItem(storageKey.value)
      run.value = null
      restoring.value = false
      pollFailures = 0
      error.value = '上次编排记录已失效，请重新启动'
      stopPolling()
      return
    }
    pollFailures = Math.min(pollFailures + 1, 4)
    error.value = cause instanceof Error ? cause.message : '无法查询编排状态'
  }
}

async function start(): Promise<void> {
  if (!canStart.value) return
  generation += 1
  const requestGeneration = generation
  loading.value = true
  error.value = ''
  const requestPendingKey = pendingKey.value
  const key = localStorage.getItem(requestPendingKey) || nextKey()
  let acceptedRunId: string | null = null
  localStorage.setItem(requestPendingKey, key)
  try {
    const accepted = await startOrchestration(props.role, key, launchInput())
    if (requestGeneration !== generation) return
    acceptedRunId = accepted.runId
    remember(accepted.runId)
    restoring.value = true
    localStorage.removeItem(requestPendingKey)
    const startedRun = await getOrchestration(props.role, accepted.runId)
    if (requestGeneration !== generation) return
    run.value = startedRun
    restoring.value = false
    trace(run.value)
    startPolling()
  } catch (cause) {
    if (cause instanceof OrchestrationApiError && cause.status === 409) {
      localStorage.removeItem(requestPendingKey)
    }
    if (requestGeneration === generation) {
      error.value = cause instanceof Error ? cause.message : '编排启动失败'
      if (acceptedRunId) startPolling()
    }
  } finally {
    if (requestGeneration === generation) loading.value = false
  }
}

async function cancel(): Promise<void> {
  if (!run.value || terminal.value) return
  generation += 1
  const requestGeneration = generation
  const runId = run.value.runId
  stopPolling()
  loading.value = true
  error.value = ''
  try {
    const cancelled = await cancelOrchestration(props.role, runId)
    if (requestGeneration !== generation) return
    run.value = cancelled
    stopPolling()
  } catch (cause) {
    if (requestGeneration === generation) {
      error.value = cause instanceof Error ? cause.message : '取消编排失败'
      startPolling()
    }
  } finally {
    if (requestGeneration === generation) loading.value = false
  }
}

watch(() => [props.active, props.role, props.available] as const, ([active]) => {
  generation += 1
  stopPolling()
  pollFailures = 0
  run.value = null
  loading.value = false
  error.value = ''
  tracedRunId = null
  restoring.value = Boolean(active && localStorage.getItem(storageKey.value))
  if (active) void refresh().then(startPolling)
}, { immediate: true })

onBeforeUnmount(() => {
  generation += 1
  disposed = true
  stopPolling()
})
</script>

<template>
  <section class="orchestration-panel panel" data-orchestration-panel>
    <header>
      <div><span class="eyebrow">JOINT ANOMALY ASSESSMENT</span><h2>园区异常联合研判</h2></div>
      <strong :data-run-status="run?.status ?? 'IDLE'">{{ run ? stateLabel(run.status) : props.available ? '可启动' : '未启用' }}</strong>
    </header>
    <p v-if="!run">{{ props.available ? '按当前角色与实时 capability 执行已有分析、证据和处置能力；不会强制运行不适用的 Agent。' : '必需的 Operations Analysis 当前未启用，完整研判不可启动。' }}</p>
    <p v-if="error" class="orchestration-panel__error" role="alert">{{ error }}</p>
    <ol v-if="run" class="orchestration-panel__steps">
      <li v-for="step in run.steps" :key="step.id" :data-step-status="step.status">
        <span>{{ labels[step.id] ?? step.id }}</span><strong>{{ stateLabel(step.status) }}</strong>
        <small>{{ step.outputSummary || step.failureReason || step.inputSummary || '等待前置步骤' }}</small>
        <code v-if="step.runReference">{{ step.runReference }}</code>
      </li>
    </ol>
    <section v-if="run?.result" class="orchestration-panel__result">
      <h3>最终结论</h3><p>{{ run.result.conclusion }}</p>
      <div v-if="run.result.recommendations.length"><strong>建议动作：</strong><ul><li v-for="item in run.result.recommendations" :key="item">{{ item }}</li></ul></div>
      <p v-if="run.result.partialReasons.length"><strong>未完成项：</strong>{{ run.result.partialReasons.join('；') }}</p>
      <details v-if="run.result.evidenceReferences.length"><summary>证据引用（{{ run.result.evidenceReferences.length }}）</summary><ul><li v-for="item in run.result.evidenceReferences" :key="item"><code>{{ item }}</code></li></ul></details>
    </section>
    <footer>
      <button type="button" data-start-orchestration :disabled="!canStart" @click="start">{{ loading ? '处理中…' : run && terminal ? '启动新一轮' : '运行完整研判' }}</button>
      <button v-if="run && !terminal" type="button" data-cancel-orchestration :disabled="loading" @click="cancel">取消后续步骤</button>
      <button v-if="run" type="button" @click="emit('open-trace', run.traceId)">查看执行轨迹</button>
    </footer>
  </section>
</template>

<style scoped>
.orchestration-panel { display: grid; gap: 16px; padding: 24px; }
.orchestration-panel header { display: flex; justify-content: space-between; gap: 16px; align-items: start; }
.orchestration-panel h2 { margin: 6px 0 0; }
.orchestration-panel header > strong { color: var(--showcase-cyan); }
.orchestration-panel header > strong[data-run-status='PARTIAL'], .orchestration-panel header > strong[data-run-status='WAITING_APPROVAL'] { color: var(--showcase-amber); }
.orchestration-panel header > strong[data-run-status='FAILED'], .orchestration-panel header > strong[data-run-status='CANCELLED'] { color: #ff8f8f; }
.orchestration-panel__steps { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin: 0; padding: 0; list-style: none; }
.orchestration-panel__steps li { display: grid; gap: 5px; min-width: 0; padding: 12px; border: 1px solid var(--showcase-border-soft); background: rgba(7, 16, 29, .58); }
.orchestration-panel__steps li[data-step-status='RUNNING'] { border-color: var(--showcase-cyan); }
.orchestration-panel__steps li[data-step-status='FAILED'], .orchestration-panel__steps li[data-step-status='BLOCKED'] { border-color: rgba(255, 100, 100, .55); }
.orchestration-panel__steps strong { color: var(--showcase-cyan); font-size: .72rem; }
.orchestration-panel__steps small { color: var(--showcase-muted); line-height: 1.45; }
.orchestration-panel__steps code { overflow: hidden; color: #b8a5ff; text-overflow: ellipsis; }
.orchestration-panel__result { padding: 16px; border-left: 3px solid var(--showcase-cyan); background: rgba(19, 83, 112, .14); }
.orchestration-panel__result h3, .orchestration-panel__result p { margin: 0 0 8px; }
.orchestration-panel__error { color: #ffb2b2; }
.orchestration-panel footer { display: flex; flex-wrap: wrap; gap: 8px; }
.orchestration-panel footer button { padding: 10px 14px; color: var(--showcase-ivory); border: 1px solid rgba(112, 232, 255, .32); background: rgba(19, 83, 112, .22); cursor: pointer; }
.orchestration-panel footer button:disabled { cursor: not-allowed; opacity: .45; }
@media (max-width: 1200px) { .orchestration-panel__steps { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .orchestration-panel__steps { grid-template-columns: 1fr; } }
</style>
