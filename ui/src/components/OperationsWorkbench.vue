<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AlertSelector from './AlertSelector.vue'
import WorkflowGraph from './WorkflowGraph.vue'
import DemoConsole from './DemoConsole.vue'
import EventTimeline from './EventTimeline.vue'
import CustomerServiceConsole from './CustomerServiceConsole.vue'
import ExecutionTraceRail from './execution/ExecutionTraceRail.vue'
import ImmersiveWorkbenchShell from './workbench/ImmersiveWorkbenchShell.vue'
import OperationsAnalysisPage from './analytics/OperationsAnalysisPage.vue'
import ExpertCollaborationPage from './ExpertCollaborationPage.vue'
import VoiceAssistantPage from './voice/VoiceAssistantPage.vue'
import GovernanceCenter from './governance/GovernanceCenter.vue'
import OperationsBoard from './operations/OperationsBoard.vue'
import CollaborationCenter from './collaboration/CollaborationCenter.vue'
import SecurityIncidentCenter from './security/SecurityIncidentCenter.vue'
import { demoAlerts, type DemoRole } from '../types/workflow'
import { useWorkflow } from '../composables/useWorkflow'
import { useExecutionTrace } from '../composables/useExecutionTrace'
import { useGuidedLaunch } from '../composables/useGuidedLaunch'
import { getOperationsCapabilities, submitFeedback } from '../services/workflowApi'
import { customerIntentLabel, workflowNodeLabel } from '../utils/labels'
import { alertWorkflowRunId } from '../utils/runId'
import type { GuidedLaunchUpdate, ScenarioLaunchRequest, ShowcaseLaunchInput, ShowcaseScenarioId, WorkbenchEvidenceItem, WorkbenchNavItem, WorkbenchView } from '../types/workbench'
import '../styles/workbench-primitives.css'
import '../styles/workflow.css'

const props = withDefaults(defineProps<{ initialView?: WorkbenchView; launchRequest?: ScenarioLaunchRequest | null; active?: boolean }>(), {
  initialView: 'workflow',
  launchRequest: null,
  active: true,
})
const emit = defineEmits<{
  'back-to-showcase': []
  'retry-guided-launch': [scenarioId: ShowcaseScenarioId, launchInput: ShowcaseLaunchInput]
}>()

const capabilities = ref<{ knowledgeMode: string; customerAnswerMode: string; vectorStore: string; analyticsEnabled: boolean; collaborationEnabled: boolean; voiceEnabled: boolean } | null>(null)
const capabilityLoadState = ref<'loading' | 'ready' | 'failed'>('loading')
const capabilityLabels = computed(() => capabilities.value ? {
  knowledge: capabilities.value.knowledgeMode === 'mock' ? 'Mock' : 'RAG',
  customer: capabilities.value.customerAnswerMode === 'mock' ? 'Mock' : 'DashScope',
  vector: capabilities.value.vectorStore === 'none' ? '无向量库（关键词检索）' : 'SimpleVectorStore（进程内）',
} : null)
const knowledgeEvidence = computed<Pick<WorkbenchEvidenceItem, 'value' | 'tone'>>(() => {
  if (capabilityLoadState.value === 'loading') return { value: '检查中' }
  if (capabilityLoadState.value === 'failed') return { value: '能力检查失败', tone: 'warning' }
  return { value: capabilityLabels.value?.knowledge ?? '未知', tone: 'verified' }
})
const navItems = computed<WorkbenchNavItem[]>(() => [
  { value: 'workflow', label: '告警工作流', available: true },
  { value: 'customer', label: '园区客服', available: true },
  { value: 'collaboration-center', label: '协同中心', available: ['ADMIN', 'APPROVER', 'CUSTOMER_AGENT'].includes(role.value) },
  { value: 'security-incidents', label: '安全事件研判', available: ['ADMIN', 'APPROVER'].includes(role.value) },
  { value: 'operations', label: '运营看板', available: capabilities.value?.analyticsEnabled === true },
  { value: 'analytics', label: '运营分析', available: capabilities.value?.analyticsEnabled === true },
  { value: 'collaboration', label: '专家协作', available: capabilities.value?.collaborationEnabled === true },
  { value: 'voice', label: '实时语音', available: capabilities.value?.voiceEnabled === true },
  { value: 'governance', label: '治理中心', available: true },
])
onMounted(() => {
  void getOperationsCapabilities()
    .then((value) => {
      capabilities.value = value
      capabilityLoadState.value = 'ready'
    })
    .catch(() => {
      capabilities.value = null
      capabilityLoadState.value = 'failed'
    })
})
const selectedAlertId = ref(demoAlerts[0].id)
const activeView = ref<WorkbenchView>(props.initialView)
let navigationGeneration = 0
const selectedAnalysisQuestion = ref<string | null>(null)
const selectedAnalysisQuestionToken = ref(0)
const customerQueueRefreshToken = ref(0)
const hasVisitedWorkflow = ref(props.initialView === 'workflow')
function switchView(view: WorkbenchView): void {
  navigationGeneration += 1
  if (view !== 'workflow') cancelPendingLoad()
  activeView.value = view
}
watch(() => props.initialView, (view) => { switchView(view) })
watch(() => props.active, (active) => {
  if (active) switchView(props.initialView)
})
watch(activeView, async (view) => {
  if (view !== 'workflow' || hasVisitedWorkflow.value) return
  await nextTick()
  if (activeView.value === 'workflow') hasVisitedWorkflow.value = true
})
const role = ref<DemoRole>('ADMIN')
const reviewer = ref('')
const comment = ref('')
const { workflow, events, loading, approving, error, isTerminal, start, load: loadWorkflow, approve, reset: resetWorkflow, cancelPendingLoad } = useWorkflow()
const guidedLaunchUpdate = ref<GuidedLaunchUpdate | null>(null)
const currentGuidedLaunchUpdate = computed(() => {
  const request = props.launchRequest
  const update = guidedLaunchUpdate.value
  return update && request && update.requestId === request.requestId ? update : null
})

function handleGuidedLaunchUpdate(update: GuidedLaunchUpdate): void {
  if (update.requestId !== props.launchRequest?.requestId) return
  guidedLaunchUpdate.value = update
}

function retryGuidedLaunch(): void {
  const request = props.launchRequest
  const update = currentGuidedLaunchUpdate.value
  if (request && update?.requestId === request.requestId && update.state === 'failed') {
    emit('retry-guided-launch', request.scenarioId,
      request.launchInput ?? { alertId: null, question: null })
  }
}

// 统一执行轨迹：告警工作流通过确定性 runId 同时出现在右侧轨迹栏。
const trace = useExecutionTrace()
watch(activeView, (view, previousView) => {
  if (view === 'governance' && previousView !== 'governance') trace.reset()
})
watch(
  () => props.launchRequest,
  (request, previousRequest) => {
    if (request || !previousRequest) return
    resetWorkflow()
    reviewer.value = ''
    comment.value = ''
    guidedLaunchUpdate.value = null
    // A generic workflow entry starts a clean alert surface. Preserve only an
    // active or clarification-pending analytics trace; terminal traces are
    // evidence from the previous run and must not leak into the idle view.
    if (previousRequest.scenarioId !== 'OPERATIONS_ANALYSIS' || trace.status.value !== 'streaming') {
      trace.reset()
    }
  },
)
const traceStatusLabels = {
  idle: '空闲',
  streaming: '执行中',
  completed: '已完成',
  failed: '执行失败',
  interrupted: '已中断',
} as const
function statusLabelForTrace(status: keyof typeof traceStatusLabels): string {
  return traceStatusLabels[status]
}
const executionEvidenceByView: Record<WorkbenchView, Pick<WorkbenchEvidenceItem, 'value' | 'tone'>> = {
  workflow: { value: '受控写入 · 高风险或证据不足需审批', tone: 'warning' },
  customer: { value: '受控写入 · 可创建客服工单', tone: 'warning' },
  voice: { value: '只读查询 · 实时语音会话', tone: 'verified' },
  collaboration: { value: '只读查询 · 多专家汇总', tone: 'verified' },
  'collaboration-center': { value: '安全处理 · 原场景状态机', tone: 'verified' },
  'security-incidents': { value: '安全处理 · 脱敏研判后转协同', tone: 'warning' },
  analytics: { value: '真实只读数据', tone: 'verified' },
  governance: { value: '安全聚合 · 只读概览', tone: 'verified' },
  operations: { value: '真实只读数据 · 选择后分析', tone: 'verified' },
}
const evidenceItems = computed<WorkbenchEvidenceItem[]>(() => [
  { label: '场景', value: navItems.value.find((item) => item.value === activeView.value)?.label ?? '告警工作流' },
  { label: '执行轨迹', value: trace.status.value === 'streaming' ? '实时同步' : statusLabelForTrace(trace.status.value), tone: trace.status.value === 'failed' ? 'danger' : 'verified' },
  { label: '知识检索', ...knowledgeEvidence.value },
  { label: '执行模式', ...executionEvidenceByView[activeView.value] },
])

function openAnalysisFromBoard(question: string): void {
  selectedAnalysisQuestion.value = question
  selectedAnalysisQuestionToken.value += 1
  switchView('analytics')
}

async function openCollaborationView(view: 'workflow' | 'customer', workflowId?: string, _ticketId?: string): Promise<void> {
  const generation = ++navigationGeneration
  if (view === 'customer') {
    cancelPendingLoad()
    customerQueueRefreshToken.value += 1
  }
  if (view === 'workflow' && workflowId) {
    const loaded = await loadWorkflow(workflowId)
    if (generation !== navigationGeneration) return
    if (loaded?.alertId && demoAlerts.some((alert) => alert.id === loaded.alertId)) {
      selectedAlertId.value = loaded.alertId
    }
  }
  if (generation !== navigationGeneration) return
  activeView.value = view
}
function openCollaborationFromIncident(): void {
  switchView('collaboration-center')
}
watch(
  () => [workflow.value?.workflowId, activeView.value] as const,
  ([workflowId, view]) => {
    if (view === 'workflow' && workflowId) {
      // The trace is shared by all scenario pages. Reclaim it whenever the
      // alert view becomes active again, otherwise another page's run remains
      // visible while this workflow is still running.
      trace.subscribe(alertWorkflowRunId(workflowId))
    }
  },
)
const selectedAlert = computed(() => demoAlerts.find((item) => item.id === selectedAlertId.value) ?? demoAlerts[0])
const needsApproval = computed(() => workflow.value?.status === 'WAITING_APPROVAL')
const hasStarted = computed(() => Boolean(workflow.value))
const actionOrder = [
  'classifyAlert', 'collectParkContext', 'energyAnalysis', 'securityReview',
  'retrieveKnowledge', 'diagnoseAlert', 'riskGate', 'humanApproval',
  'createWorkOrder', 'summarizeResult',
]
const actionCatalog: Record<string, { label: string; detail: string }> = {
  classifyAlert: { label: '完成告警分诊', detail: '确定告警类别、优先级和风险等级。' },
  collectParkContext: { label: '收集园区上下文', detail: '读取设备状态、历史告警和已有工单信息。' },
  energyAnalysis: { label: '完成能耗基线分析', detail: '对比当前能耗与历史基线，形成场景分析。' },
  securityReview: { label: '完成安防脱敏复核', detail: '只使用脱敏后的安全事件摘要进行判断。' },
  retrieveKnowledge: { label: '检索处置知识', detail: '匹配当前告警对应的园区处置手册。' },
  diagnoseAlert: { label: '完成 AI 场景诊断', detail: '结合告警、园区数据、知识和只读工具生成诊断。' },
  riskGate: { label: '完成风险判断', detail: '根据风险等级、置信度和证据决定后续路径。' },
  humanApproval: { label: '记录人工审批', detail: '已记录审批人的处置决定。' },
  createWorkOrder: { label: '创建处置工单', detail: '已创建可追踪的现场处置工单。' },
  summarizeResult: { label: '汇总工作流结果', detail: '已写入最终状态并结束本次工作流。' },
}
const completedActions = computed(() => {
  const completed = new Set(events.value
    .filter((event) => event.type === 'NODE_COMPLETED')
    .map((event) => event.node))
  return actionOrder
    .filter((node) => completed.has(node))
    .map((node) => ({ node, ...actionCatalog[node] }))
})
const nextSteps = computed(() => {
  switch (workflow.value?.status) {
    case 'WAITING_APPROVAL':
      return ['请审批人确认风险和证据，再决定是否创建处置工单。']
    case 'COMPLETED':
      return workflow.value.workOrder
        ? [`按工单 ${workflow.value.workOrder.id} 执行现场检查或处置，并在工单系统更新执行结果。`]
        : ['查看本次诊断结果，确认是否需要安排现场处置。']
    case 'REJECTED':
      return ['本次处置已被拒绝，补充现场证据后再重新发起工作流。']
    case 'FAILED':
    case 'WORK_ORDER_FAILED':
      return ['查看工作流图中的失败节点，修复依赖或补充数据后重新执行。']
    default:
      return ['等待工作流完成后查看处置结果。']
  }
})

function workOrderStatusLabel(status?: string) {
  return ({ PENDING_EXECUTION: '待现场执行', IN_PROGRESS: '执行中', RESOLVED: '已解决', CANCELLED: '已取消' } as Record<string, string>)[status ?? ''] ?? status ?? '--'
}

function selectAlert(id: string) {
  selectedAlertId.value = id
}

async function launch() {
  return start(selectedAlertId.value)
}

useGuidedLaunch({
  active: () => props.active,
  request: () => props.launchRequest,
  scenarioId: 'ALERT_WORKFLOW',
  start: async (request) => {
    const alertId = request.launchInput?.alertId
    if (!alertId || !demoAlerts.some((alert) => alert.id === alertId)) {
      throw new Error('告警演示配置无效')
    }
    selectedAlertId.value = alertId
    const started = await launch()
    if (!started) throw new Error(error.value || '告警工作流启动失败')
    return { state: 'started', message: '告警工作流已启动' }
  },
  onUpdate: handleGuidedLaunchUpdate,
})

async function decide(decision: 'APPROVE' | 'REJECT') {
  if (!reviewer.value.trim() || !comment.value.trim()) {
    ElMessage.warning('请填写审批人和审批意见')
    return
  }
  await approve({ decision, reviewer: reviewer.value, comment: comment.value, role: role.value })
  reviewer.value = ''
  comment.value = ''
}

async function rateWorkflow(rating: 'CORRECT' | 'INCORRECT') {
  if (!workflow.value) return
  try {
    await submitFeedback('ALERT_WORKFLOW', workflow.value.workflowId, rating, role.value)
    ElMessage.success('诊断反馈已记录')
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '反馈提交失败') }
}

function statusLabel(status?: string) {
  return ({ RUNNING: '执行中', WAITING_APPROVAL: '待人工审批', COMPLETED: '已完成', REJECTED: '已拒绝', FAILED: '执行失败', WORK_ORDER_FAILED: '工单创建失败' } as Record<string, string>)[status ?? ''] ?? '未启动'
}
function riskLabel(risk?: string) {
  return ({ LOW: '低风险', HIGH: '高风险' } as Record<string, string>)[risk ?? ''] ?? risk ?? '分析中'
}
function statusType(status?: string) {
  return ({ RUNNING: 'primary', WAITING_APPROVAL: 'warning', COMPLETED: 'success', REJECTED: 'info', FAILED: 'danger', WORK_ORDER_FAILED: 'danger' } as Record<string, string>)[status ?? ''] ?? 'info'
}
function confidence(value?: number) {
  return value == null ? '--' : `${Math.round(value * 100)}%`
}
</script>

<template>
  <ImmersiveWorkbenchShell
    :active-view="activeView"
    :role="role"
    :nav-items="navItems"
    :evidence-items="evidenceItems"
    :guided-launch="currentGuidedLaunchUpdate"
    :rail-priority="needsApproval"
    @switch-view="switchView"
    @update:role="role = $event"
    @back-to-showcase="emit('back-to-showcase')"
    @retry-guided-launch="retryGuidedLaunch"
  >
    <main v-show="activeView === 'analytics'" class="main-content">
      <section class="hero-row"><div><span class="eyebrow">运营分析 · 03</span><h2>自然语言直达<br /><em>真实只读数据</em></h2><p class="hero-copy">问题解析、指标口径、AST 安全校验、EXPLAIN 成本与只读执行全程可见。</p></div></section>
      <OperationsAnalysisPage
        :trace="trace"
        :active="props.active && activeView === 'analytics'"
          :launch-request="props.launchRequest"
          :initial-question="selectedAnalysisQuestion"
          :initial-question-token="selectedAnalysisQuestionToken"
        @run-started="(id: string) => trace.subscribe(id)"
        @launch-status="handleGuidedLaunchUpdate"
      />
    </main>

    <main v-show="activeView === 'customer'" class="main-content customer-main">
      <section class="hero-row customer-hero"><div><span class="eyebrow">园区服务 · 02</span><h2>园区服务问题<br /><em>快速响应与有序转人工</em></h2><p class="hero-copy">基于模拟园区知识回答常见咨询，报修或知识不足时自动生成客服工单。</p></div></section>
      <CustomerServiceConsole
        :role="role"
        :active="props.active && activeView === 'customer'"
        :refresh-token="customerQueueRefreshToken"
        :launch-request="props.launchRequest"
        :trace="trace"
        @launch-status="handleGuidedLaunchUpdate"
      />
    </main>

    <main v-show="activeView === 'voice'" class="main-content">
      <VoiceAssistantPage
        :trace="trace"
        :active="props.active && activeView === 'voice'"
        :launch-request="props.launchRequest"
        @launch-status="handleGuidedLaunchUpdate"
      />
    </main>

    <main v-show="activeView === 'collaboration'" class="main-content">
      <ExpertCollaborationPage
        :trace="trace"
        :active="props.active && activeView === 'collaboration'"
        :launch-request="props.launchRequest"
        @launch-status="handleGuidedLaunchUpdate"
      />
    </main>

    <CollaborationCenter
      v-show="activeView === 'collaboration-center'"
      :role="role"
      :active="props.active && activeView === 'collaboration-center'"
      @open-view="openCollaborationView"
    />

    <SecurityIncidentCenter
      v-show="activeView === 'security-incidents'"
      :role="role"
      @open-collaboration="openCollaborationFromIncident"
    />

    <GovernanceCenter v-show="activeView === 'governance'" :role="role" :active="props.active && activeView === 'governance'" />

    <OperationsBoard
      v-show="activeView === 'operations'"
      :role="role"
      :trace="trace"
      :active="props.active && activeView === 'operations'"
      @open-analysis="openAnalysisFromBoard"
    />

    <main v-show="activeView === 'workflow'" class="main-content">
      <section class="hero-row">
        <div>
          <span class="eyebrow">运营工作流 · 01</span>
          <h2>让每一条告警<br /><em>都有清晰的处置路径</em></h2>
          <p class="hero-copy">从告警分诊到 AI 诊断，再到风险闸门和人工审批，实时掌握园区异常的每一步。</p>
        </div>
        <div class="hero-metrics">
          <div><strong>04</strong><span>演示告警</span></div>
          <div><strong>08</strong><span>工作流节点</span></div>
          <div><strong>实时</strong><span>事件推送</span></div>
        </div>
      </section>

      <div v-if="error" class="error-banner"><span>!</span>{{ error }}<button type="button" @click="error = ''">关闭</button></div>

      <section class="dashboard-grid">
        <AlertSelector :alerts="demoAlerts" :selected-id="selectedAlertId" :loading="loading" @select="selectAlert" @start="launch" />
        <section class="panel summary-panel">
          <div class="section-heading"><div><span class="eyebrow">工作流状态</span><h2>当前工作流</h2></div><el-tag :type="statusType(workflow?.status)" effect="dark" round>{{ statusLabel(workflow?.status) }}</el-tag></div>
          <div v-if="hasStarted" class="summary-content">
            <div class="workflow-id"><span>工作流编号</span><strong>{{ workflow?.workflowId }}</strong></div>
            <div class="summary-stats"><div><span>关联告警</span><strong>{{ workflow?.alertId }}</strong></div><div><span>事件序号</span><strong>#{{ workflow?.eventSequence }}</strong></div><div><span>当前风险</span><strong :class="workflow?.diagnosis?.riskLevel === 'HIGH' ? 'danger-text' : ''">{{ riskLabel(workflow?.diagnosis?.riskLevel) }}</strong></div><div><span>诊断置信度</span><strong>{{ confidence(workflow?.diagnosis?.confidence) }}</strong></div></div>
            <div class="selected-alert"><div class="mini-icon">{{ selectedAlert.category.slice(0, 1) }}</div><div><strong>{{ selectedAlert.title }}</strong><span>{{ selectedAlert.device }} · {{ selectedAlert.building }}</span></div></div>
          </div>
          <div v-else class="empty-summary"><div class="empty-orbit">◎</div><strong>尚未启动工作流</strong><span>从左侧选择一条告警，开始查看完整处置路径。</span></div>
        </section>
      </section>

      <section class="lower-grid">
        <WorkflowGraph v-if="hasVisitedWorkflow" :workflow="workflow" :events="events" />
        <EventTimeline :events="events" />
      </section>

      <DemoConsole :workflow="workflow" :role="role" />

      <section v-if="needsApproval" class="approval-panel panel">
        <div class="approval-accent"></div><div class="approval-copy"><span class="eyebrow">人工参与</span><h2>需要人工审批</h2><p>风险闸门已暂停工作流。确认现场情况后，决定是否创建处置工单。</p><div class="approval-facts"><span v-for="reason in (workflow?.riskReasons ?? [])" :key="reason" class="risk-reason">{{ reason }}</span></div></div>
        <div class="approval-form"><el-input v-model="reviewer" aria-label="审批人姓名" placeholder="审批人姓名" /><el-input v-model="comment" aria-label="审批意见" type="textarea" :rows="2" placeholder="审批意见" /><div class="approval-actions"><el-button :loading="approving" @click="decide('REJECT')">拒绝处置</el-button><el-button type="primary" :loading="approving" @click="decide('APPROVE')">批准并创建工单</el-button></div></div>
      </section>

      <section v-if="isTerminal && workflow" class="result-panel panel">
        <div class="result-header">
          <div class="result-check">✓</div>
          <div><span class="eyebrow">工作流结果</span><h2>{{ workflow.status === 'COMPLETED' ? '处置流程已完成' : workflow.status === 'REJECTED' ? '处置流程已拒绝' : '处置流程未完成' }}</h2><p>工作流 {{ workflow.workflowId }} 已产生可追踪结果。</p></div>
          <div v-if="workflow.workOrder" class="result-order"><span>工单编号 · {{ workOrderStatusLabel(workflow.workOrder.status) }}</span><strong>{{ workflow.workOrder.id }}</strong></div>
        </div>
        <div class="result-columns">
          <div class="result-column"><h3>本次已完成</h3><ul v-if="completedActions.length"><li v-for="action in completedActions" :key="action.node"><strong>{{ action.label }}</strong><span>{{ action.detail }}</span></li></ul><p v-else class="result-empty">正在同步执行明细...</p></div>
          <div class="result-column next-column"><h3>接下来需要做什么</h3><ul><li v-for="step in nextSteps" :key="step"><strong>下一步</strong><span>{{ step }}</span></li></ul></div>
        </div>
        <div class="result-footer"><div class="result-note">诊断原文和敏感业务内容已按后端安全策略脱敏展示。</div><div v-if="['APPROVER', 'ADMIN'].includes(role)" class="result-feedback"><el-button size="small" @click="rateWorkflow('CORRECT')">诊断正确</el-button><el-button size="small" @click="rateWorkflow('INCORRECT')">诊断不正确</el-button></div></div>
      </section>
    </main>

    <template #rail>
      <ExecutionTraceRail
        class="global-rail"
        :events="trace.events.value"
        :status="trace.status.value"
        :error="trace.error.value"
      />
    </template>
  </ImmersiveWorkbenchShell>
</template>
