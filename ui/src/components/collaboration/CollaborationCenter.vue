<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { listCollaborationSlaTrend, listCollaborationWorkItems, submitApproval, updateCustomerTicket } from '../../services/workflowApi'
import type { DemoRole } from '../../types/workflow'
import type { CollaborationSlaSnapshot, CollaborationWorkItem, CollaborationWorkItemSource, CollaborationWorkItemSlaState, CollaborationWorkItemStatus } from '../../types/collaborationCenter'
import CollaborationSlaTrendChart from './CollaborationSlaTrendChart.vue'
import { createRequestId } from '../../utils/requestId'
import './collaboration-center.css'

const props = withDefaults(defineProps<{ role: DemoRole; active?: boolean; focusWorkItemId?: string | null; refreshToken?: number }>(), { active: true, focusWorkItemId: null, refreshToken: 0 })
const emit = defineEmits<{ 'open-view': [view: 'workflow' | 'customer' | 'security-incident', workflowId?: string, ticketId?: string] }>()
const items = ref<CollaborationWorkItem[]>([])
const loading = ref(false)
const failed = ref(false)
const source = ref<CollaborationWorkItemSource | ''>('')
const status = ref<CollaborationWorkItemStatus | ''>('')
const sortMode = ref<'sla' | 'updatedAt'>('sla')
const selectedItem = ref<CollaborationWorkItem | null>(null)
const trendSnapshots = ref<CollaborationSlaSnapshot[]>([])
const trendLoading = ref(false)
const trendFailed = ref(false)
const actionBusy = ref(false)
const actionError = ref('')
const approvalReviewer = ref('')
const approvalComment = ref('')
type ApprovalAttempt = {
  itemId: string
  decision: 'APPROVE' | 'REJECT'
  reviewer: string
  comment: string
  idempotencyKey: string
}
const approvalAttempts = new Map<string, ApprovalAttempt>()
const pendingActionItems = new Set<string>()
let requestGeneration = 0
let refreshTimer: ReturnType<typeof setInterval> | undefined
const drawer = ref<HTMLElement | null>(null)
const lastTrigger = ref<HTMLElement | null>(null)
const queueHeading = ref<HTMLElement | null>(null)
const SLA_REFRESH_INTERVAL_MS = 30_000

const canRead = computed(() => ['ADMIN', 'APPROVER', 'CUSTOMER_AGENT'].includes(props.role))
const canApproveSelected = computed(() => (props.role === 'ADMIN' || props.role === 'APPROVER')
  ? selectedItem.value?.source === 'ALERT_WORKFLOW' && selectedItem.value.status === 'WAITING_APPROVAL'
  : false)
const customerNextStatus = computed(() => {
  if (selectedItem.value?.source !== 'CUSTOMER_TICKET') return null
  return ({ WAITING_AGENT: 'ASSIGNED', ASSIGNED: 'IN_PROGRESS', IN_PROGRESS: 'RESOLVED', WAITING_CUSTOMER: 'IN_PROGRESS', RESOLVED: 'CLOSED' } as Record<string, string>)[selectedItem.value.status] ?? null
})
const attentionCount = computed(() => items.value.filter(item => ['WAITING_APPROVAL', 'FAILED', 'WORK_ORDER_FAILED', 'WAITING_AGENT'].includes(item.status)).length)
const slaOverview = computed(() => ({
  total: items.value.length,
  overdue: items.value.filter(item => item.slaState === 'OVERDUE').length,
  dueSoon: items.value.filter(item => item.slaState === 'DUE_SOON').length,
  onTrack: items.value.filter(item => item.slaState === 'ON_TRACK').length,
}))
const slaRank: Record<CollaborationWorkItemSlaState, number> = {
  OVERDUE: 0, DUE_SOON: 1, ON_TRACK: 2, NOT_APPLICABLE: 3, COMPLETED: 4,
}
const activeSlaStates = new Set<CollaborationWorkItemSlaState>(['OVERDUE', 'DUE_SOON', 'ON_TRACK'])
function isActiveSlaState(value?: CollaborationWorkItemSlaState): boolean {
  return value !== undefined && activeSlaStates.has(value)
}
function timeValue(value: string | null): number {
  const timestamp = value ? new Date(value).getTime() : Number.NaN
  return Number.isNaN(timestamp) ? Number.POSITIVE_INFINITY : timestamp
}
const sortedItems = computed(() => [...items.value].sort((left, right) => {
  if (sortMode.value === 'sla') {
    const rankDifference = (slaRank[left.slaState ?? 'NOT_APPLICABLE'] ?? slaRank.NOT_APPLICABLE)
      - (slaRank[right.slaState ?? 'NOT_APPLICABLE'] ?? slaRank.NOT_APPLICABLE)
    if (rankDifference !== 0) return rankDifference
    if (isActiveSlaState(left.slaState) && isActiveSlaState(right.slaState)) {
      const deadlineDifference = timeValue(left.slaDueAt) - timeValue(right.slaDueAt)
      if (deadlineDifference !== 0) return deadlineDifference
    }
  }
  const updatedDifference = timeValue(right.updatedAt) - timeValue(left.updatedAt)
  if (updatedDifference !== 0) return updatedDifference
  return left.id.localeCompare(right.id)
}))
const statusLabels: Record<CollaborationWorkItemStatus, string> = {
  RUNNING: '执行中', WAITING_APPROVAL: '待审批', COMPLETED: '已完成', REJECTED: '已拒绝', FAILED: '执行失败',
  WORK_ORDER_FAILED: '工单失败', WAITING_AGENT: '待客服接入', ASSIGNED: '已分派', IN_PROGRESS: '处理中',
  WAITING_CUSTOMER: '待用户回复', RESOLVED: '已解决', CLOSED: '已关闭', CANCELLED: '已取消',
}
const sourceLabels: Record<CollaborationWorkItemSource, string> = {
  ALERT_WORKFLOW: '告警处置', CUSTOMER_TICKET: '客服工单', SECURITY_INCIDENT: '安全事件',
}

function statusLabel(value: CollaborationWorkItemStatus): string { return statusLabels[value] ?? '无法识别' }
function sourceLabel(value: CollaborationWorkItemSource): string { return sourceLabels[value] ?? '无法识别' }
function detailLabel(value: CollaborationWorkItem['detailPath']): string {
  return ({ workflow: '打开告警工作流', customer: '打开客服控制台', 'security-incident': '打开安全事件' } as Record<CollaborationWorkItem['detailPath'], string>)[value]
}
function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN', { hour12: false })
}

function formatOptionalTime(value: string | null): string { return value ? formatTime(value) : '未提供' }
function locationLabel(item: CollaborationWorkItem): string {
  return [item.parkId, item.buildingId, item.deviceId].filter(Boolean).join(' · ') || '未提供'
}
const slaLabels: Record<CollaborationWorkItemSlaState, string> = {
  ON_TRACK: '正常', DUE_SOON: '即将到期', OVERDUE: '已超时', COMPLETED: '已完成', NOT_APPLICABLE: '不适用',
}
function slaLabel(value?: CollaborationWorkItemSlaState): string { return value ? (slaLabels[value] ?? '不适用') : '不适用' }
function slaClass(value?: CollaborationWorkItemSlaState): string { return `sla-${(value ?? 'NOT_APPLICABLE').toLowerCase().replace('_', '-')}` }

async function load(preserveItems = false): Promise<void> {
  const generation = ++requestGeneration
  if (!canRead.value) {
    items.value = []
    trendSnapshots.value = []
    trendLoading.value = false
    failed.value = false
    trendFailed.value = false
    closeDetails(false)
    return
  }
  if (!preserveItems) {
    items.value = []
    trendSnapshots.value = []
    trendFailed.value = false
    closeDetails(false)
  }
  loading.value = true
  failed.value = false
  try {
    const nextItems = await listCollaborationWorkItems(props.role, {
      source: source.value || undefined,
      status: status.value || undefined,
      limit: 50,
      sort: sortMode.value,
    })
    if (generation !== requestGeneration) return
    let visibleItems = nextItems
    if (props.focusWorkItemId && !nextItems.some(item => item.id === props.focusWorkItemId)) {
      const focusedItems = await listCollaborationWorkItems(props.role, {
        workItemId: props.focusWorkItemId,
        limit: 1,
        sort: 'updatedAt',
      })
      if (generation !== requestGeneration) return
      visibleItems = [...nextItems, ...focusedItems.filter(item => !nextItems.some(existing => existing.id === item.id))]
    }
    items.value = visibleItems
    reconcileSelectedItem(visibleItems)
    void loadTrend(generation)
  } catch {
    if (generation !== requestGeneration) return
    items.value = []
    trendSnapshots.value = []
    trendLoading.value = false
    trendFailed.value = false
    failed.value = true
    closeDetails(false)
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

async function loadTrend(generation: number): Promise<void> {
  trendLoading.value = true
  trendFailed.value = false
  try {
    const nextTrend = await listCollaborationSlaTrend(props.role, 60)
    if (generation !== requestGeneration) return
    trendSnapshots.value = nextTrend
  } catch {
    if (generation !== requestGeneration) return
    trendFailed.value = true
  } finally {
    if (generation === requestGeneration) trendLoading.value = false
  }
}

function openScene(item: CollaborationWorkItem): void {
  if (item.detailPath === 'workflow' || item.detailPath === 'customer' || item.detailPath === 'security-incident') {
    if (item.detailPath === 'workflow') {
      emit('open-view', item.detailPath, item.id.replace(/^ALERT_WORKFLOW:/, ''))
    } else if (item.detailPath === 'customer') {
      emit('open-view', item.detailPath, undefined, item.id.replace(/^CUSTOMER_TICKET:/, ''))
    } else {
      emit('open-view', item.detailPath, item.incidentId ?? item.id.replace(/^SECURITY_INCIDENT:/, ''))
    }
  }
}

function reconcileSelectedItem(nextItems: CollaborationWorkItem[]): void {
  if (!selectedItem.value) return
  const refreshedItem = nextItems.find(item => item.id === selectedItem.value?.id)
  if (refreshedItem) {
    selectedItem.value = refreshedItem
    actionBusy.value = pendingActionItems.has(refreshedItem.id)
    if (refreshedItem.source === 'ALERT_WORKFLOW' && refreshedItem.status !== 'WAITING_APPROVAL') {
      approvalAttempts.delete(refreshedItem.id)
    }
  } else {
    approvalAttempts.delete(selectedItem.value.id)
    closeDetails(false)
    void nextTick(() => queueHeading.value?.focus())
  }
}
function resetDisplayedActionState(): void {
  actionError.value = ''
  actionBusy.value = false
}
function openDetails(item: CollaborationWorkItem, event: MouseEvent): void {
  resetDisplayedActionState()
  lastTrigger.value = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  selectedItem.value = item
  actionBusy.value = pendingActionItems.has(item.id)
  approvalReviewer.value = ''
  approvalComment.value = ''
}
function closeDetails(restoreFocus = true): void {
  resetDisplayedActionState()
  const trigger = lastTrigger.value
  selectedItem.value = null
  lastTrigger.value = null
  approvalReviewer.value = ''
  approvalComment.value = ''
  if (restoreFocus) void nextTick(() => trigger?.focus())
}
function handleCloseClick(): void { closeDetails() }

function beginAction(itemId: string): void {
  pendingActionItems.add(itemId)
  actionBusy.value = true
  actionError.value = ''
}
function isCurrentAction(itemId: string): boolean {
  return selectedItem.value?.id === itemId
}

async function approveSelected(decision: 'APPROVE' | 'REJECT'): Promise<void> {
  const item = selectedItem.value
  if (!item || !canApproveSelected.value || actionBusy.value) return
  if (!approvalReviewer.value.trim() || !approvalComment.value.trim()) {
    actionError.value = '请填写审批人和审批意见'
    return
  }
  const reviewer = approvalReviewer.value.trim()
  const comment = approvalComment.value.trim()
  const previousAttempt = approvalAttempts.get(item.id)
  const attempt = previousAttempt?.decision === decision
    && previousAttempt.reviewer === reviewer
    && previousAttempt.comment === comment
    ? previousAttempt
    : {
        itemId: item.id,
        decision,
        reviewer,
        comment,
        idempotencyKey: createRequestId(),
      }
  approvalAttempts.set(item.id, attempt)
  beginAction(item.id)
  try {
    await submitApproval(item.id.replace(/^ALERT_WORKFLOW:/, ''), {
      decision,
      reviewer,
      comment,
      idempotencyKey: attempt.idempotencyKey,
    }, props.role)
    if (approvalAttempts.get(item.id) === attempt) approvalAttempts.delete(item.id)
    await load(true)
  } catch (cause) {
    await load(true)
    if (isCurrentAction(item.id)) {
      actionError.value = cause instanceof Error ? cause.message : '人工处理失败，请稍后重试'
    }
  } finally {
    pendingActionItems.delete(item.id)
    if (isCurrentAction(item.id)) actionBusy.value = false
  }
}

async function advanceSelectedTicket(): Promise<void> {
  const item = selectedItem.value
  const nextStatus = customerNextStatus.value
  if (!item || !nextStatus || actionBusy.value) return
  beginAction(item.id)
  try {
    await updateCustomerTicket(item.id.replace(/^CUSTOMER_TICKET:/, ''), nextStatus, props.role)
    await load(true)
  } catch (cause) {
    await load(true)
    if (isCurrentAction(item.id)) {
      actionError.value = cause instanceof Error ? cause.message : '人工处理失败，请稍后重试'
    }
  } finally {
    pendingActionItems.delete(item.id)
    if (isCurrentAction(item.id)) actionBusy.value = false
  }
}
function handleDrawerKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDetails()
    return
  }
  if (event.key !== 'Tab' || !drawer.value) return
  const focusable = Array.from(drawer.value.querySelectorAll<HTMLElement>(
    'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
  )).filter(element => !element.hasAttribute('disabled'))
  if (focusable.length === 0) {
    event.preventDefault()
    drawer.value.focus()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(
  [() => props.role, source, status, sortMode, () => props.active, () => props.focusWorkItemId, () => props.refreshToken],
  ([role, nextSource, nextStatus, nextSort, active], [previousRole, previousSource, previousStatus, previousSort]) => {
    const sortOnlyChange = role === previousRole && nextSource === previousSource
      && nextStatus === previousStatus && nextSort !== previousSort
    if (active) void load(sortOnlyChange)
    else {
      requestGeneration++
      trendLoading.value = false
      trendSnapshots.value = []
      trendFailed.value = false
      closeDetails(false)
    }
  },
)
watch(selectedItem, (item, previousItem) => {
  if (item && !previousItem) {
    void nextTick(() => drawer.value?.querySelector<HTMLElement>('[data-drawer-close]')?.focus())
  }
})
onMounted(() => {
  if (props.active) void load()
  refreshTimer = setInterval(() => {
    if (props.active && canRead.value && !loading.value) void load(true)
  }, SLA_REFRESH_INTERVAL_MS)
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })
</script>

<template>
  <main class="main-content collaboration-center" data-collaboration-center>
    <section class="hero-row">
      <div>
        <span class="eyebrow">智能协同 · 安全处理队列</span>
        <h2>让每个工作项<br /><em>都有清晰的下一步</em></h2>
        <p class="hero-copy">统一查看并安全处理告警处置与客服工单，保留原场景的审批、权限和状态边界。</p>
      </div>
      <div class="hero-metrics"><div><strong>{{ items.length }}</strong><span>当前工作项</span></div><div><strong>{{ attentionCount }}</strong><span>需要关注</span></div><div><strong>受控</strong><span>执行模式</span></div></div>
    </section>

    <section v-if="canRead && !failed && (items.length > 0 || !loading)" class="panel collaboration-sla-overview" aria-label="SLA 总览">
      <div class="section-heading compact"><div><span class="eyebrow">SLA 总览</span><h2>当前队列的时限健康度</h2></div><span class="count-badge">当前队列 · 最多 50 条</span></div>
      <div class="collaboration-sla-overview__grid">
        <div class="collaboration-sla-card" data-sla-overview="total"><span>工作项总数</span><strong>{{ slaOverview.total }}</strong></div>
        <div class="collaboration-sla-card sla-overdue" data-sla-overview="overdue"><span>已超时</span><strong>{{ slaOverview.overdue }}</strong></div>
        <div class="collaboration-sla-card sla-due-soon" data-sla-overview="due-soon"><span>即将到期</span><strong>{{ slaOverview.dueSoon }}</strong></div>
        <div class="collaboration-sla-card" data-sla-overview="on-track"><span>正常</span><strong>{{ slaOverview.onTrack }}</strong></div>
      </div>
    </section>

    <p v-if="!canRead" class="collaboration-state" role="alert">当前角色无权读取协同队列。</p>
    <p v-else-if="loading && items.length === 0" class="collaboration-state" role="status">正在读取协同队列…</p>
    <p v-else-if="failed" class="collaboration-state is-error" role="alert">当前无法读取协同队列，请稍后重试。</p>
    <template v-else>
      <section v-if="active" class="panel collaboration-sla-trend" aria-label="SLA 趋势">
        <div class="section-heading compact"><div><span class="eyebrow">本次会话 SLA 趋势</span><h2>队列时限状态变化</h2></div><span class="count-badge" data-sla-trend-count>已采样 {{ trendSnapshots.length }} 个点 · 最多 120 点</span></div>
        <p v-if="trendFailed" class="collaboration-sla-trend__error" role="alert">当前无法读取 SLA 趋势，队列数据仍可正常使用。</p>
        <CollaborationSlaTrendChart v-else :snapshots="trendSnapshots" />
      </section>
      <section class="panel collaboration-filters" aria-label="协同队列筛选">
        <div class="section-heading compact"><div><span class="eyebrow">队列筛选</span><h2 ref="queueHeading" data-collaboration-queue-heading tabindex="-1">按来源与状态查看</h2></div><span class="count-badge">最多 50 条</span></div>
        <div class="collaboration-filter-row">
          <label>来源<select v-model="source"><option value="">全部来源</option><option value="ALERT_WORKFLOW">告警处置</option><option value="CUSTOMER_TICKET">客服工单</option><option value="SECURITY_INCIDENT">安全事件</option></select></label>
          <label>状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option></select></label>
          <label>排序<select v-model="sortMode" data-sla-sort><option value="sla">SLA 紧急度</option><option value="updatedAt">最近更新</option></select></label>
        </div>
      </section>
      <section class="collaboration-list" aria-label="工作项列表">
        <article v-for="item in sortedItems" :key="item.id" class="panel collaboration-item" :class="{ 'is-focused': item.id === props.focusWorkItemId }" :data-work-item="item.id">
          <div class="collaboration-item__main"><div class="collaboration-item__meta"><span>{{ sourceLabel(item.source) }}</span><span :class="['priority', item.priority === 'HIGH' ? 'is-high' : '']">{{ item.priority === 'HIGH' ? '高优先级' : '常规' }}</span><small>{{ item.id }}</small></div><h3>{{ item.title }}</h3><p>{{ item.safeSummary }}</p></div>
          <div class="collaboration-item__status"><strong>{{ statusLabel(item.status) }}</strong><span :class="['collaboration-sla', slaClass(item.slaState)]">{{ slaLabel(item.slaState) }}</span><small>{{ formatTime(item.updatedAt) }}</small><button type="button" data-work-item-open @click="openScene(item)">{{ detailLabel(item.detailPath) }}</button><button type="button" data-work-item-details @click="openDetails(item, $event)">查看详情</button></div>
        </article>
        <p v-if="items.length === 0" class="panel collaboration-empty">当前没有可展示的工作项。</p>
      </section>
    </template>
    <Teleport to="body">
      <div v-if="selectedItem" class="collaboration-drawer-backdrop" @click.self="handleCloseClick">
        <aside ref="drawer" class="collaboration-drawer" role="dialog" aria-modal="true" aria-labelledby="collaboration-drawer-title" tabindex="-1" @keydown="handleDrawerKeydown">
          <div class="collaboration-drawer__header"><div><span class="eyebrow">工作项详情 · 安全处理</span><h2 id="collaboration-drawer-title">{{ selectedItem.title }}</h2></div><button type="button" data-drawer-close aria-label="关闭详情" @click="handleCloseClick">×</button></div>
        <dl class="collaboration-drawer__facts"><div><dt>来源</dt><dd>{{ sourceLabel(selectedItem.source) }}</dd></div><div><dt>状态</dt><dd>{{ statusLabel(selectedItem.status) }}</dd></div><div><dt>优先级</dt><dd>{{ selectedItem.priority === 'HIGH' ? '高优先级' : '常规' }}</dd></div><div><dt>位置/设备</dt><dd>{{ locationLabel(selectedItem) }}</dd></div><div><dt>打开时间</dt><dd>{{ formatOptionalTime(selectedItem.openedAt) }}</dd></div><div><dt>更新时间</dt><dd>{{ formatTime(selectedItem.updatedAt) }}</dd></div></dl>
        <section class="collaboration-drawer__summary"><span class="eyebrow">安全摘要</span><p>{{ selectedItem.safeSummary }}</p></section>
        <section class="collaboration-drawer__sla"><div><span class="eyebrow">演示 SLA</span><strong :class="['collaboration-sla', slaClass(selectedItem.slaState)]">{{ slaLabel(selectedItem.slaState) }}</strong></div><small>截止时间：{{ formatOptionalTime(selectedItem.slaDueAt) }}</small></section>
        <section v-if="canApproveSelected" class="collaboration-drawer__processing" aria-label="告警审批">
          <span class="eyebrow">人工审批</span>
          <input v-model="approvalReviewer" data-approval-reviewer placeholder="审批人" aria-label="审批人" />
          <textarea v-model="approvalComment" data-approval-comment placeholder="审批意见" aria-label="审批意见" rows="2"></textarea>
          <div class="collaboration-drawer__processing-actions"><button type="button" data-collaboration-action="reject" :disabled="actionBusy" @click="approveSelected('REJECT')">拒绝</button><button type="button" data-collaboration-action="approve" :disabled="actionBusy" @click="approveSelected('APPROVE')">{{ actionBusy ? '提交中…' : '批准并创建工单' }}</button></div>
        </section>
        <section v-if="customerNextStatus && (props.role === 'CUSTOMER_AGENT' || props.role === 'ADMIN')" class="collaboration-drawer__processing" aria-label="客服工单处理">
          <span class="eyebrow">客服处理</span>
          <button type="button" data-collaboration-action="customer-next" :disabled="actionBusy" @click="advanceSelectedTicket">{{ actionBusy ? '处理中…' : `推进至${statusLabel(customerNextStatus as CollaborationWorkItemStatus)}` }}</button>
        </section>
        <p v-if="actionError" class="collaboration-drawer__action-error" data-collaboration-action-error role="alert">{{ actionError }}</p>
        <div class="collaboration-drawer__actions"><button type="button" @click="openScene(selectedItem)">{{ detailLabel(selectedItem.detailPath) }}</button><button type="button" @click="handleCloseClick">关闭</button></div>
        </aside>
      </div>
    </Teleport>
  </main>
</template>
