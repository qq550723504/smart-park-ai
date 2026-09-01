<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getGovernanceOverview } from '../../services/workflowApi'
import type { GovernanceOverview } from '../../services/workflowApi'

const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })
const overview = ref<GovernanceOverview | null>(null)
const loading = ref(false)
const failed = ref(false)

const readiness = computed(() => overview.value
  ? `${overview.value.scenarios.ready}/${overview.value.scenarios.total}`
  : '—')

function percent(value: number | null): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    overview.value = await getGovernanceOverview()
  } catch {
    overview.value = null
    failed.value = true
  } finally {
    loading.value = false
  }
}

watch(() => props.active, (active) => { if (active) void load() }, { immediate: true })
</script>

<template>
  <main class="main-content governance-main" data-governance-center>
    <section class="hero-row">
      <div>
        <span class="eyebrow">治理中心 · 安全概览</span>
        <h2>看见能力边界，<br /><em>再决定如何使用 AI</em></h2>
        <p class="hero-copy">这里展示可审计的聚合指标与在线验证状态。原始对话、知识正文和敏感配置不会进入概览。</p>
      </div>
      <div class="hero-metrics" v-if="overview">
        <div><strong>{{ readiness }}</strong><span>已验证场景</span></div>
        <div><strong>{{ overview.business.workflowCount }}</strong><span>工作流样本</span></div>
        <div><strong>{{ overview.governance.auditEntryCount }}</strong><span>审计记录</span></div>
      </div>
    </section>

    <p v-if="loading" class="governance-state" role="status">正在读取安全概览…</p>
    <p v-else-if="failed" class="governance-state is-error" role="alert">当前无法读取治理概览，请稍后重试。</p>

    <template v-else-if="overview">
      <section class="panel governance-capabilities" aria-label="当前能力状态">
        <div class="section-heading compact"><div><span class="eyebrow">能力状态</span><h2>当前接入模式</h2></div><span class="count-badge">只读快照</span></div>
        <dl>
          <div><dt>知识检索</dt><dd>{{ overview.capabilities.knowledgeMode === 'rag' ? 'RAG' : 'Mock' }}</dd></div>
          <div><dt>客服回答</dt><dd>{{ overview.capabilities.customerAnswerMode === 'dashscope' ? 'DashScope' : 'Mock' }}</dd></div>
          <div><dt>向量存储</dt><dd>{{ overview.capabilities.vectorStore === 'none' ? '关键词检索' : 'SimpleVectorStore' }}</dd></div>
          <div><dt>运营分析</dt><dd>{{ overview.capabilities.analyticsEnabled ? '已启用' : '未启用' }}</dd></div>
          <div><dt>专家协作</dt><dd>{{ overview.capabilities.collaborationEnabled ? '已启用' : '未启用' }}</dd></div>
          <div><dt>实时语音</dt><dd>{{ overview.capabilities.voiceEnabled ? '已启用' : '未启用' }}</dd></div>
        </dl>
      </section>
      <section class="panel governance-scenarios" aria-label="场景验证状态">
        <div class="section-heading compact"><div><span class="eyebrow">场景验证</span><h2>演示目录状态</h2></div></div>
        <div class="scenario-counts"><span>READY <strong>{{ overview.scenarios.ready }}</strong></span><span>待验证 <strong>{{ overview.scenarios.notReady }}</strong></span><span>未启用 <strong>{{ overview.scenarios.disabled }}</strong></span></div>
      </section>
      <section class="governance-grid" aria-label="治理聚合指标">
        <article class="panel governance-card"><span>工作流完成率</span><strong>{{ percent(overview.governance.completionRate) }}</strong><small>{{ overview.business.completedWorkflowCount }} / {{ overview.business.workflowCount }} 已完成</small></article>
        <article class="panel governance-card"><span>正向反馈率</span><strong>{{ percent(overview.governance.positiveFeedbackRate) }}</strong><small>{{ overview.governance.positiveFeedbackCount }} / {{ overview.governance.feedbackCount }} 条反馈</small></article>
        <article class="panel governance-card"><span>客服会话</span><strong>{{ overview.business.customerSessionCount }}</strong><small>人工工单 {{ overview.business.humanTicketCount }} 条</small></article>
        <article class="panel governance-card"><span>知识资产</span><strong>{{ overview.governance.activeKnowledgeDocumentCount }}</strong><small>启用 / 共 {{ overview.governance.knowledgeDocumentCount }} 篇</small></article>
      </section>
      <section class="panel governance-boundaries">
        <div class="section-heading compact"><div><span class="eyebrow">使用边界</span><h2>演示与生产要分开</h2></div></div>
        <ul><li v-for="boundary in overview.boundaries" :key="boundary">{{ boundary }}</li></ul>
      </section>
    </template>
  </main>
</template>

<style scoped>
.governance-state { color: var(--showcase-muted); padding: 24px 0; }
.governance-state.is-error { color: var(--showcase-amber); }
.governance-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.governance-capabilities, .governance-scenarios { margin-bottom: 18px; padding: 24px; }
.governance-capabilities dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 16px 0 0; }
.governance-capabilities dl div { display: grid; gap: 4px; padding: 12px; border: 1px solid var(--showcase-border-soft); }
.governance-capabilities dt { color: var(--showcase-muted); font-size: 0.78rem; }
.governance-capabilities dd { margin: 0; color: var(--showcase-cyan); }
.scenario-counts { display: flex; flex-wrap: wrap; gap: 14px; color: var(--showcase-muted); }
.scenario-counts strong { color: var(--showcase-cyan); font: 500 24px Georgia, serif; margin-left: 4px; }
.governance-card { padding: 22px; display: grid; gap: 8px; }
.governance-card span, .governance-card small { color: var(--showcase-muted); }
.governance-card strong { color: var(--showcase-cyan); font: 500 32px Georgia, serif; }
.governance-boundaries { margin-top: 18px; padding: 24px; }
.governance-boundaries ul { margin: 16px 0 0; padding-left: 20px; color: var(--showcase-muted); line-height: 1.8; }
@media (max-width: 850px) { .governance-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 500px) { .governance-grid { grid-template-columns: 1fr; } }
@media (max-width: 650px) { .governance-capabilities dl { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
