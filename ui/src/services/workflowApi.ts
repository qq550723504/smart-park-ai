import type { AuditEntry, CustomerConversationResponse, CustomerServiceResponse, DemoRole, FeedbackRating, KnowledgeMetadata, OperationsMetrics, WorkflowEvent, WorkflowObservability, WorkflowResponse } from '../types/workflow'
import type { ShowcaseLaunchInput, ShowcaseScenarioId } from '../types/workbench'
import type { CollaborationWorkItem, CollaborationWorkItemFilters } from '../types/collaborationCenter'

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) },
  })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const error = await response.json()
      message = error.message || error.error || message
    } catch {
      // 后端返回非 JSON 错误时使用状态码提示。
    }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

export type ShowcaseScenarioStatus = 'READY' | 'NOT_READY' | 'DISABLED'

export interface ShowcaseScenario {
  id: ShowcaseScenarioId
  status: ShowcaseScenarioStatus
  live: boolean
  title: string
  businessQuestion: string
  expectedDurationSeconds: number
  requiredCapabilities: string[]
  proofTypes: string[]
  humanBoundary: string
  unavailableReason: string | null
  lastVerifiedAt: string | null
  launchInput: ShowcaseLaunchInput
}

export interface ShowcaseScenarioCatalog {
  capturedAt: string
  scenarios: ShowcaseScenario[]
}

export function getShowcaseScenarios() {
  return request<ShowcaseScenarioCatalog>('/api/showcase/scenarios')
}

export function askCustomerService(question: string, idempotencyKey: string) {
  return request<CustomerServiceResponse>('/api/customer-service/sessions', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ question }),
  })
}

export function replyCustomerSession(sessionId: string, question: string, idempotencyKey: string) {
  return request<CustomerServiceResponse>(`/api/customer-service/sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ question }),
  })
}

export function getCustomerConversation(sessionId: string) {
  return request<CustomerConversationResponse>(`/api/customer-service/sessions/${sessionId}/conversation`)
}

export function listCustomerTickets(role: DemoRole) {
  return request<CustomerServiceResponse[]>('/api/customer-service/tickets', {
    headers: { 'X-Demo-Role': role },
  })
}

export function updateCustomerTicket(ticketId: string, status: string, role: DemoRole) {
  return request<CustomerServiceResponse>(`/api/customer-service/tickets/${ticketId}`, {
    method: 'PATCH',
    headers: { 'X-Demo-Role': role },
    body: JSON.stringify({ status }),
  })
}

export function getKnowledge(role: DemoRole) {
  return request<KnowledgeMetadata[]>('/api/knowledge', { headers: { 'X-Demo-Role': role } })
}

export function setKnowledgeActive(documentId: string, active: boolean, role: DemoRole) {
  return request<KnowledgeMetadata>(`/api/knowledge/${documentId}/active`, {
    method: 'PATCH', headers: { 'X-Demo-Role': role }, body: JSON.stringify({ active }),
  })
}

export function submitFeedback(targetType: 'CUSTOMER_SESSION' | 'ALERT_WORKFLOW', targetId: string, rating: FeedbackRating, role: DemoRole) {
  return request('/api/feedback', {
    method: 'POST', headers: { 'X-Demo-Role': role }, body: JSON.stringify({ targetType, targetId, rating }),
  })
}

export interface OperationsCapabilities {
  knowledgeMode: 'mock' | 'rag'
  customerAnswerMode: 'mock' | 'dashscope'
  vectorStore: 'none' | 'simple-vector-store'
  analyticsEnabled: boolean
  collaborationEnabled: boolean
  voiceEnabled: boolean
}

export function getOperationsCapabilities() {
  return request<OperationsCapabilities>('/api/operations/capabilities')
}

export function getOperationsMetrics() {
  return request<OperationsMetrics>('/api/operations/metrics')
}

export function listCollaborationWorkItems(role: DemoRole, filters: CollaborationWorkItemFilters = {}) {
  const params = new URLSearchParams()
  if (filters.source) params.set('source', filters.source)
  if (filters.status) params.set('status', filters.status)
  if (filters.limit != null) params.set('limit', String(filters.limit))
  const query = params.toString()
  return request<CollaborationWorkItem[]>(`/api/collaboration/work-items${query ? `?${query}` : ''}`, {
    headers: { 'X-Demo-Role': role },
  })
}

export interface GovernanceOverview {
  capturedAt: string
  scenarios: { total: number; ready: number; notReady: number; disabled: number }
  capabilities: OperationsCapabilities
  business: {
    workflowCount: number
    completedWorkflowCount: number
    customerSessionCount: number
    humanTicketCount: number
  }
  governance: {
    auditEntryCount: number
    feedbackCount: number
    positiveFeedbackCount: number
    knowledgeDocumentCount: number
    activeKnowledgeDocumentCount: number
    completionRate: number | null
    positiveFeedbackRate: number | null
  }
  boundaries: string[]
}

export function getGovernanceOverview() {
  return request<GovernanceOverview>('/api/governance/overview')
}

export function getAuditEntries(role: DemoRole) {
  return request<AuditEntry[]>('/api/audit', { headers: { 'X-Demo-Role': role } })
}

export function getWorkflowObservability(workflowId: string) {
  return request<WorkflowObservability>(`/api/workflows/${workflowId}/observability`)
}

export function injectDemoFault(point: 'KNOWLEDGE_SEARCH', role: DemoRole) {
  return request<{ point: string; status: string }>('/api/demo/faults', {
    method: 'POST',
    headers: { 'X-Demo-Role': role },
    body: JSON.stringify({ point }),
  })
}

export function startWorkflow(alertId: string) {
  return request<WorkflowResponse>(`/api/alerts/${alertId}/workflows`, { method: 'POST' })
}

export function getWorkflow(workflowId: string) {
  return request<WorkflowResponse>(`/api/workflows/${workflowId}`)
}

export function submitApproval(workflowId: string, payload: {
  decision: 'APPROVE' | 'REJECT'
  reviewer: string
  comment: string
  idempotencyKey: string
}, role: DemoRole) {
  return request<WorkflowResponse>(`/api/workflows/${workflowId}/approval`, {
    method: 'POST',
    headers: { 'X-Demo-Role': role },
    body: JSON.stringify(payload),
  })
}

export function subscribeToWorkflow(
  workflowId: string,
  onEvent: (event: WorkflowEvent) => void,
  onError: () => void,
) {
  const source = new EventSource(`/api/workflows/${workflowId}/events`)
  const eventTypes = [
    'STARTED', 'NODE_STARTED', 'TOOL_CALLED', 'NODE_COMPLETED',
    'PAUSED', 'RESUMED', 'COMPLETED', 'FAILED',
  ]
  const handleMessage = (message: MessageEvent<string>) => {
    try {
      onEvent(JSON.parse(message.data) as WorkflowEvent)
    } catch {
      onError()
    }
  }
  // 后端使用具名 SSE 事件，必须逐一注册监听器；onmessage 只处理无名称事件。
  eventTypes.forEach((type) => source.addEventListener(type, handleMessage as EventListener))
  source.onerror = onError
  return source
}
