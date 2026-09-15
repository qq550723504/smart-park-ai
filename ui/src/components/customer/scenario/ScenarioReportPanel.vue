<script setup lang="ts">
import { computed } from 'vue'
import { Document, Download, Plus } from '@element-plus/icons-vue'
import { useB2NightEnergyScenario } from '../../../scenario/b2-night-energy/store'
import { downloadReportSnapshot } from '../../../scenario/b2-night-energy/download'

const store = useB2NightEnergyScenario()
const snapshot = store.snapshot

const canGenerate = computed(() => snapshot.value.reportContract.allowedAfter.includes(snapshot.value.state.stage))
const reports = computed(() => snapshot.value.state.reports)
const activeReport = computed(() => store.activeReport.value)

const activeLines = computed(() => activeReport.value?.markdown.split('\n') ?? [])

function downloadActiveReport(): void {
  // Downloads the frozen snapshot as-is; it never regenerates the report.
  if (activeReport.value) downloadReportSnapshot(activeReport.value)
}
</script>

<template>
  <section id="customer-reports-main" class="scenario-panel" tabindex="-1" data-scenario-reports aria-labelledby="scenario-reports-title">
    <header class="scenario-panel__head">
      <div>
        <p class="scenario-panel__eyebrow">不可变快照 · 阅读与下载不重新生成</p>
        <h2 id="scenario-reports-title">运营报告 · 场景事件简报</h2>
        <p class="scenario-panel__lede">报告冻结生成时的状态；旧 R1 不因新 R2 变化。</p>
      </div>
      <span class="scenario-panel__stage">{{ snapshot.reportContract.kind }}</span>
    </header>

    <div class="scenario-actions">
      <button type="button" class="scenario-button scenario-button--primary" :disabled="!canGenerate || store.busy.value" data-scenario-generate-report @click="store.generateReport()">
        <Plus aria-hidden="true" /> 生成事件简报
      </button>
      <span class="scenario-muted">{{ canGenerate ? '当前阶段可生成' : '完成巡检与研判后可生成' }}</span>
    </div>

    <div class="scenario-reports">
      <aside class="scenario-reports__list" aria-label="场景简报快照列表">
        <button
          v-for="report in reports"
          :key="report.reportId"
          type="button"
          class="scenario-reports__item"
          :class="{ 'is-active': activeReport?.reportId === report.reportId }"
          :data-scenario-report="report.reportId"
          @click="store.openReport(report.reportId)"
        >
          <Document aria-hidden="true" />
          <span>
            <strong>{{ report.title }}</strong>
            <small>修订 {{ report.stateRevision }} · {{ report.stage }} · {{ report.virtualGeneratedAt.slice(0, 16).replace('T', ' ') }}</small>
          </span>
        </button>
        <p v-if="!reports.length" class="scenario-muted" data-scenario-no-reports>尚未生成简报快照。</p>
      </aside>

      <article v-if="activeReport" class="scenario-reports__view" data-scenario-report-view>
        <header>
          <h3>{{ activeReport.reportId }}</h3>
          <span class="scenario-tag">修订 {{ activeReport.stateRevision }}</span>
          <button type="button" class="scenario-button" data-scenario-download-report @click="downloadActiveReport">
            <Download aria-hidden="true" /> 下载同一快照
          </button>
        </header>
        <div class="scenario-report-body">
          <p v-for="(line, index) in activeLines" :key="index" :class="{ 'is-heading': line.startsWith('#') }">{{ line || '\u00a0' }}</p>
        </div>
      </article>
      <p v-else class="scenario-muted">选择左侧快照查看冻结内容。</p>
    </div>

    <p v-if="store.error.value" class="scenario-alert" role="alert" data-scenario-error>{{ store.error.value }}</p>
  </section>
</template>
