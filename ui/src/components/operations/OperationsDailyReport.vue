<script setup lang="ts">
import { computed, watch } from 'vue'
import type { DemoRole } from '../../types/workflow'
import type { ExecutionTraceLike } from '../../composables/useOperationsAnalysis'
import { useOperationsDailyReport } from '../../composables/useOperationsDailyReport'
import type { OperationsReportTimeResolution } from '../../types/operationsReport'

const props = withDefaults(defineProps<{
  role: DemoRole
  trace?: ExecutionTraceLike
  active?: boolean
  pollIntervalMs?: number
}>(), { active: true })

const reportState = useOperationsDailyReport({ trace: props.trace, pollIntervalMs: props.pollIntervalMs })
const canRun = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')
const statusLabels = { RUNNING: '生成中', COMPLETED: '已完成', PARTIAL: '部分完成', FAILED: '失败' } as const
const sectionLabels = { PENDING: '等待中', RUNNING: '查询中', COMPLETED: '已完成', FAILED: '失败' } as const

function start(): void {
  void reportState.start(props.role)
}

watch(() => props.active, (active) => {
  if (active && reportState.runId.value) props.trace?.subscribe(reportState.runId.value)
})

function timeResolutionLabel(resolution: OperationsReportTimeResolution): string {
  if (resolution.status === 'PARSED') return '已指定'
  if (resolution.status === 'EMPTY') return '空周期'
  return '默认回看'
}

function hasTimeRange(resolution: OperationsReportTimeResolution): boolean {
  return !resolution.empty && Boolean(resolution.fromInclusive && resolution.toExclusive)
}

function formatTimeRange(resolution: OperationsReportTimeResolution): string {
  if (!resolution.fromInclusive || !resolution.toExclusive) return ''
  try {
    const format = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
    return `${format(resolution.fromInclusive)} ~ ${format(resolution.toExclusive)}`
  } catch {
    return `${resolution.fromInclusive} ~ ${resolution.toExclusive}`
  }
}
</script>

<template>
  <section v-if="canRun" class="panel operations-report" data-testid="operations-daily-report">
    <div class="section-heading compact">
      <div><span class="eyebrow">会话级快照</span><h2>运营日报</h2></div>
      <button type="button" class="operations-report__start" :disabled="reportState.busy.value || !props.active" @click="start">
        {{ reportState.busy.value ? '生成中…' : '生成运营日报' }}
      </button>
    </div>
    <p class="operations-report__hint">固定三项只读指标，结果仅保留在本次会话中，不会改写设备或工单。</p>
    <p v-if="reportState.error.value" class="operations-report__error" data-testid="report-error">{{ reportState.error.value }}</p>
    <div v-if="reportState.report.value" class="operations-report__body" data-testid="report-body">
      <div class="operations-report__status">
        <span>日报状态</span><strong>{{ statusLabels[reportState.report.value.status] }}</strong>
      </div>
      <article v-for="section in reportState.report.value.sections" :key="section.id" class="operations-report__section" :data-status="section.status">
        <div class="operations-report__section-head"><div><strong>{{ section.title }}</strong><small>{{ section.question }}</small></div><span>{{ sectionLabels[section.status] }}</span></div>
        <p v-if="section.summary">{{ section.summary }}</p>
        <div v-if="section.status === 'COMPLETED'" class="operations-report__result">
          <span>返回 {{ section.rowCount ?? 0 }} 行{{ section.truncated ? '（已截断）' : '' }}</span>
          <table v-if="section.columns?.length"><thead><tr><th v-for="column in section.columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in section.rows ?? []" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table>
        </div>
        <div
          v-if="section.timeResolution"
          class="operations-report__time-resolution"
          data-testid="section-time-resolution"
          :data-empty="section.timeResolution.empty"
          :data-from="section.timeResolution.fromInclusive ?? undefined"
          :data-to="section.timeResolution.toExclusive ?? undefined"
        >
          <strong>时间范围（{{ timeResolutionLabel(section.timeResolution) }}）</strong>
          <span v-if="hasTimeRange(section.timeResolution)">{{ formatTimeRange(section.timeResolution) }}</span>
          <span>{{ section.timeResolution.explanation }}</span>
        </div>
        <span v-if="section.failureStage" class="operations-report__failure">失败阶段：{{ section.failureStage }}</span>
      </article>
    </div>
    <p v-else class="operations-report__empty">尚未生成日报。点击上方按钮开始一次真实只读汇总。</p>
  </section>
</template>

<style scoped>
.operations-report { margin-bottom: 18px; padding: 24px; }
.operations-report__start { border: 1px solid var(--showcase-cyan); color: var(--showcase-cyan); background: transparent; padding: 9px 14px; cursor: pointer; }
.operations-report__start:disabled { opacity: .55; cursor: not-allowed; }
.operations-report__hint, .operations-report__empty { color: var(--showcase-muted); }
.operations-report__error, .operations-report__failure { color: var(--showcase-danger, #ff8a8a); }
.operations-report__body { display: grid; gap: 10px; }
.operations-report__status { display: flex; justify-content: space-between; border-top: 1px solid var(--showcase-border-soft); padding-top: 12px; }
.operations-report__section { border: 1px solid var(--showcase-border-soft); padding: 14px; background: rgba(12, 17, 26, .42); }
.operations-report__section-head { display: flex; justify-content: space-between; gap: 12px; }
.operations-report__section-head strong, .operations-report__section-head small { display: block; }
.operations-report__section-head small { color: var(--showcase-muted); margin-top: 4px; }
.operations-report__section-head > span { color: var(--showcase-cyan); white-space: nowrap; }
.operations-report__result { color: var(--showcase-muted); font-size: .9rem; }
.operations-report__time-resolution { display: grid; gap: 4px; margin-top: 8px; color: var(--showcase-muted); font-size: .9rem; }
.operations-report table { width: 100%; margin-top: 8px; border-collapse: collapse; }
.operations-report th, .operations-report td { border-bottom: 1px solid var(--showcase-border-soft); padding: 5px; text-align: left; }
@media (max-width: 650px) { .operations-report__section-head { flex-direction: column; } }
</style>
