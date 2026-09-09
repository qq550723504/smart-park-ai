<script setup lang="ts">
import { ref, watch } from 'vue'
import OperationsDailyReport from './OperationsDailyReport.vue'
import AnomalyRadar from './AnomalyRadar.vue'
import AnomalyEvidenceDrawer from './AnomalyEvidenceDrawer.vue'
import EnergyTimeSeriesPanel from './EnergyTimeSeriesPanel.vue'
import DeviceTelemetryHealthPanel from './DeviceTelemetryHealthPanel.vue'
import OrchestrationPanel from './OrchestrationPanel.vue'
import type { ExecutionTraceLike } from '../../composables/useOperationsAnalysis'
import type { DemoRole } from '../../types/workflow'
import type { AnomalyFilters } from '../../types/operationsAnomaly'
import type { WorkbenchView } from '../../types/workbench'

const props = withDefaults(defineProps<{
  role: DemoRole
  trace?: ExecutionTraceLike
  active?: boolean
  analyticsAvailable?: boolean
  collaborationAvailable?: boolean
  securityIncidentAvailable?: boolean
}>(), { active: true, analyticsAvailable: false, collaborationAvailable: false, securityIncidentAvailable: false })
const emit = defineEmits<{
  'open-analysis': [question: string]
  'open-building': [buildingId: string, filters: AnomalyFilters]
  'open-trace': [runId: string]
  'open-view': [view: WorkbenchView]
}>()

const groups = [
  {
    title: '停车与交通',
    description: '按停车区域查看进场量和车位利用率。',
    questions: ['过去5天各停车区域停车利用率', '过去5天各停车区域进场量'],
  },
  {
    title: '能耗与空间',
    description: '从楼宇、占用人数和基线偏差理解空间运营。',
    questions: ['过去5天各楼宇能耗基线偏差', '过去5天各楼宇平均占用人数', '过去5天各楼宇能耗与占用人数关系'],
  },
  {
    title: '告警与设备',
    description: '从风险构成、楼宇分布和设备在线状态识别需要关注的运营异常。',
    questions: [
      '过去7天告警数量',
      '过去7天高风险告警数量',
      '过去7天各风险等级告警数量',
      '过去7天各类别告警数量',
      '过去7天各状态告警数量',
      '过去7天各楼宇告警数量排行',
      '过去7天各楼宇高风险告警排行',
      '各楼宇离线设备数',
      '各设备类型离线设备数',
    ],
  },
]
const selectedBuildingId = ref<string | null>(null)
const selectedFilters = ref<AnomalyFilters>({})
const energyTrendStatus = ref<'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE'>('UNAVAILABLE')
const temperatureStatus = ref<'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE'>('UNAVAILABLE')
const deviceHealthStatus = ref<'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE'>('UNAVAILABLE')

function openBuilding(buildingId: string, filters: AnomalyFilters): void {
  selectedBuildingId.value = buildingId
  selectedFilters.value = filters
  emit('open-building', buildingId, filters)
}

function closeEvidence(): void {
  selectedBuildingId.value = null
}

watch(() => props.active, (active) => {
  if (!active) closeEvidence()
})
</script>

<template>
  <main class="main-content operations-board" data-operations-board>
    <section class="operations-board__hero">
      <div>
        <span class="eyebrow">OPERATIONS COCKPIT / READ ONLY</span>
        <h2>园区运营态势<br /><em>从真实异常到 Agent 研判</em></h2>
        <p>管理层先看告警、设备与楼宇风险，需要深入时再进入受控分析、工作流和证据轨迹。</p>
      </div>
      <div class="operations-board__hero-facts">
        <div><strong>14</strong><span>已登记分析入口</span></div>
        <div><strong>只读</strong><span>Analytics 执行模式</span></div>
        <div><strong>Trace</strong><span>后端事件可追溯</span></div>
      </div>
    </section>

    <section class="operations-board__capability-strip" aria-label="驾驶舱数据能力状态">
      <article data-cockpit-feature="energy-overview" data-feature-state="ADAPTED"><span>能耗总览 / 排行</span><strong>ADAPTED</strong><small>使用真实能耗基线偏差；不冒充实时总量</small><button type="button" @click="emit('open-analysis', '过去5天各楼宇能耗基线偏差')">打开只读分析</button></article>
      <article data-cockpit-feature="energy-trend" :data-feature-state="energyTrendStatus"><span>能耗趋势</span><strong>{{ energyTrendStatus }}</strong><small>真实小时序列；缺失点不补零、不插值</small><button type="button" @click="emit('open-analysis', '过去5天各楼宇能耗基线偏差')">分析基线偏差</button></article>
      <article data-cockpit-feature="device-health" :data-feature-state="deviceHealthStatus"><span>设备健康研判</span><strong>{{ deviceHealthStatus }}</strong><small>解释性状态与证据；不生成健康分</small><button type="button" @click="emit('open-analysis', '各设备类型离线设备数')">分析设备状态</button></article>
      <article data-cockpit-feature="temperature-telemetry" :data-feature-state="temperatureStatus"><span>温度遥测</span><strong>{{ temperatureStatus }}</strong><small>确定性 Demo source；非生产 IoT</small></article>
      <article data-cockpit-feature="vibration-telemetry" data-feature-state="NOT_READY"><span>振动遥测</span><strong>NOT_READY</strong><small>当前没有振动 datasource</small></article>
    </section>

    <EnergyTimeSeriesPanel
      :role="props.role"
      :active="props.active"
      @status="(status) => energyTrendStatus = status"
    />

    <DeviceTelemetryHealthPanel
      :role="props.role"
      :active="props.active"
      @telemetry-status="(status) => temperatureStatus = status"
      @health-status="(status) => deviceHealthStatus = status"
    />

    <AnomalyRadar
      :role="props.role"
      :active="props.active"
      @open-analysis="(question) => emit('open-analysis', question)"
      @open-building="openBuilding"
      @open-trace="(runId) => emit('open-trace', runId)"
    />
    <AnomalyEvidenceDrawer
      :role="props.role"
      :building-id="selectedBuildingId"
      :filters="selectedFilters"
      :open="selectedBuildingId !== null"
      @close="closeEvidence"
      @open-analysis="(question) => emit('open-analysis', question)"
      @open-trace="(runId) => emit('open-trace', runId)"
    />

    <section class="operations-board__workbench panel" aria-label="Agent 工作台入口">
      <div class="operations-board__workbench-copy">
        <span class="eyebrow">AGENT WORKBENCH</span>
        <h2>不是聊天框，是可追溯的执行入口</h2>
        <p>发现异常 → 读取上下文 → 调用领域能力 → 分析基线 → 形成假设 → 验证证据 → 输出建议 → 人工确认。</p>
        <div class="operations-board__workbench-actions">
          <button type="button" data-agent-entry="analytics" @click="emit('open-analysis', '过去5天各楼宇能耗与占用人数关系')">分析能耗</button>
          <button type="button" data-agent-entry="workflow" @click="emit('open-view', 'workflow')">告警诊断</button>
          <button type="button" data-agent-entry="collaboration" :disabled="!props.collaborationAvailable" @click="emit('open-view', 'collaboration')">跨域专家协作</button>
          <button type="button" data-agent-entry="security" :disabled="!props.securityIncidentAvailable" @click="emit('open-view', 'security-incidents')">安全事件研判</button>
        </div>
      </div>
      <div class="operations-board__agent-boundary" data-cockpit-feature="run-all-agents" :data-feature-state="props.analyticsAvailable ? 'AVAILABLE' : 'NOT_READY'">
        <span>完整演示 / 运行全部 Agent</span><strong>{{ props.analyticsAvailable ? 'AVAILABLE' : 'NOT_READY' }}</strong><p>{{ props.analyticsAvailable ? '启动真实跨场景编排；不适用或无权限的步骤会明确跳过。' : '必需的 Operations Analysis 当前未启用。' }}</p>
      </div>
    </section>

    <OrchestrationPanel :role="props.role" :active="props.active" :available="props.analyticsAvailable" @open-trace="(runId) => emit('open-trace', runId)" />

    <OperationsDailyReport :role="props.role" :trace="props.trace" :active="props.active" :available="props.analyticsAvailable" />

    <section class="operations-board__question-grid" aria-label="受控分析入口">
      <section v-for="group in groups" :key="group.title" class="panel operations-board__group" :aria-label="group.title">
        <div class="section-heading compact"><div><span class="eyebrow">CONTROLLED ANALYTICS</span><h2>{{ group.title }}</h2></div><span class="count-badge">{{ group.questions.length }} 个入口</span></div>
        <p>{{ group.description }}</p>
        <div class="operations-board__cards">
          <button v-for="question in group.questions" :key="question" type="button" data-board-question :data-question="question" @click="emit('open-analysis', question)">
            <span class="operations-board__card-icon">↗</span><span><strong>{{ question }}</strong><small>打开真实只读分析</small></span>
          </button>
        </div>
      </section>
    </section>
  </main>
</template>

<style scoped>
.operations-board { display: grid; gap: 16px; }
.operations-board__hero { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(360px, .65fr); gap: 28px; align-items: end; padding: 30px; border: 1px solid rgba(112, 232, 255, .2); background: linear-gradient(112deg, rgba(9, 24, 40, .94), rgba(17, 22, 45, .76)); }
.operations-board__hero h2 { margin: 8px 0 12px; font-size: clamp(2rem, 3.1vw, 3.6rem); line-height: 1.04; letter-spacing: -.045em; }
.operations-board__hero h2 em { color: var(--showcase-cyan); font-style: normal; }
.operations-board__hero p { max-width: 720px; margin: 0; color: var(--showcase-muted); line-height: 1.65; }
.operations-board__hero-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid var(--showcase-border-soft); }
.operations-board__hero-facts div { min-width: 0; padding: 16px; background: rgba(5, 13, 25, .64); }
.operations-board__hero-facts div + div { border-left: 1px solid var(--showcase-border-soft); }
.operations-board__hero-facts strong, .operations-board__hero-facts span { display: block; }
.operations-board__hero-facts strong { color: var(--showcase-cyan); font-size: 1.35rem; }
.operations-board__hero-facts span { margin-top: 5px; color: var(--showcase-muted); font-size: .7rem; }
.operations-board__capability-strip { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.operations-board__capability-strip article { display: grid; align-content: start; gap: 6px; min-height: 132px; padding: 14px; border: 1px solid var(--showcase-border-soft); background: rgba(7, 16, 29, .72); }
.operations-board__capability-strip span { color: var(--showcase-ivory); font-size: .84rem; }
.operations-board__capability-strip strong { color: #b8a5ff; font-size: .7rem; letter-spacing: .1em; }
.operations-board__capability-strip article[data-feature-state='NOT_READY'] strong,
.operations-board__capability-strip article[data-feature-state='PARTIAL'] strong,
.operations-board__capability-strip article[data-feature-state='UNAVAILABLE'] strong { color: var(--showcase-amber); }
.operations-board__capability-strip article[data-feature-state='AVAILABLE'] strong { color: var(--showcase-cyan); }
.operations-board__capability-strip small { color: var(--showcase-muted); line-height: 1.45; }
.operations-board__capability-strip button { justify-self: start; margin-top: auto; padding: 0; color: var(--showcase-cyan); border: 0; background: transparent; cursor: pointer; }
.operations-board__workbench { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(280px, .55fr); gap: 18px; padding: 26px; overflow: hidden; }
.operations-board__workbench h2 { margin: 8px 0; font-size: 1.7rem; }
.operations-board__workbench p { color: var(--showcase-muted); line-height: 1.6; }
.operations-board__workbench-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.operations-board__workbench-actions button { padding: 10px 13px; color: var(--showcase-ivory); border: 1px solid rgba(112, 232, 255, .28); background: rgba(19, 83, 112, .18); cursor: pointer; }
.operations-board__workbench-actions button:disabled { cursor: not-allowed; opacity: .45; }
.operations-board__agent-boundary { padding: 18px; border: 1px solid rgba(255, 210, 122, .26); background: rgba(83, 59, 24, .16); }
.operations-board__agent-boundary span, .operations-board__agent-boundary strong { display: block; }
.operations-board__agent-boundary strong { margin-top: 7px; color: var(--showcase-amber); font-size: .72rem; letter-spacing: .1em; }
.operations-board__agent-boundary p { margin-bottom: 0; font-size: .82rem; }
.operations-board__question-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.operations-board__group { padding: 22px; }
.operations-board__group:last-child { grid-column: 1 / -1; }
.operations-board__group > p { color: var(--showcase-muted); margin: 0 0 16px; }
.operations-board__cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.operations-board__group:last-child .operations-board__cards { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.operations-board__cards button { display: flex; align-items: center; gap: 10px; min-width: 0; padding: 13px; text-align: left; color: var(--showcase-ivory); border: 1px solid var(--showcase-border-soft); background: rgba(7, 16, 29, .58); cursor: pointer; }
.operations-board__cards button:hover { border-color: var(--showcase-cyan); background: var(--showcase-cyan-soft); }
.operations-board__cards strong, .operations-board__cards small { display: block; }
.operations-board__cards strong { line-height: 1.35; }
.operations-board__cards small { margin-top: 5px; color: var(--showcase-muted); }
.operations-board__card-icon { color: var(--showcase-cyan); font-size: 1.1rem; }
/* The workbench keeps a 380px trace rail, so the board needs its compact grid before the viewport itself reaches 1100px. */
@media (max-width: 1600px) { .operations-board__hero, .operations-board__workbench { grid-template-columns: 1fr; } .operations-board__capability-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); } .operations-board__group:last-child .operations-board__cards { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .operations-board__hero { padding: 20px; } .operations-board__hero-facts, .operations-board__capability-strip, .operations-board__question-grid, .operations-board__cards, .operations-board__group:last-child .operations-board__cards { grid-template-columns: 1fr; } .operations-board__hero-facts div + div { border-left: 0; border-top: 1px solid var(--showcase-border-soft); } .operations-board__group:last-child { grid-column: auto; } }
</style>
