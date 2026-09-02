<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { listCollaborationWorkItems } from '../../services/workflowApi'
import type { DemoRole } from '../../types/workflow'
import type { CollaborationWorkItem, CollaborationWorkItemSource, CollaborationWorkItemSlaState, CollaborationWorkItemStatus } from '../../types/collaborationCenter'
import './collaboration-center.css'

const props = withDefaults(defineProps<{ role: DemoRole; active?: boolean }>(), { active: true })
const emit = defineEmits<{ 'open-view': [view: 'workflow' | 'customer', workflowId?: string, ticketId?: string] }>()
const items = ref<CollaborationWorkItem[]>([])
const loading = ref(false)
const failed = ref(false)
const source = ref<CollaborationWorkItemSource | ''>('')
const status = ref<CollaborationWorkItemStatus | ''>('')
const selectedItem = ref<CollaborationWorkItem | null>(null)
let requestGeneration = 0
let refreshTimer: ReturnType<typeof setInterval> | undefined
const drawer = ref<HTMLElement | null>(null)
const lastTrigger = ref<HTMLElement | null>(null)
const SLA_REFRESH_INTERVAL_MS = 30_000

const canRead = computed(() => props.role === 'ADMIN' || props.role === 'CUSTOMER_AGENT')
const attentionCount = computed(() => items.value.filter(item => ['WAITING_APPROVAL', 'FAILED', 'WORK_ORDER_FAILED', 'WAITING_AGENT'].includes(item.status)).length)
const statusLabels: Record<CollaborationWorkItemStatus, string> = {
  RUNNING: '执行中', WAITING_APPROVAL: '待审批', COMPLETED: '已完成', REJECTED: '已拒绝', FAILED: '执行失败',
  WORK_ORDER_FAILED: '工单失败', WAITING_AGENT: '待客服接入', ASSIGNED: '已分派', IN_PROGRESS: '处理中',
  WAITING_CUSTOMER: '待用户回复', RESOLVED: '已解决', CLOSED: '已关闭', CANCELLED: '已取消',
}
const sourceLabels: Record<CollaborationWorkItemSource, string> = {
  ALERT_WORKFLOW: '告警处置', CUSTOMER_TICKET: '客服工单',
}

function statusLabel(value: CollaborationWorkItemStatus): string { return statusLabels[value] ?? '无法识别' }
function sourceLabel(value: CollaborationWorkItemSource): string { return sourceLabels[value] ?? '无法识别' }
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

async function load(): Promise<void> {
  const generation = ++requestGeneration
  if (!canRead.value) {
    items.value = []
    failed.value = false
    selectedItem.value = null
    lastTrigger.value = null
    return
  }
  loading.value = true
  failed.value = false
  try {
    const nextItems = await listCollaborationWorkItems(props.role, {
      source: source.value || undefined,
      status: status.value || undefined,
      limit: 50,
    })
    if (generation !== requestGeneration) return
    items.value = nextItems
  } catch {
    if (generation !== requestGeneration) return
    items.value = []
    failed.value = true
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

function openScene(item: CollaborationWorkItem): void {
  if (item.detailPath === 'workflow' || item.detailPath === 'customer') {
    if (item.detailPath === 'workflow') {
      emit('open-view', item.detailPath, item.id.replace(/^ALERT_WORKFLOW:/, ''))
    } else {
      emit('open-view', item.detailPath, undefined, item.id.replace(/^CUSTOMER_TICKET:/, ''))
    }
  }
}
function openDetails(item: CollaborationWorkItem, event: MouseEvent): void {
  lastTrigger.value = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  selectedItem.value = item
}
function closeDetails(restoreFocus = true): void {
  const trigger = lastTrigger.value
  selectedItem.value = null
  lastTrigger.value = null
  if (restoreFocus) void nextTick(() => trigger?.focus())
}
function handleCloseClick(): void { closeDetails() }
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

watch([() => props.role, source, status, () => props.active], ([, , , active]) => {
  if (active) void load()
  else {
    requestGeneration++
    closeDetails(false)
  }
})
watch(selectedItem, (item) => {
  if (item) void nextTick(() => drawer.value?.querySelector<HTMLElement>('[data-drawer-close]')?.focus())
})
onMounted(() => {
  if (props.active) void load()
  refreshTimer = setInterval(() => {
    if (props.active && canRead.value) void load()
  }, SLA_REFRESH_INTERVAL_MS)
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })
</script>

<template>
  <main class="main-content collaboration-center" data-collaboration-center>
    <section class="hero-row">
      <div>
        <span class="eyebrow">智能协同 · 只读队列</span>
        <h2>让每个工作项<br /><em>都有清晰的下一步</em></h2>
        <p class="hero-copy">统一查看告警处置与客服工单，保留原场景的审批、权限和状态边界。</p>
      </div>
      <div class="hero-metrics"><div><strong>{{ items.length }}</strong><span>当前工作项</span></div><div><strong>{{ attentionCount }}</strong><span>需要关注</span></div><div><strong>只读</strong><span>执行模式</span></div></div>
    </section>

    <p v-if="!canRead" class="collaboration-state" role="alert">当前角色无权读取协同队列。</p>
    <p v-else-if="loading" class="collaboration-state" role="status">正在读取协同队列…</p>
    <p v-else-if="failed" class="collaboration-state is-error" role="alert">当前无法读取协同队列，请稍后重试。</p>
    <template v-else>
      <section class="panel collaboration-filters" aria-label="协同队列筛选">
        <div class="section-heading compact"><div><span class="eyebrow">队列筛选</span><h2>按来源与状态查看</h2></div><span class="count-badge">最多 50 条</span></div>
        <div class="collaboration-filter-row">
          <label>来源<select v-model="source"><option value="">全部来源</option><option value="ALERT_WORKFLOW">告警处置</option><option value="CUSTOMER_TICKET">客服工单</option></select></label>
          <label>状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option></select></label>
        </div>
      </section>
      <section class="collaboration-list" aria-label="工作项列表">
        <article v-for="item in items" :key="item.id" class="panel collaboration-item" :data-work-item="item.id">
          <div class="collaboration-item__main"><div class="collaboration-item__meta"><span>{{ sourceLabel(item.source) }}</span><span :class="['priority', item.priority === 'HIGH' ? 'is-high' : '']">{{ item.priority === 'HIGH' ? '高优先级' : '常规' }}</span><small>{{ item.id }}</small></div><h3>{{ item.title }}</h3><p>{{ item.safeSummary }}</p></div>
          <div class="collaboration-item__status"><strong>{{ statusLabel(item.status) }}</strong><span :class="['collaboration-sla', slaClass(item.slaState)]">{{ slaLabel(item.slaState) }}</span><small>{{ formatTime(item.updatedAt) }}</small><button type="button" data-work-item-open @click="openScene(item)">{{ item.detailPath === 'workflow' ? '打开告警工作流' : '打开客服控制台' }}</button><button type="button" data-work-item-details @click="openDetails(item, $event)">查看详情</button></div>
        </article>
        <p v-if="items.length === 0" class="panel collaboration-empty">当前没有可展示的工作项。</p>
      </section>
    </template>
    <Teleport to="body">
      <div v-if="selectedItem" class="collaboration-drawer-backdrop" @click.self="handleCloseClick">
        <aside ref="drawer" class="collaboration-drawer" role="dialog" aria-modal="true" aria-labelledby="collaboration-drawer-title" tabindex="-1" @keydown="handleDrawerKeydown">
          <div class="collaboration-drawer__header"><div><span class="eyebrow">工作项详情 · 只读</span><h2 id="collaboration-drawer-title">{{ selectedItem.title }}</h2></div><button type="button" data-drawer-close aria-label="关闭详情" @click="handleCloseClick">×</button></div>
        <dl class="collaboration-drawer__facts"><div><dt>来源</dt><dd>{{ sourceLabel(selectedItem.source) }}</dd></div><div><dt>状态</dt><dd>{{ statusLabel(selectedItem.status) }}</dd></div><div><dt>优先级</dt><dd>{{ selectedItem.priority === 'HIGH' ? '高优先级' : '常规' }}</dd></div><div><dt>位置/设备</dt><dd>{{ locationLabel(selectedItem) }}</dd></div><div><dt>打开时间</dt><dd>{{ formatOptionalTime(selectedItem.openedAt) }}</dd></div><div><dt>更新时间</dt><dd>{{ formatTime(selectedItem.updatedAt) }}</dd></div></dl>
        <section class="collaboration-drawer__summary"><span class="eyebrow">安全摘要</span><p>{{ selectedItem.safeSummary }}</p></section>
        <section class="collaboration-drawer__sla"><div><span class="eyebrow">演示 SLA</span><strong :class="['collaboration-sla', slaClass(selectedItem.slaState)]">{{ slaLabel(selectedItem.slaState) }}</strong></div><small>截止时间：{{ formatOptionalTime(selectedItem.slaDueAt) }}</small></section>
        <div class="collaboration-drawer__actions"><button type="button" @click="openScene(selectedItem)">{{ selectedItem.detailPath === 'workflow' ? '打开告警工作流' : '打开客服控制台' }}</button><button type="button" @click="handleCloseClick">关闭</button></div>
        </aside>
      </div>
    </Teleport>
  </main>
</template>
