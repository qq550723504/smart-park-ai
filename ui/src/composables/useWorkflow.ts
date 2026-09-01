import { computed, onScopeDispose, ref } from 'vue'
import { getWorkflow, startWorkflow, submitApproval, subscribeToWorkflow } from '../services/workflowApi'
import type { DemoRole, WorkflowEvent, WorkflowResponse } from '../types/workflow'
import { createRequestId } from '../utils/requestId'

export function useWorkflow() {
  const workflow = ref<WorkflowResponse | null>(null)
  const events = ref<WorkflowEvent[]>([])
  const loading = ref(false)
  const approving = ref(false)
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
    error.value = ''
    approvalKey = null
  }

  function mergeWorkflow(next: WorkflowResponse) {
    workflow.value = next
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

  async function refresh(generation = operationGeneration, workflowId = workflow.value?.workflowId) {
    if (!workflowId || !isCurrent(generation, workflowId)) return
    try {
      const refreshed = await getWorkflow(workflowId)
      if (!isCurrent(generation, workflowId)) return
      mergeWorkflow(refreshed)
    } catch (cause) {
      if (!isCurrent(generation, workflowId)) return
      error.value = cause instanceof Error ? cause.message : '无法刷新工作流状态'
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
    error.value = ''
    events.value = []
    approvalKey = null
    workflow.value = null
    try {
      const result = await getWorkflow(workflowId)
      if (generation !== operationGeneration) return null
      mergeWorkflow(result)
      pendingLoad = false
      subscribeIfLive(result, generation)
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
    if (!workflow.value) return
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
      mergeWorkflow(approved)
      approvalKey = null
      await refresh(generation, workflowId)
    } catch (cause) {
      if (!isCurrent(generation, workflowId)) return
      error.value = cause instanceof Error ? cause.message : '审批提交失败'
    } finally {
      if (isCurrent(generation, workflowId)) approving.value = false
    }
  }

  onScopeDispose(reset)
  return { workflow, events, loading, approving, error, isTerminal, start, load, approve, refresh, closeStream, cancelPendingLoad, reset }
}
