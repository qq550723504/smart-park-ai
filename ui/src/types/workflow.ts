export type DemoRole = 'VIEWER' | 'OPERATOR' | 'APPROVER' | 'CUSTOMER_AGENT' | 'ADMIN'

export interface OperationsMetrics {
  workflowCount: number
  completedWorkflowCount: number
  customerSessionCount: number
  humanTicketCount: number
  auditEntryCount: number
  feedbackCount: number
  positiveFeedbackCount: number
  knowledgeDocumentCount: number
  activeKnowledgeDocumentCount: number
}

export interface KnowledgeMetadata {
  id: string
  title: string
  tags: string[]
  updatedAt: string
  active: boolean
}

export type FeedbackRating = 'HELPFUL' | 'NOT_HELPFUL' | 'CORRECT' | 'INCORRECT'


export interface AuditEntry {
  actorRole: string
  action: string
  resourceId: string
  outcome: string
  timestamp: string
}

export interface WorkflowObservability {
  workflowId: string
  totalEvents: number
  toolCalls: number
  tools: string[]
  failedNodes: string[]
}

export interface CustomerTicketResponse {
  id: string
  sessionId: string
  intent: string
  status: string
  safeSummary: string
  createdAt: string
}

export interface CustomerConversationResponse {
  sessionId: string
  messages: Array<{ role: 'USER' | 'ASSISTANT'; text: string; createdAt: string }>
  retrievals: Array<{ query: string; documentIds: string[]; createdAt: string }>
  humanHandoff: boolean
}

export interface CustomerServiceResponse {
  sessionId: string
  intent: string
  answer: string
  knowledgeSources: string[]
  needsHuman: boolean
  ticket: CustomerTicketResponse | null
}

export type WorkflowStatus = 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'REJECTED' | 'FAILED' | 'WORK_ORDER_FAILED'

export interface DiagnosisResponse {
  id: string
  alertId: string
  deviceId: string
  riskLevel: string
  rootCause: string
  summary: string
  evidence: string[]
  recommendedAction: string
  confidence: number
  diagnosedAt: string
}

export interface ApprovalResponse {
  decision: string
  reviewer: string
  comment: string
  decidedAt: string
}

export interface WorkOrderResponse {
  id: string
  workflowId: string
  parkId: string
  buildingId: string
  deviceId: string
  alertId: string
  summary: string
  riskLevel: string
  status: 'PENDING_EXECUTION' | 'IN_PROGRESS' | 'RESOLVED' | 'CANCELLED' | string
  approval: ApprovalResponse | null
  evidence: string[]
  createdAt: string
  updatedAt: string
}

export interface WorkflowResponse {
  workflowId: string
  alertId: string
  status: WorkflowStatus
  diagnosis: DiagnosisResponse | null
  approval: ApprovalResponse | null
  workOrder: WorkOrderResponse | null
  errors: string[]
  eventSequence: number
  riskReasons: string[]
}

export interface WorkflowEvent {
  eventId: string
  type: string
  node: string
  sequence: number
  timestamp: string
  redactedSummary: string
}

export interface DemoAlert {
  id: string
  title: string
  device: string
  building: string
  risk: 'LOW' | 'HIGH'
  category: string
  description: string
}

export const demoAlerts: DemoAlert[] = [
  {
    id: 'ALT-TEMP-001',
    title: '暖通机房温度持续升高',
    device: '暖通空调送风机组',
    building: 'A1 · 暖通机房',
    risk: 'LOW',
    category: '温度异常',
    description: '送风温度已超过舒适区阈值。',
  },
  {
    id: 'ALT-POWER-001',
    title: '主配电柜电压波动',
    device: '主配电柜',
    building: 'A2 · 配电间',
    risk: 'HIGH',
    category: '电力异常',
    description: '主配电柜检测到三相电压不稳定。',
  },
  {
    id: 'ALT-ENERGY-001',
    title: 'A2 楼宇能耗异常',
    device: 'A2 楼宇电能表',
    building: 'A2 · 能源管理',
    risk: 'HIGH',
    category: '能耗异常',
    description: '当前时段能耗比学习基线高出 38%。',
  },
  {
    id: 'ALT-ACCESS-001',
    title: '北门非开放时段连续拒绝访问',
    device: '北门门禁控制器',
    building: 'A1 · 北门入口',
    risk: 'HIGH',
    category: '安防异常',
    description: '匿名凭证连续三次被拒绝，仅提供脱敏规则摘要。',
  },
]
