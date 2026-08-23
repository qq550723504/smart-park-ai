<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AlertSelector from './components/AlertSelector.vue'
import WorkflowGraph from './components/WorkflowGraph.vue'
import EventTimeline from './components/EventTimeline.vue'
import { demoAlerts } from './types/workflow'
import { useWorkflow } from './composables/useWorkflow'
import './styles.css'

const selectedAlertId = ref(demoAlerts[0].id)
const reviewer = ref('')
const comment = ref('')
const { workflow, events, loading, approving, error, isTerminal, start, approve } = useWorkflow()
const selectedAlert = computed(() => demoAlerts.find((item) => item.id === selectedAlertId.value) ?? demoAlerts[0])
const needsApproval = computed(() => workflow.value?.status === 'WAITING_APPROVAL')
const hasStarted = computed(() => Boolean(workflow.value))

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
  await approve({ decision, reviewer: reviewer.value, comment: comment.value })
  reviewer.value = ''
  comment.value = ''
}

function statusLabel(status?: string) {
  return ({ RUNNING: '执行中', WAITING_APPROVAL: '待人工审批', COMPLETED: '已完成', REJECTED: '已拒绝', FAILED: '执行失败' } as Record<string, string>)[status ?? ''] ?? '未启动'
}
function statusType(status?: string) {
  return ({ RUNNING: 'primary', WAITING_APPROVAL: 'warning', COMPLETED: 'success', REJECTED: 'info', FAILED: 'danger' } as Record<string, string>)[status ?? ''] ?? 'info'
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
        <div><span class="brand-kicker">SMART PARK · AI OPERATIONS</span><h1>告警处置中心</h1></div>
      </div>
      <div class="system-status"><span class="status-pulse"></span><span>Mock 园区系统</span><span class="divider"></span><span class="muted">DashScope Agent</span></div>
    </header>

    <main class="main-content">
      <section class="hero-row">
        <div>
          <span class="eyebrow">WORKFLOW CONSOLE / 01</span>
          <h2>让每一条告警<br /><em>都有清晰的处置路径</em></h2>
          <p class="hero-copy">从告警分诊到 AI 诊断，再到风险闸门和人工审批，实时掌握园区异常的每一步。</p>
        </div>
        <div class="hero-metrics">
          <div><strong>04</strong><span>演示告警</span></div>
          <div><strong>08</strong><span>工作流节点</span></div>
          <div><strong>SSE</strong><span>实时事件</span></div>
        </div>
      </section>

      <div v-if="error" class="error-banner"><span>!</span>{{ error }}<button type="button" @click="error = ''">关闭</button></div>

      <section class="dashboard-grid">
        <AlertSelector :alerts="demoAlerts" :selected-id="selectedAlertId" :loading="loading" @select="selectAlert" @start="launch" />
        <section class="panel summary-panel">
          <div class="section-heading"><div><span class="eyebrow">WORKFLOW STATUS</span><h2>当前工作流</h2></div><el-tag :type="statusType(workflow?.status)" effect="dark" round>{{ statusLabel(workflow?.status) }}</el-tag></div>
          <div v-if="hasStarted" class="summary-content">
            <div class="workflow-id"><span>工作流编号</span><strong>{{ workflow?.workflowId }}</strong></div>
            <div class="summary-stats"><div><span>关联告警</span><strong>{{ workflow?.alertId }}</strong></div><div><span>事件序号</span><strong>#{{ workflow?.eventSequence }}</strong></div><div><span>当前风险</span><strong :class="workflow?.diagnosis?.riskLevel === 'HIGH' ? 'danger-text' : ''">{{ workflow?.diagnosis?.riskLevel ?? '分析中' }}</strong></div><div><span>诊断置信度</span><strong>{{ confidence(workflow?.diagnosis?.confidence) }}</strong></div></div>
            <div class="selected-alert"><div class="mini-icon">{{ selectedAlert.category.slice(0, 1) }}</div><div><strong>{{ selectedAlert.title }}</strong><span>{{ selectedAlert.device }} · {{ selectedAlert.building }}</span></div></div>
          </div>
          <div v-else class="empty-summary"><div class="empty-orbit">◎</div><strong>尚未启动工作流</strong><span>从左侧选择一条告警，开始查看完整处置路径。</span></div>
        </section>
      </section>

      <section class="lower-grid">
        <WorkflowGraph :workflow="workflow" :events="events" />
        <EventTimeline :events="events" />
      </section>

      <section v-if="needsApproval" class="approval-panel panel">
        <div class="approval-accent"></div><div class="approval-copy"><span class="eyebrow">HUMAN IN THE LOOP</span><h2>需要人工审批</h2><p>风险闸门已暂停工作流。确认现场情况后，决定是否创建处置工单。</p><div class="approval-facts"><span>风险等级 <strong>{{ workflow?.diagnosis?.riskLevel }}</strong></span><span>诊断置信度 <strong>{{ confidence(workflow?.diagnosis?.confidence) }}</strong></span><span>证据文档 <strong>{{ workflow?.diagnosis ? '已完成检索' : '分析中' }}</strong></span></div></div>
        <div class="approval-form"><el-input v-model="reviewer" placeholder="审批人姓名" /><el-input v-model="comment" type="textarea" :rows="2" placeholder="审批意见" /><div class="approval-actions"><el-button :loading="approving" @click="decide('REJECT')">拒绝处置</el-button><el-button type="primary" :loading="approving" @click="decide('APPROVE')">批准并创建工单</el-button></div></div>
      </section>

      <section v-if="isTerminal && workflow" class="result-strip panel"><div class="result-check">✓</div><div><span class="eyebrow">WORKFLOW RESULT</span><h2>{{ workflow.status === 'COMPLETED' ? '处置流程已完成' : workflow.status === 'REJECTED' ? '处置流程已拒绝' : '处置流程未完成' }}</h2></div><div v-if="workflow.workOrder" class="result-order"><span>工单编号</span><strong>{{ workflow.workOrder.id }}</strong></div><div class="result-note">业务内容已按后端安全策略脱敏展示</div></section>
    </main>
    <footer><span>SMART PARK OPERATIONS</span><span>工作流状态由后端 Graph 实时驱动</span></footer>
  </div>
</template>
