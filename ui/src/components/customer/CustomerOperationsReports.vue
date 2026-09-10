<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Calendar, Clock, DataAnalysis, Document, Download, List, Refresh, TrendCharts, WarningFilled } from '@element-plus/icons-vue'
import { useOperationsDailyReport } from '../../composables/useOperationsDailyReport'
import type { OperationsReportCreateRequest, OperationsReportSection, OperationsReportStatus } from '../../types/operationsReport'
import type { DemoRole } from '../../types/workflow'
import CustomerOverviewChart, { type CustomerChartDatum } from './CustomerOverviewChart.vue'
import ecoOperations from '../../assets/customer/eco-operations.png'
import ecoOperations480 from '../../assets/customer/eco-operations-480.webp'
import ecoOperations960 from '../../assets/customer/eco-operations-960.webp'
import './customer-reports.css'

const props = withDefaults(defineProps<{
  active?: boolean
  available?: boolean
  demoRole?: DemoRole
  pollIntervalMs?: number
}>(), { active: true, available: true, demoRole: 'OPERATOR' })

const state = useOperationsDailyReport({ pollIntervalMs: props.pollIntervalMs })
const lookbackDays = ref<1 | 5 | 30>(5)
const periods = [
  { days: 1 as const, label: '近 24 小时' },
  { days: 5 as const, label: '近 5 天' },
  { days: 30 as const, label: '近 30 天' },
]
const canUse = computed(() => props.demoRole === 'OPERATOR' || props.demoRole === 'ADMIN')
const statusLabels: Record<OperationsReportStatus, string> = {
  REQUESTED: '已请求',
  GENERATING: '生成中',
  COMPLETED: '已生成',
  PARTIAL: '部分完成',
  FAILED: '生成失败',
}
const sectionLabels = {
  PENDING: '等待中',
  RUNNING: '生成中',
  COMPLETED: '已完成',
  UNAVAILABLE: '数据不可用',
  FAILED: '生成失败',
} as const

function formatTime(value: string | null | undefined, timezone = 'Asia/Shanghai'): string {
  if (!value) return '未提供'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return value
  try {
    return date.toLocaleString('zh-CN', {
      timeZone: timezone,
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    })
  } catch (failure) {
    if (!(failure instanceof RangeError)) throw failure
    return `${date.toISOString()} (${timezone})`
  }
}

function createRequest(): OperationsReportCreateRequest {
  const to = new Date()
  return {
    reportType: 'OPERATIONS_DAILY',
    timeWindow: {
      fromInclusive: new Date(to.getTime() - lookbackDays.value * 24 * 60 * 60 * 1000).toISOString(),
      toExclusive: to.toISOString(),
    },
    timezone: 'Asia/Shanghai',
  }
}

function generate(): void {
  const request = createRequest()
  void state.start(props.demoRole, request, `${request.reportType}:${request.timezone}:${lookbackDays.value}d`)
}

function open(reportId: string): void {
  void state.open(reportId, props.demoRole)
}

function refresh(): void {
  void state.refresh(props.demoRole)
}

function download(reportId: string): void {
  void state.download(reportId, props.demoRole)
}

function displayCell(value: unknown): string {
  if (value == null) return '缺失'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value)
  return '不可展示'
}

function numericValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value.trim())) {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function chartData(section: OperationsReportSection): CustomerChartDatum[] {
  return section.rows.slice(0, 12).map((row, index) => {
    const value = row.map(numericValue).find((candidate) => candidate != null) ?? null
    const label = row.find((cell) => typeof cell === 'string' && numericValue(cell) == null)
    return { name: typeof label === 'string' ? label : `记录 ${index + 1}`, value }
  })
}

const report = computed(() => state.report.value)
const reportSections = computed(() => report.value?.sections ?? [])
const completedSections = computed(() => reportSections.value.filter((section) => section.status === 'COMPLETED'))
const chartSection = computed(() => completedSections.value.find((section) => chartData(section).some((item) => item.value != null)) ?? null)
const selectedChartData = computed(() => chartSection.value ? chartData(chartSection.value) : [])
const chartUnit = computed(() => chartSection.value?.sourceReferences.find((source) => source.unit)?.unit ?? '')
const keyConclusions = computed(() => {
  if (!report.value) return []
  return [...new Set([
    report.value.summary,
    ...report.value.sections.map((section) => section.summary),
  ].map((value) => value?.trim()).filter((value): value is string => Boolean(value)))].slice(0, 4)
})
const selectedStatus = computed(() => report.value ? statusLabels[report.value.status] : '尚未选择')
const terminalWarning = computed(() => {
  if (!report.value) return ''
  if (report.value.status === 'PARTIAL') return '本报告仅部分完成。页面只展示快照中已取得的章节，缺失部分不会补零或推断。'
  if (report.value.status === 'FAILED') return '本次报告生成失败。历史记录仍可查看，但不能作为成功报告下载或展示。'
  return ''
})

watch(() => props.demoRole, (role) => {
  state.reset()
  if (props.active && canUse.value) void state.loadHistory(role)
}, { immediate: true })

watch(() => props.active, (active) => {
  if (active && canUse.value) void state.loadHistory(props.demoRole)
})

watch(() => state.reports.value, (reports) => {
  if (props.active && !state.busy.value && !state.report.value && !state.detailLoading.value && reports[0]) {
    void state.open(reports[0].reportId, props.demoRole)
  }
})
</script>

<template>
  <main id="customer-reports-main" class="customer-reports" data-customer-page="reports">
    <section v-if="!canUse" class="customer-card customer-reports__blocked" role="alert">
      <WarningFilled aria-hidden="true" />
      <div><h2>当前角色不能访问运营报告</h2><p>报告读取、生成和下载均由服务端角色校验控制。</p></div>
    </section>

    <template v-else>
      <section class="customer-card customer-reports__toolbar" aria-label="报告生成设置">
        <div class="customer-reports__periods" role="radiogroup" aria-label="报告数据周期">
          <button
            v-for="period in periods"
            :key="period.days"
            type="button"
            :class="{ 'is-current': lookbackDays === period.days }"
            :aria-checked="lookbackDays === period.days"
            role="radio"
            :disabled="state.busy.value"
            @click="lookbackDays = period.days"
          >{{ period.label }}</button>
        </div>
        <div class="customer-reports__type"><span>报告类型</span><strong>运营日报</strong></div>
        <button
          type="button"
          class="customer-reports__generate"
          data-generate-report
          :disabled="state.busy.value || state.detailLoading.value || !props.available || !props.active"
          @click="generate"
        >
          <Document aria-hidden="true" />
          {{ state.busy.value ? '正在生成…' : '立即生成' }}
        </button>
      </section>

      <p v-if="!props.available" class="customer-alert" role="status">
        <WarningFilled aria-hidden="true" />当前分析能力未确认或未启用；仍可查看和下载已有快照，本页不会用静态成功内容替代。
      </p>
      <p v-if="state.error.value" class="customer-alert" role="alert" data-testid="report-error">
        <WarningFilled aria-hidden="true" />{{ state.error.value }}
      </p>

      <div class="customer-reports__layout">
        <section class="customer-card customer-reports__preview" data-testid="report-preview">
          <template v-if="report">
            <header class="customer-reports__preview-head">
              <div class="customer-reports__title-mark"><Document aria-hidden="true" /></div>
              <div>
                <p>不可变运营快照</p>
                <h2>{{ report.title }}</h2>
                <span>{{ formatTime(report.timeWindow.fromInclusive, report.timezone) }} — {{ formatTime(report.timeWindow.toExclusive, report.timezone) }}</span>
              </div>
              <span class="customer-reports__status" :data-status="report.status">{{ selectedStatus }}</span>
              <picture class="customer-reports__illustration" aria-hidden="true">
                <source type="image/webp" :srcset="`${ecoOperations480} 480w, ${ecoOperations960} 960w`" sizes="220px" />
                <img :src="ecoOperations" alt="" />
              </picture>
            </header>

            <p v-if="terminalWarning" class="customer-reports__truth-note" role="status">
              <WarningFilled aria-hidden="true" />{{ terminalWarning }}
            </p>

            <section class="customer-reports__summary">
              <header><DataAnalysis aria-hidden="true" /><h3>执行摘要</h3></header>
              <p>{{ report.summary || '该快照没有提供执行摘要。' }}</p>
              <div class="customer-reports__snapshot-meta">
                <span><Clock aria-hidden="true" />生成时间 {{ formatTime(report.completedAt ?? report.createdAt, report.timezone) }}</span>
                <span><Calendar aria-hidden="true" />数据读取 {{ formatTime(report.asOf, report.timezone) }}</span>
              </div>
            </section>

            <section class="customer-reports__section-overview" aria-labelledby="report-section-overview-title">
              <header><TrendCharts aria-hidden="true" /><h3 id="report-section-overview-title">关键指标概览</h3></header>
              <div class="customer-reports__section-cards">
                <article v-for="section in reportSections" :key="section.sectionId" :data-status="section.status">
                  <span>{{ sectionLabels[section.status] }}</span>
                  <strong>{{ section.title }}</strong>
                  <small>{{ section.status === 'COMPLETED' ? `${section.rowCount} 行快照数据` : (section.partialReason ?? section.failureReason ?? '没有可展示结果') }}</small>
                </article>
              </div>
            </section>

            <section v-if="chartSection && selectedChartData.length" class="customer-reports__chart-card">
              <header><h3>{{ chartSection.title }}</h3><span>来自当前报告快照</span></header>
              <CustomerOverviewChart kind="line" :data="selectedChartData" :unit="chartUnit" :label="`${chartSection.title}，数据来自当前所选报告快照`" />
            </section>

            <section class="customer-reports__details" aria-label="报告章节详情">
              <article v-for="section in reportSections" :key="section.sectionId" :data-status="section.status">
                <header><div><h3>{{ section.title }}</h3><p>{{ section.question }}</p></div><span>{{ sectionLabels[section.status] }}</span></header>
                <p v-if="section.summary">{{ section.summary }}</p>
                <p v-if="section.partialReason || section.failureReason" class="customer-reports__section-failure">{{ section.partialReason ?? section.failureReason }}</p>
                <div v-if="section.status === 'COMPLETED' && section.columns.length && section.rows.length" class="customer-reports__table-wrap">
                  <table>
                    <thead><tr><th v-for="column in section.columns" :key="column">{{ column }}</th></tr></thead>
                    <tbody><tr v-for="(row, rowIndex) in section.rows.slice(0, 4)" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ displayCell(cell) }}</td></tr></tbody>
                  </table>
                  <small v-if="section.rows.length > 4">预览前 4 行；下载产物保留该快照的完整可用内容。</small>
                </div>
              </article>
            </section>

            <details class="customer-reports__sources">
              <summary>查看报告来源与快照标识</summary>
              <dl>
                <div><dt>报告 ID</dt><dd>{{ report.reportId }}</dd></div>
                <div><dt>生成版本</dt><dd>{{ report.generationVersion }}</dd></div>
                <div><dt>数据时区</dt><dd>{{ report.timezone }}</dd></div>
                <div><dt>证据数量</dt><dd>{{ report.evidence.length }}</dd></div>
              </dl>
            </details>
          </template>

          <div v-else class="customer-state customer-reports__preview-empty">
            <Document aria-hidden="true" />
            <strong>{{ state.historyLoading.value || state.detailLoading.value ? '正在读取报告快照' : '尚无可预览报告' }}</strong>
            <span>生成新报告，或从右侧历史中打开一份已有快照。</span>
          </div>
        </section>

        <aside class="customer-reports__aside">
          <section class="customer-card customer-reports__history" data-testid="report-history">
            <header><div><List aria-hidden="true" /><h2>报告目录</h2></div><button type="button" :disabled="state.busy.value || state.detailLoading.value || state.historyLoading.value" data-refresh-reports @click="refresh"><Refresh aria-hidden="true" />刷新</button></header>
            <p v-if="state.historyLoading.value" class="customer-state is-compact">正在读取报告历史…</p>
            <p v-else-if="state.reports.value.length === 0" class="customer-state is-compact">暂无历史报告</p>
            <div v-else class="customer-reports__history-list">
              <button
                v-for="item in state.reports.value"
                :key="item.reportId"
                type="button"
                :disabled="state.busy.value"
                :class="{ 'is-current': report?.reportId === item.reportId }"
                :aria-current="report?.reportId === item.reportId ? 'true' : undefined"
                :data-status="item.status"
                @click="open(item.reportId)"
              >
                <Document aria-hidden="true" />
                <span><strong>{{ item.title }}</strong><small>{{ formatTime(item.createdAt, item.timezone) }}</small></span>
                <em>{{ statusLabels[item.status] }}</em>
              </button>
            </div>
            <button v-if="state.historyHasNext.value" type="button" class="customer-reports__more" :disabled="state.historyLoading.value" @click="state.loadHistory(props.demoRole, true)">加载更多（{{ state.reports.value.length }} / {{ state.historyTotal.value }}）</button>
          </section>

          <section class="customer-card customer-reports__conclusions">
            <header><TrendCharts aria-hidden="true" /><div><h2>关键结论</h2><p>与当前预览属于同一快照</p></div></header>
            <p v-if="!report" class="customer-state is-compact">选择报告后显示</p>
            <p v-else-if="keyConclusions.length === 0" class="customer-state is-compact">该快照没有提供结论</p>
            <ol v-else><li v-for="conclusion in keyConclusions" :key="conclusion">{{ conclusion }}</li></ol>
          </section>

          <section class="customer-card customer-reports__download">
            <header><Download aria-hidden="true" /><div><h2>下载报告</h2><p>下载当前所选报告的服务器 Markdown 快照</p></div></header>
            <button
              type="button"
              data-download-current
              :disabled="!report?.downloadAvailable || Boolean(state.downloadingId.value)"
              @click="report && download(report.reportId)"
            ><Download aria-hidden="true" />{{ state.downloadingId.value ? '正在下载…' : '下载报告（Markdown）' }}</button>
            <p v-if="report?.artifact">{{ report.artifact.fileName }} · {{ Math.ceil(report.artifact.size / 1024) }} KB</p>
            <p v-else>{{ report ? '当前快照没有可下载产物。部分完成或失败不会冒充可下载成功。' : '请先选择一份报告。' }}</p>
            <small>查看、刷新、离开返回和下载都不会重新生成报告；未提供服务端 PDF、分享或发送能力。</small>
          </section>
        </aside>
      </div>
    </template>
  </main>
</template>
