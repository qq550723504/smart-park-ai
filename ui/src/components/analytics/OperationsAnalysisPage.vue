<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  useOperationsAnalysis,
  type AnalysisStartCallbacks,
  type ExecutionTraceLike,
} from '../../composables/useOperationsAnalysis'
import { useGuidedLaunch } from '../../composables/useGuidedLaunch'
import AnalyticsChart from './AnalyticsChart.vue'
import type { GuidedLaunchUpdate, ScenarioLaunchRequest } from '../../types/workbench'

const props = withDefaults(defineProps<{
  trace?: ExecutionTraceLike
  pollIntervalMs?: number
  active?: boolean
  launchRequest?: ScenarioLaunchRequest | null
}>(), { active: true, launchRequest: null })
const emit = defineEmits<{
  'run-started': [runId: string]
  'launch-status': [update: GuidedLaunchUpdate]
}>()

const question = ref('')
const recommendedGroups = [
  {
    label: '能耗与空间',
    questions: [
      '过去5天各楼宇能耗',
      '能耗总量',
      '各楼宇能耗对比',
      '过去5天按小时能耗趋势',
      '过去5天各楼宇能耗排行',
      '过去5天各楼宇能耗热力图',
      '过去5天按日期能耗日历热力图',
      '过去5天能耗目标完成率',
      '过去5天各楼宇能耗与占用人数关系',
      '过去5天各楼宇能耗空间分布',
      '过去5天各楼宇分时能耗堆叠图',
    ],
  },
  {
    label: '告警与设备',
    questions: ['告警数量', '高风险告警数量', '设备离线数'],
  },
  {
    label: '停车资源',
    questions: ['停车进场量'],
  },
]
const analysis = useOperationsAnalysis({
  ...(props.trace ? { trace: props.trace } : {}),
  ...(props.pollIntervalMs != null ? { pollIntervalMs: props.pollIntervalMs } : {}),
})
const chosenMetrics = ref<string[]>([])
let cancelGuidedAnalysisStart: (() => void) | null = null
let pendingGuidedAnalysisStart: { promise: Promise<void>; cancel: () => void; question: string } | null = null
let returnedFromShowcase = !props.active

onScopeDispose(() => {
  cancelGuidedAnalysisStart?.()
})

watch(() => props.active, (active) => {
  if (!active) {
    returnedFromShowcase = true
    return
  }
  // The analysis composable keeps an accepted run alive while its page is
  // hidden. Reclaim the shared execution trace when the page becomes active.
  if (analysis.runId.value) props.trace?.subscribe(analysis.runId.value)
})

function launchAnalysis(callbacks?: AnalysisStartCallbacks): void {
  void analysis.submit(question.value, {
    onAccepted: (runId) => {
      emit('run-started', runId)
      callbacks?.onAccepted?.(runId)
    },
    onFailed: (cause) => callbacks?.onFailed?.(cause),
  })
}

function submitAnalysisForm(): void {
  cancelGuidedAnalysisStart?.()
  launchAnalysis()
}

function startGuidedAnalysis(): Promise<void> {
  cancelGuidedAnalysisStart?.()
  const submittedQuestion = question.value.trim()
  let pending: { promise: Promise<void>; cancel: () => void; question: string }
  const promise = new Promise<void>((resolve, reject) => {
    let settled = false
    const clearPending = () => {
      if (pendingGuidedAnalysisStart === pending) pendingGuidedAnalysisStart = null
    }
    const cancel = () => {
      if (settled) return
      settled = true
      if (cancelGuidedAnalysisStart === cancel) cancelGuidedAnalysisStart = null
      clearPending()
      reject(new Error('运营分析启动已取消'))
    }
    const accept = () => {
      if (settled) return
      settled = true
      if (cancelGuidedAnalysisStart === cancel) cancelGuidedAnalysisStart = null
      clearPending()
      resolve()
    }
    const fail = (cause: Error) => {
      if (settled) return
      settled = true
      if (cancelGuidedAnalysisStart === cancel) cancelGuidedAnalysisStart = null
      clearPending()
      reject(cause)
    }
    cancelGuidedAnalysisStart = cancel
    launchAnalysis({ onAccepted: accept, onFailed: fail })
  })
  pending = { promise, cancel: cancelGuidedAnalysisStart!, question: submittedQuestion }
  pendingGuidedAnalysisStart = pending
  return promise
}

useGuidedLaunch({
  active: () => props.active,
  request: () => props.launchRequest,
  scenarioId: 'OPERATIONS_ANALYSIS',
  start: async (request) => {
    const guidedQuestion = request.launchInput?.question?.trim()
    if (!guidedQuestion) throw new Error('运营分析演示配置无效')
    const preservingExistingRun = returnedFromShowcase
      && analysis.runId.value
      && (analysis.phase.value === 'running' || analysis.phase.value === 'clarification')
    const preservingPendingRun = returnedFromShowcase
      && !analysis.runId.value
      && analysis.phase.value === 'running'
      && pendingGuidedAnalysisStart?.question === guidedQuestion
    returnedFromShowcase = false
    if (preservingExistingRun) {
      return { state: 'started', message: '已保留当前运营分析，请继续查看' }
    }
    if (preservingPendingRun) {
      question.value = guidedQuestion
      await pendingGuidedAnalysisStart!.promise
      return { state: 'started', message: '已保留当前运营分析，请继续查看' }
    }
    question.value = guidedQuestion
    await startGuidedAnalysis()
    return { state: 'started', message: '运营分析已启动' }
  },
  onUpdate: (update) => emit('launch-status', update),
})

function selectRecommendedQuestion(value: string): void {
  question.value = value
}

function resume(): void {
  analysis.selections.value = chosenMetrics.value.map((metric, index) => ({
    term: `澄清-${index + 1}`,
    metric,
  }))
  void analysis.clarify()
}

const dto = computed(() => analysis.dto.value)

function formatRange(t: { fromInclusive: string | null; toExclusive: string | null }) {
  try {
    const fmt = (iso: string) => new Date(iso).toLocaleString('zh-CN', { hour12: false })
    return `${fmt(t.fromInclusive ?? '')} ~ ${fmt(t.toExclusive ?? '')}`
  } catch {
    return `${t.fromInclusive ?? ''} ~ ${t.toExclusive ?? ''}`
  }
}
const columns = computed(() => dto.value?.columns ?? [])
const rows = computed(() => dto.value?.rows ?? [])

const METRIC_OPTIONS = [
  { value: 'alert_count', label: '告警数量 (alert_count)' },
  { value: 'high_risk_alert_count', label: '高风险告警数量 (high_risk_alert_count)' },
  { value: 'energy_kwh', label: '能耗 (energy_kwh)' },
  { value: 'parking_entries', label: '停车进场量 (parking_entries)' },
]

// Each question renders the candidates the backend actually offered; the
// hard-coded list is only a fallback for responses that predate the contract.
function optionsFor(index: number): string[] {
  const fromBackend = dto.value?.clarificationOptions?.[index]
  if (fromBackend && fromBackend.length > 0) return fromBackend
  return METRIC_OPTIONS.map((option) => option.value)
}

function metricLabel(value: string): string {
  return METRIC_OPTIONS.find((option) => option.value === value)?.label ?? value
}

function onMetricChange(index: number, event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  while (chosenMetrics.value.length <= index) chosenMetrics.value.push('alert_count')
  chosenMetrics.value[index] = value
}

// Each <select> visibly defaults to its first candidate; initialize the model
// with the same value so "continue" submits the displayed defaults even
// before any change event fires.
watch(
  () => (dto.value?.status === 'NEEDS_CLARIFICATION' ? dto.value : null),
  (current) => {
    const count = current?.clarificationQuestions?.length ?? 0
    if (count > 0) {
      chosenMetrics.value = Array.from({ length: count }, (_, index) => optionsFor(index)[0])
    }
  },
)
</script>

<template>
  <section class="analytics-page panel" aria-label="自然语言运营分析">
    <div class="section-heading">
      <div>
        <span class="eyebrow">自然语言 · 运营分析</span>
        <h2>用一句话完成真实只读分析</h2>
      </div>
    </div>

    <form class="question-row" @submit.prevent="submitAnalysisForm">
      <input v-model="question" type="text" placeholder="例如：上周各楼宇能耗对比、高风险告警有多少…" aria-label="分析问题" />
      <button type="submit" :disabled="analysis.phase.value === 'running'">开始分析</button>
    </form>
    <div class="analytics-presets" role="group" aria-label="推荐问题">
      <span>试试这些问题</span>
      <section v-for="group in recommendedGroups" :key="group.label" class="analytics-preset-group" :aria-label="group.label">
        <strong>{{ group.label }}</strong>
        <div class="analytics-preset-group__items">
          <button
            v-for="preset in group.questions"
            :key="preset"
            type="button"
            :disabled="analysis.phase.value === 'running'"
            @click="selectRecommendedQuestion(preset)"
          >
            {{ preset }}
          </button>
        </div>
      </section>
    </div>
    <p v-if="analysis.error.value" class="analytics-error" data-testid="analytics-error">{{ analysis.error.value }}</p>

    <p v-if="analysis.phase.value === 'running'" class="analytics-running" data-testid="analytics-running">
      正在执行真实只读查询，事件将同步到右侧执行轨迹…
    </p>

    <section v-if="dto?.status === 'NEEDS_CLARIFICATION'" class="clarify-panel" data-testid="clarify-panel">
      <h3>需要澄清指标口径</h3>
      <ul>
        <li v-for="q in dto.clarificationQuestions ?? []" :key="q">{{ q }}</li>
      </ul>
      <div v-for="(_, index) in dto.clarificationQuestions ?? []" :key="'sel-' + index" class="selection-row">
        <label :for="'metric-' + index">指标 {{ index + 1 }}</label>
        <select :id="'metric-' + index" @change="onMetricChange(index, $event)">
          <option v-for="value in optionsFor(index)" :key="value" :value="value">{{ metricLabel(value) }}</option>
        </select>
      </div>
      <button data-testid="resume-button" @click="resume">按所选口径继续</button>
    </section>

    <section v-if="dto?.status === 'COMPLETED'" class="result-panel" data-testid="result-panel">
      <div class="result-meta">
        <span class="badge-real">真实只读查询</span>
        <span>返回 {{ dto.rowCount }} 行{{ dto.truncated ? '（已按上限截断）' : '' }}</span>
      </div>
      <table v-if="columns.length" class="result-table">
        <thead><tr><th v-for="c in columns" :key="c">{{ c }}</th></tr></thead>
        <tbody>
          <tr v-for="(row, rIndex) in rows" :key="rIndex">
            <td v-for="(cell, cIndex) in row" :key="cIndex">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
      <AnalyticsChart :chart="analysis.chart.value" :columns="columns" :rows="rows" />
      <div
        v-if="dto.timeResolution"
        class="time-resolution-card"
        data-testid="time-resolution"
        :data-empty="dto.timeResolution.empty"
      >
        <strong>时间范围（{{ dto.timeResolution.status === 'PARSED' ? '已指定' : dto.timeResolution.status === 'EMPTY' ? '空' : '未指定' }}）</strong>
        <span v-if="!dto.timeResolution.empty && dto.timeResolution.fromInclusive">
          {{ formatRange(dto.timeResolution) }}
        </span>
        <span>{{ dto.timeResolution.explanation }}</span>
      </div>
      <p v-if="dto.summary" class="result-summary">{{ dto.summary }}</p>
    </section>

    <section v-if="dto?.status === 'FAILED'" class="failed-panel" data-testid="failed-panel">
      <strong>本次分析已终止。</strong>
      失败阶段：{{ dto.failureStage ?? '未知' }}。可在上方修改问题后重试。
    </section>
  </section>
</template>

<style scoped src="./analytics.css"></style>
