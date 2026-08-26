<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ExpertCard from './ExpertCard.vue'
import { useExpertCollaboration } from '../composables/useExpertCollaboration'
import type { ExpertDomain } from '../types/collaboration'
import type { ExecutionTrace } from '../composables/useExecutionTrace'

const props = withDefaults(defineProps<{ trace: ExecutionTrace; active?: boolean }>(), { active: true })
const question = ref('电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联')
const presets = [
  '电表 DEV-ENERGY-001 当前能耗是否高于基线',
  '设备 DEV-HVAC-001 当前状态如何，是否存在关联告警',
  '电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联',
]
const { run, loading, error, isRunning, start } = useExpertCollaboration()
const domainLabels: Record<ExpertDomain, string> = { ENERGY: '能耗专家', DEVICE: '设备专家', SECURITY: '安防专家' }
const domains = computed(() => run.value?.plan?.selectedDomains ?? [])
const handoffs = computed(() => props.trace.events.value.filter((event) => event.eventType === 'EXPERT_HANDOFF'))

watch(
  [() => props.active, () => run.value?.runId],
  ([active, runId]) => { if (active && runId) props.trace.subscribe(runId) },
  { immediate: true },
)

function selectPreset(value: string) { question.value = value }

async function submit() {
  try { await start(question.value) } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '专家协作启动失败') }
}
function findingFor(domain: ExpertDomain) { return run.value?.findings.find((finding) => finding.domain === domain) }
function statusLabel(status?: string) { return ({ RUNNING: '协作进行中', COMPLETED: '已完成', FAILED: '执行失败', NEEDS_CLARIFICATION: '需要澄清' } as Record<string, string>)[status ?? ''] ?? '等待启动' }
function timeLabel(timestamp: string) { return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false }) }
</script>

<template>
  <div class="collaboration-main">
    <section class="hero-row collaboration-hero"><div><span class="eyebrow">专家协作 · 04</span><h2>让复杂问题<br /><em>由合适的专家共同回答</em></h2><p class="hero-copy">主管先拆解问题，再动态分派领域专家并汇总有证据的结论。每一次交接都可追踪。</p></div><div class="hero-metrics"><div><strong>{{ domains.length || '—' }}</strong><span>本次专家</span></div><div><strong>{{ run?.findings.length || '—' }}</strong><span>已返回结论</span></div><div><strong>{{ run?.synthesis ? `${Math.round(run.synthesis.confidence * 100)}%` : '—' }}</strong><span>汇总置信度</span></div></div></section>

    <section class="collaboration-layout">
      <div class="collaboration-primary">
        <section class="panel collaboration-question">
          <div class="section-heading"><div><span class="eyebrow">问题输入</span><h2>发起一次专家会诊</h2></div><el-tag :type="isRunning ? 'warning' : run?.status === 'COMPLETED' ? 'success' : 'info'" effect="plain" round>{{ statusLabel(run?.status) }}</el-tag></div>
          <form class="collaboration-form" @submit.prevent="submit"><el-input v-model="question" aria-label="专家协作问题" :disabled="loading || isRunning" placeholder="请包含可查询的 DEV-… 或 SEC-… 标识" /><el-button type="primary" native-type="submit" :loading="loading" :disabled="isRunning">开始协作</el-button></form>
          <div class="collaboration-presets" aria-label="预置问题"><button v-for="preset in presets" :key="preset" type="button" :disabled="loading || isRunning" @click="selectPreset(preset)">{{ preset }}</button></div>
          <p v-if="error" class="collaboration-error">{{ error }}</p>
          <div v-if="run?.plan" class="plan-summary"><span>主管拆解</span><strong>{{ run.plan.selectionReason }}</strong><small>{{ run.plan.normalizedQuestion }}</small></div>
        </section>

        <section class="expert-section"><div class="section-heading"><div><span class="eyebrow">动态专家卡</span><h2>本次参与的专家</h2></div><span class="count-badge">{{ domains.length }} 个分支</span></div><div v-if="domains.length" class="expert-grid"><ExpertCard v-for="domain in domains" :key="domain" :domain="domain" :plan="run?.plan ?? null" :finding="findingFor(domain)" /></div><div v-else class="collaboration-empty">提交问题后，系统会根据问题内容动态选择专家。</div></section>

        <section v-if="run?.synthesis" class="panel synthesis-panel"><div class="section-heading"><div><span class="eyebrow">主管汇总</span><h2>协作结论</h2></div><span class="synthesis-confidence">置信度 {{ Math.round(run.synthesis.confidence * 100) }}%</span></div><p>{{ run.synthesis.conclusion }}</p><div class="evidence-list"><span v-for="ref in run.synthesis.evidenceRefs" :key="ref">{{ ref }}</span></div><p v-if="run.synthesis.uncertainties.length" class="uncertainty">不确定性：{{ run.synthesis.uncertainties.join('、') }}</p></section>
      </div>

      <aside class="panel handoff-panel"><div class="section-heading"><div><span class="eyebrow">交接轨迹</span><h2>专家之间如何协作</h2></div><span class="live-indicator"><i></i>实时</span></div><ol v-if="handoffs.length" class="handoff-list"><li v-for="event in handoffs" :key="event.eventId"><span class="handoff-node"></span><div><div class="handoff-meta"><strong>{{ event.actor }}</strong><time>{{ timeLabel(event.timestamp) }}</time></div><p>{{ event.safeSummary }}</p><span v-if="event.displayPayload?.payloadType === 'EXPERT_HANDOFF'" class="handoff-detail">{{ domainLabels[event.displayPayload.domain as ExpertDomain] ?? event.displayPayload.domain }} · {{ event.displayPayload.direction }} · {{ event.displayPayload.findingStatus }}</span></div></li></ol><div v-else class="collaboration-empty">启动协作后，主管分派与专家回传会按时间记录在这里。</div></aside>
    </section>
  </div>
</template>
