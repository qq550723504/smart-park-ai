<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getSecurityIncident, handoffSecurityIncident, listSecurityIncidents, reviewSecurityIncident } from '../../services/securityIncidentApi'
import type { SecurityDisposition, SecurityIncident, SecurityIncidentSummary } from '../../types/securityIncident'
import type { DemoRole } from '../../types/workflow'
import { securityDispositionLabel, securityEventTypeLabel, securitySourceTypeLabel } from '../../utils/labels'
import './security-incident-center.css'

const props = withDefaults(defineProps<{ role: DemoRole; focusIncidentId?: string | null; active?: boolean }>(), {
  active: true,
})
const emit = defineEmits<{ 'open-collaboration': [payload: { incidentId: string; workItemId: string }] }>()

const items = ref<SecurityIncidentSummary[]>([])
const selected = ref<SecurityIncident | null>(null)
const loading = ref(false)
const actionLoading = ref<'review' | 'handoff' | null>(null)
const error = ref('')
let requestGeneration = 0
const canRead = computed(() => ['APPROVER', 'ADMIN'].includes(props.role))
const highRiskCount = computed(() => items.value.filter(item => item.riskLevel === 'HIGH').length)
const openCount = computed(() => items.value.filter(item => item.status === 'OPEN').length)
const handoffCount = computed(() => items.value.filter(item => item.status === 'HANDOFF').length)
const hasDispositionData = computed(() => items.value.some(item => item.disposition && item.disposition !== 'UNREVIEWED'))
const falsePositiveCount = computed(() => items.value.filter(item => item.disposition === 'FALSE_POSITIVE').length)
const primaryEvidence = computed(() => selected.value?.evidence[0])

watch(() => [props.role, props.focusIncidentId, props.active], ([, , active]) => {
  if (active) void load()
  else {
    requestGeneration += 1
    loading.value = false
    selected.value = null
    error.value = ''
  }
}, { immediate: true })

async function load() {
  const generation = ++requestGeneration
  selected.value = null
  error.value = ''
  if (!canRead.value) { items.value = []; loading.value = false; return }
  loading.value = true
  try {
    const page = await listSecurityIncidents(props.role)
    if (generation !== requestGeneration) return
    items.value = page.items
    const initialIncident = page.items.find(item => item.incidentId === props.focusIncidentId) ?? page.items[0]
    if (initialIncident) await select(initialIncident.incidentId, generation)
  } catch (cause) {
    if (generation === requestGeneration) {
      items.value = []
      error.value = cause instanceof Error ? cause.message : '安全事件读取失败'
    }
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

async function select(incidentId: string, parentGeneration = requestGeneration) {
  const generation = ++requestGeneration
  selected.value = null
  error.value = ''
  try {
    const detail = await getSecurityIncident(props.role, incidentId)
    if (generation === requestGeneration && parentGeneration <= generation) selected.value = detail
  } catch (cause) {
    if (generation === requestGeneration) error.value = cause instanceof Error ? cause.message : '事件详情读取失败'
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

async function action(kind: 'review' | 'handoff', disposition?: SecurityDisposition) {
  if (!selected.value || actionLoading.value) return
  const incidentId = selected.value.incidentId
  actionLoading.value = kind
  try {
    const actionGeneration = requestGeneration
    const next = kind === 'review'
      ? await reviewSecurityIncident(props.role, incidentId, disposition)
      : await handoffSecurityIncident(props.role, incidentId)
    if (requestGeneration !== actionGeneration || selected.value?.incidentId !== incidentId) return
    selected.value = next
    const index = items.value.findIndex(item => item.incidentId === incidentId)
    if (index >= 0) items.value[index] = selected.value
    if (kind === 'handoff' && selected.value.handoffWorkItemId) {
      emit('open-collaboration', { incidentId, workItemId: selected.value.handoffWorkItemId })
    }
    ElMessage.success(kind === 'review'
      ? `事件已记录研判：${securityDispositionLabel(next.disposition)}`
      : '事件已转为协同工作项')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '安全事件动作失败')
  } finally { actionLoading.value = null }
}

function statusLabel(status: string) { return ({ OPEN: '待研判', REVIEWED: '已研判', HANDOFF: '已转协同' } as Record<string, string>)[status] ?? status }
function riskLabel(risk: string) { return ({ LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' } as Record<string, string>)[risk] ?? risk }
function severityLabel(severity?: string) { return ({ LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重', UNKNOWN: '未知' } as Record<string, string>)[severity ?? ''] ?? severity ?? '未知' }
function timeLabel(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
function confidenceLabel(value?: number) { return value == null ? '' : `${Math.round(value * 100)}%` }
function reviewDisabled() { return selected.value?.status !== 'OPEN' || actionLoading.value !== null }
function openExistingHandoff() {
  if (!selected.value?.handoffWorkItemId) return
  emit('open-collaboration', { incidentId: selected.value.incidentId, workItemId: selected.value.handoffWorkItemId })
}
</script>

<template>
  <main class="main-content security-incident-main">
    <section v-if="!canRead" class="panel security-incident-empty"><h2>仅授权安全角色可查看</h2><p>安全事件研判仅对审批人和管理员开放。</p></section>
    <template v-else>
      <section class="hero-row security-incident-hero">
        <div>
          <span class="eyebrow">安全运营 · 06</span>
          <h2>把零散异常<br /><em>还原成可研判事件</em></h2>
          <p class="hero-copy">按区域、类型和时间窗口归并脱敏安全事件，保留可追溯证据并在人工研判后记录协同交接。</p>
        </div>
        <div class="hero-metrics">
          <div><strong>{{ loading ? '—' : openCount }}</strong><span>待研判</span></div>
          <div><strong>{{ loading ? '—' : highRiskCount }}</strong><span>高风险</span></div>
          <div><strong>{{ loading ? '—' : handoffCount }}</strong><span>已转协同</span></div>
          <div data-security-false-positive>
            <strong v-if="hasDispositionData">{{ falsePositiveCount }}</strong>
            <span v-if="hasDispositionData">误报</span>
            <span v-else>暂无复核结论</span>
          </div>
        </div>
      </section>
      <p v-if="error" class="security-incident-error">{{ error }}</p>
      <section class="security-incident-layout">
        <aside class="panel security-incident-list">
          <div class="section-heading"><div><span class="eyebrow">事件队列</span><h2>安全事件</h2></div><span class="count-badge">{{ items.length }}</span></div>
          <div v-if="loading && !items.length" class="security-incident-empty">正在读取安全事件…</div>
          <div v-else-if="!items.length" class="security-incident-empty">当前没有可展示的安全事件。</div>
          <button v-for="item in items" v-else :key="item.incidentId" type="button" class="security-incident-row" :class="{ active: selected?.incidentId === item.incidentId }" :data-security-incident="item.incidentId" @click="select(item.incidentId)">
            <span class="security-incident-row__risk" :class="`risk-${item.riskLevel.toLowerCase()}`">{{ riskLabel(item.riskLevel) }}</span>
            <strong>{{ item.incidentId }}</strong>
            <span>{{ item.buildingId }} · {{ securityEventTypeLabel(item.eventType) }}</span>
            <small>{{ statusLabel(item.status) }} · {{ securityDispositionLabel(item.disposition) }} · {{ item.eventCount }} 个事件 / {{ item.alertCount }} 条告警</small>
            <small data-correlation-times>{{ timeLabel(item.openedAt) }} — {{ timeLabel(item.lastOccurredAt) }}</small>
          </button>
        </aside>
        <section class="panel security-incident-detail">
          <div v-if="!selected" class="security-incident-empty">选择左侧事件查看时间线与脱敏证据。</div>
          <template v-else>
            <div class="section-heading">
              <div><span class="eyebrow">事件研判</span><h2>{{ selected.incidentId }}</h2></div>
              <div class="security-incident-badges"><span :class="`risk-${selected.riskLevel.toLowerCase()}`">{{ riskLabel(selected.riskLevel) }}</span><span>{{ statusLabel(selected.status) }}</span></div>
            </div>
            <p class="security-incident-summary">{{ selected.summary }}</p>
            <dl class="security-incident-facts">
              <div><dt>事件类型</dt><dd data-security-event-type>{{ securityEventTypeLabel(selected.eventType) }}</dd></div>
              <div><dt>区域</dt><dd>{{ selected.parkId }} · {{ selected.buildingId }}</dd></div>
              <div><dt>发生时间</dt><dd>{{ timeLabel(selected.openedAt) }} — {{ timeLabel(selected.lastOccurredAt) }}</dd></div>
              <div><dt>关联事件</dt><dd>{{ selected.eventIds.join('、') }}</dd></div>
              <div><dt>关联告警</dt><dd>{{ selected.alertIds.join('、') || '无' }}</dd></div>
              <div><dt>复核结论</dt><dd data-security-disposition>{{ securityDispositionLabel(selected.disposition) }}<template v-if="selected.dispositionSource"> · {{ selected.dispositionSource }}</template></dd></div>
              <div v-if="primaryEvidence?.sourceType"><dt>来源</dt><dd>{{ securitySourceTypeLabel(primaryEvidence.sourceType) }} · {{ primaryEvidence.eventSourceId || '未提供' }}</dd></div>
              <div v-if="primaryEvidence?.severity"><dt>严重度</dt><dd>{{ severityLabel(primaryEvidence.severity) }}</dd></div>
              <div v-if="primaryEvidence?.confidence != null"><dt>置信度</dt><dd data-security-confidence>{{ confidenceLabel(primaryEvidence.confidence) }}</dd></div>
              <div v-if="selected.handoffWorkItemId"><dt>协同工作项</dt><dd><button type="button" data-security-action="open-handoff" @click="openExistingHandoff">{{ selected.handoffWorkItemId }}</button></dd></div>
            </dl>
            <div class="security-incident-columns">
              <div>
                <h3>脱敏证据</h3>
                <ul class="security-incident-evidence">
                  <li v-for="evidence in selected.evidence" :key="evidence.sourceId">
                    <strong>{{ evidence.sourceId }}</strong>
                    <span>{{ evidence.summary }}</span>
                    <small v-if="evidence.rawEventType || evidence.sourceType" data-security-evidence-source>
                      {{ evidence.rawEventType || '未提供原始类型' }}
                      · {{ evidence.sourceType ? securitySourceTypeLabel(evidence.sourceType) : '未知来源' }}
                      <template v-if="evidence.confidence != null"> · 置信度 {{ confidenceLabel(evidence.confidence) }}</template>
                    </small>
                  </li>
                </ul>
              </div>
              <div>
                <h3>事件时间线</h3>
                <ol class="security-incident-timeline">
                  <li v-for="entry in selected.timeline" :key="`${entry.sourceType}-${entry.sourceId}`"><time>{{ timeLabel(entry.occurredAt) }}</time><strong>{{ entry.sourceId }}</strong><span>{{ entry.label }}</span></li>
                </ol>
              </div>
            </div>
            <div class="security-incident-recommendations"><h3>建议动作</h3><p v-for="recommendation in selected.recommendations" :key="recommendation">{{ recommendation }}</p></div>
            <div class="security-incident-actions">
              <button type="button" data-security-action="review" :disabled="reviewDisabled()" @click="action('review', 'CONFIRMED_INCIDENT')">{{ actionLoading === 'review' ? '处理中…' : '确认事件' }}</button>
              <button type="button" data-security-action="review-false-positive" :disabled="reviewDisabled()" @click="action('review', 'FALSE_POSITIVE')">误报（人工复核结论）</button>
              <button type="button" data-security-action="review-inconclusive" :disabled="reviewDisabled()" @click="action('review', 'INCONCLUSIVE')">无法判定</button>
              <button type="button" data-security-action="review-duplicate" :disabled="reviewDisabled()" @click="action('review', 'DUPLICATE')">重复事件</button>
              <button type="button" data-security-action="handoff" :disabled="selected.status !== 'REVIEWED' || Boolean(selected.handoffWorkItemId) || actionLoading !== null" @click="action('handoff')">{{ actionLoading === 'handoff' ? '转出中…' : selected.handoffWorkItemId ? '已转为协同' : selected.status === 'OPEN' ? '请先记录研判' : '转为协同工作项' }}</button>
              <button v-if="selected.handoffWorkItemId" type="button" data-security-action="open-handoff" @click="openExistingHandoff">打开协同工作项</button>
            </div>
          </template>
        </section>
      </section>
    </template>
  </main>
</template>
