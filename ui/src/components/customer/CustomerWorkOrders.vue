<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { BellFilled, Checked, Clock, Document, OfficeBuilding, Refresh, UserFilled, WarningFilled } from '@element-plus/icons-vue'
import { useWorkflow } from '../../composables/useWorkflow'
import { getAnomalyEvidence } from '../../services/operationsAnomalyApi'
import { getActionableAlert, listCollaborationWorkItems } from '../../services/workflowApi'
import type { CollaborationWorkItem, CollaborationWorkItemStatus } from '../../types/collaborationCenter'
import type { CustomerAnalysisContext } from '../../types/customer'
import type { AnomalyEvidence } from '../../types/operationsAnomaly'
import type { ActionableAlertResponse, DemoRole, WorkflowResponse } from '../../types/workflow'
import { customerRecommendations } from '../../utils/customerRecommendations'

const props = withDefaults(defineProps<{
  context: CustomerAnalysisContext | null
  active?: boolean
  demoRole?: DemoRole
}>(), { active: true, demoRole: 'APPROVER' })

const emit = defineEmits<{ back: [] }>()
const workflow = useWorkflow()
const evidence = ref<AnomalyEvidence | null>(null)
const actionableAlert = ref<ActionableAlertResponse | null>(null)
const workItems = ref<CollaborationWorkItem[]>([])
const loading = ref(false)
const errors = ref({ evidence: '', identity: '', items: '' })
const filter = ref<'all' | 'pending' | 'processing'>('all')
const confirmationOpen = ref(false)
const confirmationButton = ref<HTMLButtonElement | null>(null)
const confirmationTrigger = ref<HTMLElement | null>(null)
const outcomeMessage = ref('')
let requestGeneration = 0
let loadedContextKey = ''
let loadingContextKey = ''
let workflowAlertId = ''

const contextKey = computed(() => props.context
  ? JSON.stringify({ alertId: props.context.anomalyId, buildingId: props.context.buildingId, window: props.context.anomalyWindow })
  : '')

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '时间未提供'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return '时间未提供'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: props.context?.anomalyWindow.timezone ?? 'Asia/Shanghai',
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

const sourceAlert = computed(() => {
  const alertId = props.context?.anomalyId
  if (!alertId || evidence.value?.domainStatus.alerts === 'UNAVAILABLE') return null
  return evidence.value?.alerts.find((item) => text(item.alertId) === alertId) ?? null
})

const actionability = computed(() => {
  const context = props.context
  if (!context) return { allowed: false, reason: '请先从运营分析选择同一事件。' }
  if (!context.anomalyId) return { allowed: false, reason: '当前分析没有可核验的告警标识，不能创建工单。' }
  if (loading.value) return { allowed: false, reason: '正在核验分析异常与可建单告警的对应关系。' }
  if (errors.value.evidence) return { allowed: false, reason: errors.value.evidence }
  if (evidence.value?.domainStatus.alerts === 'UNAVAILABLE') {
    return { allowed: false, reason: '告警依据暂不可用，不能确认同一事件。' }
  }
  const observed = sourceAlert.value
  if (!observed) return { allowed: false, reason: '当前窗口未找到分析页选中的同一告警，已阻止建单。' }
  if (errors.value.identity || !actionableAlert.value) {
    return { allowed: false, reason: errors.value.identity || '工作流暂未确认该告警可建单。' }
  }
  const accepted = actionableAlert.value
  const sameIdentity = accepted.alertId === context.anomalyId
    && accepted.buildingId === context.buildingId
    && accepted.deviceId === text(observed.deviceId)
    && accepted.category === text(observed.category)
  return sameIdentity
    ? { allowed: true, reason: `已核验同一告警 ${accepted.alertId}：楼宇、设备与类别一致。` }
    : { allowed: false, reason: '分析告警与工作流可接受对象不一致，已阻止建单；不会替换成另一条告警。' }
})

const recommendations = computed(() => props.context ? customerRecommendations(props.context) : [])
const currentStatus = computed(() => {
  const response = workflow.workflow.value
  if (response?.workOrder) return workOrderStatusLabel(response.workOrder.status)
  return response ? workflowStatusLabel(response.status) : '待人工确认'
})

const progress = computed(() => {
  const response = workflow.workflow.value
  return [
    { label: '发现异常', done: Boolean(props.context), at: formatTime(text(sourceAlert.value?.occurredAt)) },
    { label: '完成分析', done: Boolean(props.context), at: props.context ? '已查看处理建议' : '尚未进入' },
    { label: '人工确认', done: Boolean(response?.approval), at: response?.approval ? formatTime(response.approval.decidedAt) : '等待确认' },
    { label: '创建工单', done: Boolean(response?.workOrder), at: response?.workOrder ? response.workOrder.id : '尚未创建' },
    { label: response?.workOrder ? workOrderStatusLabel(response.workOrder.status) : '等待处理', done: false, at: response?.workOrder ? '来自后端实际状态' : '工单创建后显示' },
  ]
})

const matchingWorkItem = computed(() => {
  const workflowId = workflow.workflow.value?.workflowId
  return workflowId ? workItems.value.find((item) => item.id === `ALERT_WORKFLOW:${workflowId}`) ?? null : null
})

const listRows = computed(() => {
  const current = props.context ? [{
    id: props.context.anomalyId ?? `analysis:${props.context.buildingId}`,
    type: workflow.workflow.value?.workOrder ? '工单' : '事件',
    title: props.context.title,
    location: `${props.context.buildingId} · ${props.context.buildingName}`,
    status: currentStatus.value,
    priority: props.context.priority,
    selected: true,
    rawStatus: workflow.workflow.value?.status ?? 'WAITING_APPROVAL',
  }] : []
  const others = workItems.value
    .filter((item) => !props.context?.anomalyId || !item.title.includes(props.context.anomalyId))
    .slice(0, 8)
    .map((item) => ({
      id: item.id,
      type: item.source === 'ALERT_WORKFLOW' ? '事件' : item.source === 'CUSTOMER_TICKET' ? '服务工单' : '安全事项',
      title: item.title,
      location: item.buildingId ? `${item.buildingId} · ${item.deviceId ?? '设备未提供'}` : '园区事项',
      status: collaborationStatusLabel(item.status),
      priority: item.priority === 'HIGH' ? '高' : '关注',
      selected: false,
      rawStatus: item.status,
    }))
  const rows = [...current, ...others]
  if (filter.value === 'pending') return rows.filter((item) => ['WAITING_APPROVAL', 'WAITING_AGENT', 'ASSIGNED'].includes(item.rawStatus))
  if (filter.value === 'processing') return rows.filter((item) => ['RUNNING', 'IN_PROGRESS'].includes(item.rawStatus))
  return rows
})

const pendingEventCount = computed(() => evidence.value?.domainStatus.alerts === 'UNAVAILABLE'
  ? null
  : evidence.value?.alerts.filter((item) => text(item.status) === 'OPEN').length ?? null)
const processingCount = computed(() => workItems.value.filter((item) => ['RUNNING', 'IN_PROGRESS'].includes(item.status)).length)
const endedCount = computed(() => workItems.value.filter((item) => ['REJECTED', 'FAILED', 'WORK_ORDER_FAILED', 'RESOLVED', 'CLOSED', 'CANCELLED'].includes(item.status)
  || (item.source === 'SECURITY_INCIDENT' && item.status === 'COMPLETED')).length)

function workflowStatusLabel(status: WorkflowResponse['status']): string {
  return ({
    RUNNING: '分析处理中', WAITING_APPROVAL: '待人工确认', COMPLETED: '工单流程已完成', REJECTED: '已拒绝建单',
    FAILED: '分析失败', WORK_ORDER_FAILED: '建单失败',
  })[status]
}

function collaborationStatusLabel(status: CollaborationWorkItemStatus): string {
  return ({
    RUNNING: '执行中', WAITING_APPROVAL: '待确认', COMPLETED: '流程已完成', REJECTED: '已拒绝', FAILED: '执行失败',
    WORK_ORDER_FAILED: '建单失败', WAITING_AGENT: '待接入', ASSIGNED: '已分派', IN_PROGRESS: '处理中',
    WAITING_CUSTOMER: '待回复', RESOLVED: '已解决', CLOSED: '已关闭', CANCELLED: '已取消',
  })[status]
}

function workOrderStatusLabel(status: string): string {
  return ({ PENDING_EXECUTION: '已创建，待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CANCELLED: '已取消' } as Record<string, string>)[status]
    ?? '状态未知'
}

async function refreshWorkItems(expectedGeneration = requestGeneration): Promise<void> {
  try {
    const items = await listCollaborationWorkItems('CUSTOMER_AGENT', { limit: 50, sort: 'updatedAt' })
    if (expectedGeneration === requestGeneration) workItems.value = items
  } catch {
    if (expectedGeneration === requestGeneration) errors.value.items = '事项统计暂不可用。'
  }
}

async function refresh(): Promise<void> {
  if (!props.active) return
  const context = props.context
  const key = contextKey.value
  const generation = ++requestGeneration
  loadingContextKey = key
  errors.value = { evidence: '', identity: '', items: '' }
  loading.value = true
  if (!context) {
    if (workflowAlertId) workflow.reset()
    workflowAlertId = ''
    loadedContextKey = ''
    loadingContextKey = ''
    evidence.value = null
    actionableAlert.value = null
    workItems.value = []
    outcomeMessage.value = ''
    loading.value = false
    return
  }
  const alertId = text(context.anomalyId) ?? ''
  if (workflowAlertId && workflowAlertId !== alertId) {
    workflow.reset()
    workflowAlertId = ''
  }
  const sameLoadedContext = loadedContextKey === key
  if (!sameLoadedContext) {
    evidence.value = null
    actionableAlert.value = null
    workItems.value = []
    outcomeMessage.value = ''
  }
  const knownWorkflow = workflowAlertId === alertId ? workflow.workflow.value : null
  const evidencePromise = getAnomalyEvidence('VIEWER', context.buildingId, {
    from: context.anomalyWindow.from,
    to: context.anomalyWindow.to,
  })
  const identityPromise = context.anomalyId ? getActionableAlert(context.anomalyId) : Promise.resolve(null)
  const workflowPromise = knownWorkflow ? workflow.refresh() : Promise.resolve(null)
  const [evidenceResult, identityResult, refreshedWorkflow] = await Promise.all([
    Promise.resolve(evidencePromise).then((value) => ({ status: 'fulfilled', value }) as const, (reason) => ({ status: 'rejected', reason }) as const),
    Promise.resolve(identityPromise).then((value) => ({ status: 'fulfilled', value }) as const, (reason) => ({ status: 'rejected', reason }) as const),
    workflowPromise,
  ])
  if (generation !== requestGeneration) return
  if (evidenceResult.status === 'fulfilled') evidence.value = evidenceResult.value
  else errors.value.evidence = '同一事件的告警依据读取失败，已阻止建单。'
  if (identityResult.status === 'fulfilled') actionableAlert.value = identityResult.value
  else errors.value.identity = '该分析告警不在现有可建单告警端口中，已阻止建单。'
  loadedContextKey = key
  loadingContextKey = ''
  loading.value = false
  if (knownWorkflow?.workOrder) {
    const receipt = refreshedWorkflow?.workOrder ?? knownWorkflow.workOrder
    outcomeMessage.value = refreshedWorkflow
      ? `工单 ${receipt.id} 已创建，当前状态：${workOrderStatusLabel(receipt.status)}。创建工单不表示问题已解决。`
      : `工单 ${receipt.id} 的最新状态暂未确认；仍保留上次已知状态“${workOrderStatusLabel(receipt.status)}”，请稍后刷新重试。`
  }
  await refreshWorkItems(generation)
}

function refreshPage(): void {
  if (loading.value || workflow.loading.value || workflow.approving.value) return
  void refresh()
}

function openConfirmation(event: MouseEvent): void {
  if (!actionability.value.allowed || workflow.loading.value
    || workflow.approving.value || workflow.approvalNeedsRefresh.value) return
  confirmationTrigger.value = event.currentTarget as HTMLElement
  confirmationOpen.value = true
  outcomeMessage.value = ''
  void nextTick(() => confirmationButton.value?.focus())
}

function closeConfirmation(): void {
  if (workflow.loading.value || workflow.approving.value) return
  confirmationOpen.value = false
  void nextTick(() => confirmationTrigger.value?.focus())
}

function rejectCreation(): void {
  outcomeMessage.value = '已取消本次人工确认，没有创建工单。'
  closeConfirmation()
}

async function confirmCreation(): Promise<void> {
  const context = props.context
  const alertId = context?.anomalyId
  const expectedKey = contextKey.value
  if (!context || !alertId || !actionability.value.allowed
    || workflow.loading.value || workflow.approving.value || workflow.approvalNeedsRefresh.value) return
  let response = workflow.workflow.value
  const retryableFailure = response && !response.workOrder
    && ['FAILED', 'WORK_ORDER_FAILED'].includes(response.status)
  if (!response || retryableFailure) {
    workflowAlertId = alertId
    response = await workflow.start(alertId)
  }
  if (expectedKey !== contextKey.value || !response) return
  if (response.status === 'WAITING_APPROVAL') {
    await workflow.approve({
      decision: 'APPROVE',
      reviewer: 'customer-demo-approver',
      comment: `确认对 ${alertId} 创建演示工单`,
      role: props.demoRole,
    })
    response = workflow.workflow.value
  }
  if (expectedKey !== contextKey.value || !response) return
  if (response.workOrder) {
    outcomeMessage.value = `工单 ${response.workOrder.id} 已创建，当前状态：${workOrderStatusLabel(response.workOrder.status)}。创建工单不表示问题已解决。`
    confirmationOpen.value = false
    await refreshWorkItems()
    void nextTick(() => confirmationTrigger.value?.focus())
  } else if (!workflow.error.value) {
    outcomeMessage.value = response.status === 'REJECTED'
      ? '人工决定为拒绝，未创建工单。'
      : `后端返回 ${workflowStatusLabel(response.status)}，尚未确认工单已创建。`
  }
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeConfirmation()
  }
}

watch([() => props.active, contextKey], ([active, key]) => {
  if (!active) {
    requestGeneration++
    loadingContextKey = ''
    loading.value = false
    return
  }
  if (key !== loadedContextKey && key !== loadingContextKey) {
    void refresh()
  }
}, { immediate: true })
</script>

<template>
  <main id="customer-work-orders-main" class="customer-work-orders" data-customer-page="work-orders">
    <section v-if="!context" class="customer-card customer-work-orders__empty" role="status">
      <Document aria-hidden="true" />
      <h2>请先选择需要处理的事件</h2>
      <p>从园区总览进入运营分析并查看处理建议后，本页才会继承同一告警。</p>
      <button type="button" @click="emit('back')">返回运营分析</button>
    </section>

    <template v-else>
      <section class="customer-work-orders__kpis" aria-label="当前事件与事项统计">
        <article class="customer-card"><span class="is-red"><BellFilled /></span><div><small>待确认事件</small><strong>{{ pendingEventCount ?? '—' }}</strong><em>当前楼宇窗口</em></div></article>
        <article class="customer-card"><span class="is-blue"><Document /></span><div><small>处理中事项</small><strong>{{ processingCount }}</strong><em>当前协同全集</em></div></article>
        <article class="customer-card"><span class="is-green"><Checked /></span><div><small>已结束事项</small><strong>{{ endedCount }}</strong><em>不含待处理工单</em></div></article>
        <article class="customer-card"><span class="is-purple"><Clock /></span><div><small>平均处理时长</small><strong>未提供</strong><em>现有契约无可靠均值</em></div></article>
      </section>

      <p v-if="errors.items" class="customer-work-orders__notice is-warning" role="status">{{ errors.items }}</p>
      <p class="customer-work-orders__notice" :class="{ 'is-blocked': !actionability.allowed }" data-actionability>
        {{ actionability.reason }}
      </p>

      <section class="customer-work-orders__workspace">
        <aside class="customer-card customer-work-orders__queue" aria-label="事项列表">
          <header><div><Document /><h2>待处理事项</h2></div><button type="button" data-refresh-work-orders :disabled="loading || workflow.loading.value || workflow.approving.value" @click="refreshPage"><Refresh />刷新</button></header>
          <div class="customer-work-orders__filters" role="group" aria-label="事项状态筛选">
            <button v-for="option in [{ id: 'all', label: '全部' }, { id: 'pending', label: '待确认' }, { id: 'processing', label: '处理中' }]"
              :key="option.id" type="button" :class="{ 'is-current': filter === option.id }" @click="filter = option.id as typeof filter">
              {{ option.label }}
            </button>
          </div>
          <div class="customer-work-orders__items">
            <article v-for="item in listRows" :key="item.id" :class="{ 'is-selected': item.selected }">
              <span class="customer-work-orders__item-icon"><OfficeBuilding /></span>
              <div><small>{{ item.type }} · {{ item.priority }}优先级</small><strong>{{ item.title }}</strong><em>{{ item.location }}</em></div>
              <b>{{ item.status }}</b>
            </article>
            <p v-if="!listRows.length" class="customer-work-orders__empty-list">当前筛选范围没有事项。</p>
          </div>
          <footer>共 {{ listRows.length }} 项 · 关联后同一告警不重复计数</footer>
        </aside>

        <section class="customer-work-orders__detail">
          <article class="customer-card customer-work-orders__summary">
            <header><div><OfficeBuilding /><span><small>{{ context.buildingId }} · {{ context.buildingName }}</small><h2>{{ context.title }}</h2></span></div><b>{{ context.priority }}</b></header>
            <p>{{ context.summary?.energyDeviationPct == null ? '当前能耗偏差未取得。' : `当前窗口能耗较基线偏差 ${context.summary.energyDeviationPct}%` }} 相关线索仍需现场人工核查，工单仅用于推动后续处理。</p>
          </article>

          <article class="customer-card customer-work-orders__progress">
            <header><h2>业务进度</h2><small>状态来自分析上下文、人工决定和工单回执</small></header>
            <ol>
              <li v-for="step in progress" :key="step.label" :class="{ 'is-done': step.done }"><span><Checked v-if="step.done" /><i v-else></i></span><strong>{{ step.label }}</strong><small>{{ step.at }}</small></li>
            </ol>
          </article>

          <article class="customer-card customer-work-orders__recommendations">
            <header><div><WarningFilled /><h2>处理建议</h2></div><small>建议不等于已执行</small></header>
            <ol><li v-for="(item, index) in recommendations" :key="item"><span>{{ index + 1 }}</span>{{ item }}</li></ol>
          </article>

          <article class="customer-card customer-work-orders__history">
            <header><h2>处理记录</h2><small>仅展示客户可理解的已发生事实</small></header>
            <div v-for="step in progress.filter((item) => item.done)" :key="step.label"><time>{{ step.at }}</time><span><b>{{ step.label }}</b><small>{{ step.label === '创建工单' ? '已取得后端工单回执，尚未代表问题解决。' : '该业务阶段已有可核验记录。' }}</small></span></div>
          </article>
        </section>

        <aside class="customer-work-orders__right">
          <article class="customer-card customer-work-orders__order">
            <header><div><Document /><h2>工单信息</h2></div><b>{{ currentStatus }}</b></header>
            <dl>
              <div><dt>工单编号</dt><dd data-work-order-id>{{ workflow.workflow.value?.workOrder?.id ?? '尚未创建' }}</dd></div>
              <div><dt>实际状态</dt><dd data-work-order-status>{{ workflow.workflow.value?.workOrder ? workOrderStatusLabel(workflow.workflow.value.workOrder.status) : '尚无工单状态' }}</dd></div>
              <div><dt>责任部门</dt><dd>后端未提供</dd></div>
              <div><dt>负责人</dt><dd><UserFilled /> 后端未提供</dd></div>
              <div><dt>处理期限</dt><dd>{{ matchingWorkItem?.slaDueAt ? formatTime(matchingWorkItem.slaDueAt) : '后端未提供' }}</dd></div>
              <div><dt>告警编号</dt><dd>{{ context.anomalyId ?? '未提供' }}</dd></div>
            </dl>
          </article>

          <article class="customer-card customer-work-orders__participants">
            <header><h2>参与方</h2><small>现有职责边界</small></header>
            <div><span class="is-blue"><UserFilled /></span><p><strong>园区管理方</strong><small>人工确认</small></p></div>
            <div><span class="is-green"><Document /></span><p><strong>现有告警工作流</strong><small>诊断与幂等建单</small></p></div>
          </article>

          <article class="customer-card customer-work-orders__actions">
            <h2>业务动作</h2>
            <button type="button" data-confirm-work-order :disabled="!actionability.allowed || Boolean(workflow.workflow.value?.workOrder) || workflow.loading.value || workflow.approving.value || workflow.approvalNeedsRefresh.value" @click="openConfirmation">
              <Checked /> {{ workflow.workflow.value?.workOrder ? '工单已创建' : '人工确认并创建工单' }}
            </button>
            <p v-if="workflow.error.value" role="alert">{{ workflow.error.value }}</p>
            <p v-if="outcomeMessage" data-work-order-outcome role="status">{{ outcomeMessage }}</p>
            <small>创建成功仅表示已生成待处理工单；催办、派发、完工等未支持动作不在本页模拟。</small>
          </article>
        </aside>
      </section>
    </template>

    <div v-if="confirmationOpen" class="customer-work-orders__dialog-backdrop" @keydown="onDialogKeydown">
      <section class="customer-work-orders__dialog" role="dialog" aria-modal="true" aria-labelledby="work-order-confirm-title">
        <header><WarningFilled /><div><h2 id="work-order-confirm-title">确认创建演示工单</h2><p>本次操作绑定告警 {{ context?.anomalyId }}，不会因后台选择变化处理其他对象。</p></div></header>
        <dl>
          <div><dt>问题</dt><dd>{{ context?.title }}</dd></div>
          <div><dt>建议</dt><dd>{{ recommendations[0] }}</dd></div>
          <div><dt>操作结果</dt><dd>调用现有告警工作流；若风险闸门要求审批，将以当前演示角色提交人工确认，再读取实际工单回执。</dd></div>
        </dl>
        <p v-if="workflow.error.value" role="alert">{{ workflow.error.value }}</p>
        <footer>
          <button type="button" :disabled="workflow.loading.value || workflow.approving.value" @click="rejectCreation">取消，不建单</button>
          <button ref="confirmationButton" type="button" data-submit-work-order :disabled="workflow.loading.value || workflow.approving.value || workflow.approvalNeedsRefresh.value" @click="confirmCreation">
            {{ workflow.loading.value ? '正在分析告警…' : workflow.approving.value ? '正在提交确认…' : workflow.approvalNeedsRefresh.value ? '请先刷新状态' : '确认并执行' }}
          </button>
        </footer>
      </section>
    </div>
  </main>
</template>

<style scoped src="./customer-work-orders.css"></style>
