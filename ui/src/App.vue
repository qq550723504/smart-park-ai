<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AlertSelector from './components/AlertSelector.vue'
import WorkflowGraph from './components/WorkflowGraph.vue'
import DemoConsole from './components/DemoConsole.vue'
import EventTimeline from './components/EventTimeline.vue'
import CustomerServiceConsole from './components/CustomerServiceConsole.vue'
import { demoAlerts, type DemoRole } from './types/workflow'
import { useWorkflow } from './composables/useWorkflow'
import { getOperationsCapabilities, submitFeedback } from './services/workflowApi'
import { customerIntentLabel, workflowNodeLabel } from './utils/labels'
import './styles.css'

const capabilities = ref<{ knowledgeMode: string; customerAnswerMode: string; vectorStore: string } | null>(null)
const capabilityLabels = computed(() => capabilities.value ? {
  knowledge: capabilities.value.knowledgeMode === 'mock' ? 'Mock' : 'RAG',
  customer: capabilities.value.customerAnswerMode === 'mock' ? 'Mock' : 'DashScope',
  vector: capabilities.value.vectorStore === 'none' ? '无向量库（关键词检索）' : 'SimpleVectorStore（进程内）',
} : null)
onMounted(() => {
  void getOperationsCapabilities()
    .then((value) => { capabilities.value = value })
    .catch(() => { capabilities.value = null })
})
const selectedAlertId = ref(demoAlerts[0].id)
const activeView = ref<'workflow' | 'customer'>('workflow')
const role = ref<DemoRole>('ADMIN')
const reviewer = ref('')
const comment = ref('')
const { workflow, events, loading, approving, error, isTerminal, start, approve } = useWorkflow()
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
  await start(selectedAlertId.value)
}

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
  <div class="app-shell">
    <header class="topbar">
      <div class="brand-lockup">
        <div class="brand-mark"><span></span><span></span><span></span></div>
        <div><span class="brand-kicker">智慧园区 · 智能运营</span><h1>智慧园区智能运营中心</h1></div>
      </div>
      <div class="topbar-actions">
        <el-select v-model="role" class="role-select" aria-label="演示角色"><el-option label="查看者" value="VIEWER" /><el-option label="操作员" value="OPERATOR" /><el-option label="审批人" value="APPROVER" /><el-option label="客服坐席" value="CUSTOMER_AGENT" /><el-option label="管理员" value="ADMIN" /></el-select>
        <nav class="view-switch"><button :class="{ active: activeView === 'workflow' }" @click="activeView = 'workflow'">运营工作流</button><button :class="{ active: activeView === 'customer' }" @click="activeView = 'customer'">园区客服</button></nav>
        <div class="system-status"><span class="status-pulse"></span><span>模拟园区系统</span><span class="divider"></span><span class="muted">知识检索 {{ capabilityLabels?.knowledge ?? '--' }} · 客服回答 {{ capabilityLabels?.customer ?? '--' }} · 索引存储 {{ capabilityLabels?.vector ?? '--' }}</span></div>
      </div>
    </header>

    <main v-if="activeView === 'customer'" class="main-content customer-main">
      <section class="hero-row customer-hero"><div><span class="eyebrow">园区服务 · 02</span><h2>园区服务问题<br /><em>快速响应与有序转人工</em></h2><p class="hero-copy">基于模拟园区知识回答常见咨询，报修或知识不足时自动生成客服工单。</p></div></section>
      <CustomerServiceConsole :role="role" />
    </main>

    <main v-else class="main-content">
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
        <WorkflowGraph :workflow="workflow" :events="events" />
        <EventTimeline :events="events" />
      </section>

      <DemoConsole :workflow="workflow" :role="role" />

      <section v-if="needsApproval" class="approval-panel panel">
        <div class="approval-accent"></div><div class="approval-copy"><span class="eyebrow">人工参与</span><h2>需要人工审批</h2><p>风险闸门已暂停工作流。确认现场情况后，决定是否创建处置工单。</p><div class="approval-facts"><span v-for="reason in (workflow?.riskReasons ?? [])" :key="reason" class="risk-reason">{{ reason }}</span></div></div>
        <div class="approval-form"><el-input v-model="reviewer" placeholder="审批人姓名" /><el-input v-model="comment" type="textarea" :rows="2" placeholder="审批意见" /><div class="approval-actions"><el-button :loading="approving" @click="decide('REJECT')">拒绝处置</el-button><el-button type="primary" :loading="approving" @click="decide('APPROVE')">批准并创建工单</el-button></div></div>
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
    <footer><span>智慧园区运营中心</span><span>工作流状态由后端图实时驱动</span></footer>
  </div>
</template>
