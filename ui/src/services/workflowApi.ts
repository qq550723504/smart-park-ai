import type { WorkflowEvent, WorkflowResponse } from '../types/workflow'

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) },
    ...options,
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
}) {
  return request<WorkflowResponse>(`/api/workflows/${workflowId}/approval`, {
    method: 'POST',
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
