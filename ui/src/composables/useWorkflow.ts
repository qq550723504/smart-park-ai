import { computed, onScopeDispose, ref } from 'vue'
import { getWorkflow, getWorkflowEventHistory, startWorkflow, submitApproval, subscribeToWorkflow, WorkflowApiError } from '../services/workflowApi'
import type { DemoRole, WorkflowEvent, WorkflowResponse } from '../types/workflow'
import { createRequestId } from '../utils/requestId'

export function useWorkflow() {
  const workflow = ref<WorkflowResponse | null>(null)
  const events = ref<WorkflowEvent[]>([])
  const loading = ref(false)
  const approving = ref(false)
  const approvalNeedsRefresh = ref(false)
  const error = ref('')
  let eventSource: EventSource | null = null
  let approvalKey: string | null = null
  let operationGeneration = 0
  let pendingLoad = false

  const terminalStatuses = ['COMPLETED', 'REJECTED', 'FAILED', 'WORK_ORDER_FAILED']
  const isTerminalStatus = (status?: string) => terminalStatuses.includes(status ?? '')
  const isTerminal = computed(() => isTerminalStatus(workflow.value?.status))

  function closeStream() {
    eventSource?.close()
    eventSource = null
  }

  function reset(): void {
    operationGeneration++
    pendingLoad = false
    closeStream()
    workflow.value = null
    events.value = []
    loading.value = false
    approving.value = false
    approvalNeedsRefresh.value = false
    error.value = ''
    approvalKey = null
  }

  function cancelPendingLoad(): void {
    if (!pendingLoad) return
    operationGeneration++
    pendingLoad = false
    closeStream()
    workflow.value = null
    events.value = []
    loading.value = false
    approving.value = false
    approvalNeedsRefresh.value = false
    error.value = ''
    approvalKey = null
  }

  function mergeWorkflow(next: WorkflowResponse): WorkflowResponse {
    const current = workflow.value
    const merged = current?.workflowId === next.workflowId
      ? {
          ...next,
          approval: next.approval ?? current.approval,
          workOrder: next.workOrder ?? current.workOrder,
          eventSequence: Math.max(next.eventSequence, current.eventSequence),
        }
      : next
    workflow.value = merged
    return merged
  }

  function isCurrent(generation: number, workflowId: string): boolean {
    return generation === operationGeneration && workflow.value?.workflowId === workflowId
  }

  function handleEvent(event: WorkflowEvent, generation: number, workflowId: string) {
    if (!isCurrent(generation, workflowId)) return
    if (!events.value.some((item) => item.eventId === event.eventId)) {
      events.value = [...events.value, event]
    }
    if (workflow.value) {
      workflow.value = { ...workflow.value, eventSequence: Math.max(workflow.value.eventSequence, event.sequence) }
    }
    if (['COMPLETED', 'FAILED'].includes(event.type)) {
      void refresh(generation, workflowId)
    }
  }

  async function refresh(generation = operationGeneration, workflowId = workflow.value?.workflowId): Promise<WorkflowResponse | null> {
    if (!workflowId || !isCurrent(generation, workflowId)) return null
    error.value = ''
    try {
      const refreshed = await getWorkflow(workflowId)
      if (!isCurrent(generation, workflowId)) return null
      if (refreshed.workflowId !== workflowId) {
        approvalNeedsRefresh.value = workflow.value?.status === 'WAITING_APPROVAL'
        error.value = '工作流状态回查返回了不匹配的对象，已保留当前结果。'
        return null
      }
      const merged = mergeWorkflow(refreshed)
      approvalNeedsRefresh.value = false
      return merged
    } catch (cause) {
      if (!isCurrent(generation, workflowId)) return null
      const detail = cause instanceof Error ? cause.message : '无法读取工作流状态'
      error.value = `最新工作流状态暂未确认：${detail}`
      return null
    }
  }

  function subscribeIfLive(result: WorkflowResponse, generation: number): void {
    if (isTerminalStatus(result.status)) return
    try {
      eventSource = subscribeToWorkflow(result.workflowId,
        (event) => handleEvent(event, generation, result.workflowId), () => {
        if (isCurrent(generation, result.workflowId) && !isTerminal.value) {
          error.value = '实时事件连接中断，请检查后端服务。'
        }
      })
    } catch {
      if (isCurrent(generation, result.workflowId) && !isTerminal.value) {
        error.value = '实时事件连接中断，请检查后端服务。'
      }
      eventSource = null
    }
  }

  async function start(alertId: string): Promise<WorkflowResponse | null> {
    const generation = ++operationGeneration
    pendingLoad = false
    closeStream()
    loading.value = true
    approving.value = false
    approvalNeedsRefresh.value = false
    error.value = ''
    events.value = []
    approvalKey = null
    try {
      const result = await startWorkflow(alertId)
      if (generation !== operationGeneration) return null
      mergeWorkflow(result)
      subscribeIfLive(result, generation)
      return result
    } catch (cause) {
      if (generation !== operationGeneration) return null
      error.value = cause instanceof Error ? cause.message : '无法启动工作流'
      return null
    } finally {
      if (generation === operationGeneration) {
        pendingLoad = false
        loading.value = false
      }
    }
  }

  async function load(workflowId: string): Promise<WorkflowResponse | null> {
    const generation = ++operationGeneration
    pendingLoad = true
    closeStream()
    loading.value = true
    approving.value = false
    approvalNeedsRefresh.value = false
    error.value = ''
    events.value = []
    approvalKey = null
    workflow.value = null
    try {
      const result = await getWorkflow(workflowId)
      if (generation !== operationGeneration) return null
      mergeWorkflow(result)
      if (isTerminalStatus(result.status)) {
        try {
          const history = await getWorkflowEventHistory(result.workflowId)
          if (generation !== operationGeneration) return null
          events.value = history
        } catch (cause) {
          if (generation === operationGeneration) {
            error.value = cause instanceof Error ? cause.message : '无法读取工作流事件历史'
          }
        }
      } else {
        subscribeIfLive(result, generation)
      }
      pendingLoad = false
      return result
    } catch (cause) {
      if (generation !== operationGeneration) return null
      error.value = cause instanceof Error ? cause.message : '无法读取工作流'
      return null
    } finally {
      if (generation === operationGeneration) {
        pendingLoad = false
        loading.value = false
      }
    }
  }

  async function approve(payload: { decision: 'APPROVE' | 'REJECT'; reviewer: string; comment: string; role: DemoRole }) {
    if (!workflow.value || workflow.value.status !== 'WAITING_APPROVAL'
      || approving.value || approvalNeedsRefresh.value) return
    const generation = operationGeneration
    const workflowId = workflow.value.workflowId
    approving.value = true
    error.value = ''
    try {
      approvalKey ??= createRequestId()
      const { role, ...decision } = payload
      const approved = await submitApproval(workflowId, {
        ...decision,
        idempotencyKey: approvalKey,
      }, role)
      if (!isCurrent(generation, workflowId)) return
      if (approved.workflowId !== workflowId) {
        approvalNeedsRefresh.value = true
        error.value = '确认结果暂时未知，审批响应返回了不匹配的对象；请刷新状态后再处理。'
        return
      }
      mergeWorkflow(approved)
      approvalKey = null
      await refresh(generation, workflowId)
    } catch (cause) {
      if (!isCurrent(generation, workflowId)) return
      if (!(cause instanceof WorkflowApiError) || cause.status >= 500 || cause.status === 409) {
        try {
          const reconciled = await getWorkflow(workflowId)
          if (!isCurrent(generation, workflowId)) return
          if (reconciled.workflowId !== workflowId) {
            approvalNeedsRefresh.value = true
            error.value = '确认结果暂时未知，状态回查返回了不匹配的对象；请刷新状态后再处理。'
            return
          }
          const merged = mergeWorkflow(reconciled)
          if (merged.approval || merged.workOrder || merged.status !== 'WAITING_APPROVAL') {
            approvalKey = null
            approvalNeedsRefresh.value = false
            error.value = ''
            return
          }
        } catch {
          if (!isCurrent(generation, workflowId)) return
          approvalNeedsRefresh.value = true
          error.value = '确认结果暂时未知，状态回查失败；请刷新状态后再处理。'
          return
        }
        approvalNeedsRefresh.value = true
        error.value = cause instanceof WorkflowApiError && cause.status === 409
          ? `审批状态冲突：${cause.message}。权威状态仍为待确认，请先刷新状态后再决定是否重试。`
          : '确认结果暂时未知，权威状态仍为待确认；请先刷新状态后再决定是否重试。'
      } else {
        approvalNeedsRefresh.value = false
        error.value = cause.message
      }
    } finally {
      if (isCurrent(generation, workflowId)) approving.value = false
    }
  }

  onScopeDispose(reset)
  return { workflow, events, loading, approving, approvalNeedsRefresh, error, isTerminal, start, load, approve, refresh, closeStream, cancelPendingLoad, reset }
}
