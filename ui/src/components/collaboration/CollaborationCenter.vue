<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listCollaborationWorkItems } from '../../services/workflowApi'
import type { DemoRole } from '../../types/workflow'
import type { CollaborationWorkItem, CollaborationWorkItemSource, CollaborationWorkItemStatus } from '../../types/collaborationCenter'
import './collaboration-center.css'

const props = withDefaults(defineProps<{ role: DemoRole; active?: boolean }>(), { active: true })
const emit = defineEmits<{ 'open-view': [view: 'workflow' | 'customer'] }>()
const items = ref<CollaborationWorkItem[]>([])
const loading = ref(false)
const failed = ref(false)
const source = ref<CollaborationWorkItemSource | ''>('')
const status = ref<CollaborationWorkItemStatus | ''>('')

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

async function load(): Promise<void> {
  if (!canRead.value) { items.value = []; failed.value = false; return }
  loading.value = true
  failed.value = false
  try {
    items.value = await listCollaborationWorkItems(props.role, {
      source: source.value || undefined,
      status: status.value || undefined,
      limit: 50,
    })
  } catch {
    items.value = []
    failed.value = true
  } finally { loading.value = false }
}

function open(item: CollaborationWorkItem): void {
  if (item.detailPath === 'workflow' || item.detailPath === 'customer') emit('open-view', item.detailPath)
}

watch([() => props.role, source, status, () => props.active], ([, , , active]) => { if (active) void load() })
onMounted(() => { void load() })
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
          <div class="collaboration-item__status"><strong>{{ statusLabel(item.status) }}</strong><small>{{ formatTime(item.updatedAt) }}</small><button type="button" @click="open(item)">{{ item.detailPath === 'workflow' ? '打开告警工作流' : '打开客服控制台' }}</button></div>
        </article>
        <p v-if="items.length === 0" class="panel collaboration-empty">当前没有可展示的工作项。</p>
      </section>
    </template>
  </main>
</template>
