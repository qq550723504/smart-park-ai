export type WorkflowStatus = 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'REJECTED' | 'FAILED'

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
  status: string
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
]
