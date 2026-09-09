<script setup lang="ts">
import { computed, watch } from 'vue'
import type { DemoRole } from '../../types/workflow'
import type { ExecutionTraceLike } from '../../composables/useOperationsAnalysis'
import { useOperationsDailyReport } from '../../composables/useOperationsDailyReport'
import type { OperationsReportTimeResolution } from '../../types/operationsReport'

const props = withDefaults(defineProps<{ role: DemoRole; trace?: ExecutionTraceLike; active?: boolean; available?: boolean; pollIntervalMs?: number }>(), { active: true, available: true })
const state = useOperationsDailyReport({ trace: props.trace, pollIntervalMs: props.pollIntervalMs })
const canUse = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')
const statusLabels = { REQUESTED: '已请求', GENERATING: '生成中', COMPLETED: '已完成', PARTIAL: '部分完成', FAILED: '失败' } as const
const sectionLabels = { PENDING: '等待中', RUNNING: '查询中', COMPLETED: '已完成', UNAVAILABLE: '不可用', FAILED: '失败' } as const

function start(): void { void state.start(props.role) }
function open(reportId: string): void { void state.open(reportId, props.role) }
function download(reportId: string): void { void state.download(reportId, props.role) }
function loadMore(): void { void state.loadHistory(props.role, true) }
function format(value: string | null | undefined, timezone: string): string {
  if (!value) return '—'
  const instant = new Date(value)
  try {
    return instant.toLocaleString('zh-CN', { hour12: false, timeZone: timezone })
  } catch (failure) {
    if (!(failure instanceof RangeError)) throw failure
    return `${instant.toISOString()} (${timezone})`
  }
}
function resolution(value: OperationsReportTimeResolution | Record<string, never>): OperationsReportTimeResolution | null {
  return 'status' in value ? value as OperationsReportTimeResolution : null
}
function timeResolutionLabel(value: OperationsReportTimeResolution): string {
  if (value.status === 'PARSED') return '已指定'
  if (value.status === 'EMPTY') return '空周期'
  return '默认回看'
}

watch(() => props.role, (role) => {
  state.reset()
  if (props.active && canUse.value) void state.loadHistory(role)
}, { immediate: true })

watch(() => props.active, (active) => {
  if (!active) return
  if (canUse.value) void state.loadHistory(props.role)
  if (state.runId.value) props.trace?.subscribe(state.runId.value, props.role)
})
</script>

<template>
  <section v-if="canUse" class="panel operations-report" data-testid="operations-daily-report">
    <div class="section-heading compact">
      <div><span class="eyebrow">AI REPORT / DURABLE SNAPSHOT</span><h2>AI 运营报告</h2></div>
      <button type="button" class="operations-report__start" :disabled="state.busy.value || !props.active || !props.available" data-generate-report @click="start">
        {{ state.busy.value ? '生成中…' : '生成日报' }}
      </button>
    </div>
    <p class="operations-report__hint">历史报告保存生成时的结构化结果、Source As Of 与 evidence；查看和下载不会重新执行分析。</p>
    <p v-if="!props.available" class="operations-report__hint">当前分析链路未启用，只能查看和下载已有报告。</p>
    <p v-if="state.error.value" class="operations-report__error" data-testid="report-error">{{ state.error.value }}</p>

    <div class="operations-report__history" data-testid="report-history">
      <div class="operations-report__history-title"><strong>最近报告</strong><span>{{ state.historyLoading.value ? '加载中…' : `${state.reports.value.length} 份` }}</span></div>
      <p v-if="!state.historyLoading.value && state.reports.value.length === 0" class="operations-report__empty">暂无历史报告。</p>
      <article v-for="item in state.reports.value" :key="item.reportId" class="operations-report__history-row" :data-status="item.status">
        <span>{{ format(item.createdAt, item.timezone) }}</span><strong>{{ statusLabels[item.status] }}</strong>
        <button type="button" @click="open(item.reportId)">查看</button>
        <button type="button" :disabled="!item.downloadAvailable" @click="download(item.reportId)">下载</button>
      </article>
      <button v-if="state.historyHasNext.value" type="button" data-load-more-reports :disabled="state.historyLoading.value" @click="loadMore">
        加载更多（已显示 {{ state.reports.value.length }} / {{ state.historyTotal.value }}）
      </button>
    </div>

    <div v-if="state.report.value" class="operations-report__body" data-testid="report-body">
      <div class="operations-report__status"><span>报告状态</span><strong>{{ statusLabels[state.report.value.status] }}</strong></div>
      <dl class="operations-report__metadata">
        <div><dt>生成时间</dt><dd>{{ format(state.report.value.completedAt ?? state.report.value.createdAt, state.report.value.timezone) }}</dd></div>
        <div><dt>数据窗口</dt><dd>{{ format(state.report.value.timeWindow.fromInclusive, state.report.value.timezone) }} — {{ format(state.report.value.timeWindow.toExclusive, state.report.value.timezone) }}</dd></div>
        <div><dt>Source As Of</dt><dd>{{ format(state.report.value.asOf, state.report.value.timezone) }}</dd></div>
        <div><dt>Trace</dt><dd><button type="button" @click="props.trace?.subscribe(state.report.value!.traceId, props.role)">{{ state.report.value.traceId }}</button></dd></div>
      </dl>
      <p v-if="state.report.value.summary">{{ state.report.value.summary }}</p>
      <article v-for="section in state.report.value.sections" :key="section.sectionId" class="operations-report__section" :data-status="section.status">
        <div class="operations-report__section-head"><div><strong>{{ section.title }}</strong><small>{{ section.question }}</small></div><span>{{ sectionLabels[section.status] }}</span></div>
        <p v-if="section.summary">{{ section.summary }}</p>
        <div v-if="section.status === 'COMPLETED'" class="operations-report__result">
          <span>快照包含 {{ section.rowCount }} 行{{ section.truncated ? '（已截断）' : '' }}</span>
          <table v-if="section.columns.length"><thead><tr><th v-for="column in section.columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in section.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table>
        </div>
        <div v-if="resolution(section.timeResolution)" class="operations-report__time-resolution" data-testid="section-time-resolution" :data-empty="resolution(section.timeResolution)?.empty" :data-from="resolution(section.timeResolution)?.fromInclusive ?? undefined" :data-to="resolution(section.timeResolution)?.toExclusive ?? undefined">
          <strong>时间范围（{{ timeResolutionLabel(resolution(section.timeResolution)!) }}）</strong>
          <span>{{ resolution(section.timeResolution)?.explanation }}</span>
        </div>
        <span v-if="section.partialReason || section.failureReason" class="operations-report__failure">{{ section.partialReason ?? section.failureReason }}</span>
      </article>
      <div class="operations-report__evidence">
        <strong>Evidence / Source</strong>
        <span v-for="evidence in state.report.value.evidence" :key="`${evidence.metric}-${evidence.runReference}`">{{ evidence.sourceSystem }} · {{ evidence.metric }} · {{ evidence.entity }} · {{ format(evidence.observationTime, state.report.value.timezone) }} · run {{ evidence.runReference }} · {{ evidence.summary }}</span>
        <span v-for="source in state.report.value.sourceReferences" :key="`${source.sourceSystem}-${source.metric}`">{{ source.sourceSystem }} · {{ source.metric }} · {{ source.unit }} · {{ source.status }} · as of {{ format(source.asOf, state.report.value.timezone) }}</span>
      </div>
      <button v-if="state.report.value.downloadAvailable" type="button" data-download-current @click="download(state.report.value.reportId)">下载 Markdown 快照</button>
    </div>
  </section>
</template>

<style scoped>
.operations-report { display: grid; gap: 16px; padding: 26px; overflow: hidden; background: linear-gradient(135deg, rgba(8, 24, 39, .86), rgba(13, 15, 32, .78)); }
.operations-report__start { border: 1px solid var(--showcase-cyan); color: #041019; background: linear-gradient(100deg, #58dfff, #829cff); padding: 9px 14px; cursor: pointer; font-weight: 800; }
.operations-report button:disabled { opacity: .55; cursor: not-allowed; }
.operations-report__hint, .operations-report__empty { color: var(--showcase-muted); }
.operations-report__error, .operations-report__failure { color: var(--showcase-danger, #ff8a8a); }
.operations-report__history, .operations-report__body, .operations-report__evidence { display: grid; gap: 10px; }
.operations-report__history-title, .operations-report__history-row, .operations-report__status { display: grid; grid-template-columns: 1fr auto auto auto; align-items: center; gap: 10px; border-top: 1px solid var(--showcase-border-soft); padding-top: 10px; }
.operations-report__history-row strong { color: var(--showcase-cyan); }
.operations-report__history-row button, .operations-report__metadata button, [data-download-current] { color: var(--showcase-cyan); border: 1px solid var(--showcase-border-soft); background: transparent; padding: 6px 9px; cursor: pointer; }
.operations-report__metadata { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 0; }
.operations-report__metadata div { padding: 10px; border: 1px solid var(--showcase-border-soft); }
.operations-report__metadata dt { color: var(--showcase-muted); font-size: .75rem; }.operations-report__metadata dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.operations-report__section { border: 1px solid rgba(112, 232, 255, .14); padding: 16px; background: rgba(5, 14, 27, .58); }
.operations-report__section-head { display: flex; justify-content: space-between; gap: 12px; }.operations-report__section-head strong, .operations-report__section-head small { display: block; }.operations-report__section-head small { color: var(--showcase-muted); margin-top: 4px; }.operations-report__section-head > span { color: var(--showcase-cyan); white-space: nowrap; }
.operations-report__result, .operations-report__time-resolution { color: var(--showcase-muted); font-size: .9rem; }.operations-report__time-resolution { display: grid; gap: 4px; margin-top: 8px; }
.operations-report table { width: 100%; margin-top: 8px; border-collapse: collapse; }.operations-report th, .operations-report td { border-bottom: 1px solid var(--showcase-border-soft); padding: 5px; text-align: left; }
.operations-report__evidence span { color: var(--showcase-muted); font-size: .85rem; }
@media (max-width: 650px) { .operations-report__history-row { grid-template-columns: 1fr auto; }.operations-report__metadata { grid-template-columns: 1fr; }.operations-report__section-head { flex-direction: column; } }
</style>
