const workflowNodeLabels: Record<string, string> = {
  workflow: '工作流',
  classifyAlert: '告警分诊',
  collectParkContext: '收集园区上下文',
  energyAnalysis: '能耗基线分析',
  securityReview: '安防脱敏复核',
  retrieveKnowledge: '检索处置知识',
  diagnoseAlert: 'AI 场景诊断',
  riskGate: '风险判断',
  humanApproval: '人工审批',
  createWorkOrder: '创建处置工单',
  summarizeResult: '汇总工作流结果',
}

const workflowEventLabels: Record<string, string> = {
  STARTED: '已启动',
  NODE_STARTED: '节点开始',
  TOOL_CALLED: '调用工具',
  NODE_COMPLETED: '节点完成',
  PAUSED: '等待审批',
  RESUMED: '恢复执行',
  COMPLETED: '已完成',
  FAILED: '执行失败',
}

const toolLabels: Record<string, string> = {
  'AlertPort.getAlert': '读取告警',
  'DevicePort.getDevice': '读取设备状态',
  'AlertPort.findHistory': '查询历史告警',
  'WorkOrderPort.findByWorkflowId': '查询关联工单',
  'KnowledgePort.search': '检索园区知识',
  'EnergyPort.getLatestEnergyReading': '读取最新能耗',
  'SecurityPort.getEvent': '读取脱敏安防事件',
  'AgentTool.lookupDeviceStatus': 'AI 查询设备状态',
  'AgentTool.lookupAlertHistory': 'AI 查询告警历史',
  'AgentTool.lookupAlert': 'AI 查询告警详情',
  'AgentTool.searchParkKnowledge': 'AI 检索园区知识',
  'AgentTool.lookupWorkOrders': 'AI 查询已有工单',
  'WorkOrderPort.create': '创建工单',
}

const customerIntentLabels: Record<string, string> = {
  REPAIR: '设施报修',
  PARKING: '停车服务',
  VISITOR: '访客通行',
  ENERGY: '园区能耗',
  GENERAL: '一般咨询',
}

const customerTicketStatusLabels: Record<string, string> = {
  WAITING_AGENT: '等待客服接入',
  ASSIGNED: '已分配客服',
  IN_PROGRESS: '处理中',
  WAITING_CUSTOMER: '等待用户补充',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
}

const auditActionLabels: Record<string, string> = {
  APPROVE_WORKFLOW: '审批工作流',
  UPDATE_CUSTOMER_TICKET: '更新客服工单',
  UPDATE_KNOWLEDGE: '更新知识状态',
  RECORD_FEEDBACK: '记录反馈',
  REVIEW_SECURITY_INCIDENT: '研判安全事件',
  HANDOFF_SECURITY_INCIDENT: '交接安全事件',
}

const eventSummaryLabels: Record<string, string> = {
  'alert workflow started': '工作流已启动',
  'workflow completed': '工作流已完成',
  'workflow rejected': '工作流已拒绝',
  'operator approval resumed workflow': '审批后恢复执行',
  'waiting for operator approval': '等待人工审批',
  'operator decision recorded': '已记录人工决定',
  ALERT_LOOKUP_FAILED: '读取告警失败',
  CLASSIFICATION_FAILED: '告警分诊失败',
  PARK_CONTEXT_FAILED: '收集园区上下文失败',
  KNOWLEDGE_RETRIEVAL_FAILED: '检索园区知识失败',
  DIAGNOSIS_FAILED: 'AI 诊断失败',
  WORK_ORDER_FAILED: '创建工单失败',
  APPROVAL_FAILED: '审批处理失败',
  WORKFLOW_FAILED: '工作流执行失败',
}

export function workflowNodeLabel(value: string) {
  return workflowNodeLabels[value] ?? value
}

export function workflowEventLabel(value: string) {
  return workflowEventLabels[value] ?? value
}

export function toolLabel(value: string) {
  return toolLabels[value] ?? value
}

export function customerIntentLabel(value: string) {
  return customerIntentLabels[value] ?? value
}

export function customerTicketStatusLabel(value: string) {
  return customerTicketStatusLabels[value] ?? value
}

export function auditActionLabel(value: string) {
  return auditActionLabels[value] ?? value
}

export function eventSummaryLabel(value: string) {
  if (eventSummaryLabels[value]) return eventSummaryLabels[value]
  const completed = value.match(/^(.+) completed$/)
  if (completed) return `${workflowNodeLabel(completed[1])}已完成`
  const started = value.match(/^(.+) started$/)
  if (started) return `${workflowNodeLabel(started[1])}已开始`
  return toolLabel(value)
}
