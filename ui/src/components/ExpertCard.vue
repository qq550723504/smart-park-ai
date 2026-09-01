<script setup lang="ts">
import { computed } from 'vue'
import type { ExpertDomain, ExpertFinding, SupervisorPlan } from '../types/collaboration'
import { expertDetailKey, formatFinding } from '../utils/collaborationPresentation'

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
const evidenceStatus = computed(() => props.finding?.evidenceRefs.length ? '工具证据已验证' : `模型置信度 ${confidence.value}`)
const findingDisplay = computed(() => props.finding ? formatFinding(props.finding) : null)
</script>

<template>
  <article class="expert-card" :class="`expert-card-${status.toLowerCase()}`" :data-testid="`expert-card-${domain}`">
    <header class="expert-card-header">
      <div class="expert-identity"><span class="expert-mark">{{ detail.mark }}</span><div><strong>{{ detail.name }}</strong><span>{{ detail.subtitle }}</span></div></div>
      <span class="expert-status">{{ statusLabel }}</span>
    </header>
    <div class="expert-assignment"><span>交接任务</span><p>{{ assignment || '等待主管分配任务' }}</p></div>
    <div v-if="finding" class="expert-finding">
      <p class="expert-conclusion">{{ findingDisplay?.summary }}</p>
      <dl v-if="findingDisplay?.details.length" class="expert-details">
        <div v-for="(detail, index) in findingDisplay.details" :key="expertDetailKey(detail.label, detail.value, index)">
          <dt>{{ detail.label }}</dt>
          <dd>{{ detail.value }}</dd>
        </div>
      </dl>
      <div v-for="(group, groupIndex) in findingDisplay?.detailGroups ?? []" :key="`${group.label}-${groupIndex}`" class="expert-detail-group">
        <p class="expert-detail-group-title">{{ group.label }}</p>
        <dl class="expert-details">
          <div v-for="(detail, index) in group.details" :key="expertDetailKey(detail.label, detail.value, index)">
            <dt>{{ detail.label }}</dt>
            <dd>{{ detail.value }}</dd>
          </div>
        </dl>
      </div>
      <div class="expert-stats"><span><strong>{{ evidenceStatus }}</strong></span><span>证据 <strong>{{ finding.evidenceRefs.length }} 条</strong></span></div>
      <div v-if="findingDisplay?.evidence.length" class="evidence-list"><span v-for="(evidence, index) in findingDisplay.evidence" :key="`${evidence}-${index}`">{{ evidence }}</span></div>
      <p v-if="findingDisplay?.nextChecks.length" class="next-checks">后续核查：{{ findingDisplay.nextChecks.join('、') }}</p>
    </div>
    <div v-else class="expert-pending"><span class="status-pulse"></span>正在等待专家返回结构化结论</div>
  </article>
</template>
