<script setup lang="ts">
import { computed } from 'vue'
import type { ExpertDomain, ExpertFinding, SupervisorPlan } from '../types/collaboration'

const props = defineProps<{
  domain: ExpertDomain
  plan: SupervisorPlan | null
  finding?: ExpertFinding
}>()

const domainLabels: Record<ExpertDomain, { name: string; subtitle: string; mark: string }> = {
  ENERGY: { name: '能耗专家', subtitle: '负荷与能耗基线', mark: '能' },
  DEVICE: { name: '设备专家', subtitle: '设备状态与告警关联', mark: '设' },
  SECURITY: { name: '安防专家', subtitle: '安全事件与访问风险', mark: '安' },
}
const statusLabels: Record<string, string> = {
  PENDING: '等待分派', RUNNING: '分析中', SUPPORTED: '已支持',
  INSUFFICIENT_EVIDENCE: '证据不足', FAILED: '分析失败',
}
const detail = computed(() => domainLabels[props.domain])
const assignment = computed(() => props.plan?.assignments[props.domain] ?? '')
const status = computed(() => props.finding?.status ?? (props.plan ? 'RUNNING' : 'PENDING'))
const statusLabel = computed(() => statusLabels[status.value] ?? status.value)
const confidence = computed(() => props.finding ? `${Math.round(props.finding.confidence * 100)}%` : '--')
</script>

<template>
  <article class="expert-card" :class="`expert-card-${status.toLowerCase()}`" :data-testid="`expert-card-${domain}`">
    <header class="expert-card-header">
      <div class="expert-identity"><span class="expert-mark">{{ detail.mark }}</span><div><strong>{{ detail.name }}</strong><span>{{ detail.subtitle }}</span></div></div>
      <span class="expert-status">{{ statusLabel }}</span>
    </header>
    <div class="expert-assignment"><span>交接任务</span><p>{{ assignment || '等待主管分配任务' }}</p></div>
    <div v-if="finding" class="expert-finding">
      <p class="expert-conclusion">{{ finding.conclusion }}</p>
      <div class="expert-stats"><span>置信度 <strong>{{ confidence }}</strong></span><span>证据 <strong>{{ finding.evidenceRefs.length }} 条</strong></span></div>
      <div v-if="finding.evidenceRefs.length" class="evidence-list"><span v-for="ref in finding.evidenceRefs" :key="ref">{{ ref }}</span></div>
      <p v-if="finding.nextChecks.length" class="next-checks">后续核查：{{ finding.nextChecks.join('、') }}</p>
    </div>
    <div v-else class="expert-pending"><span class="status-pulse"></span>正在等待专家返回结构化结论</div>
  </article>
</template>
