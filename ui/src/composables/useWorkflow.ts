import { computed, onScopeDispose, ref } from 'vue'
import { getWorkflow, startWorkflow, submitApproval, subscribeToWorkflow } from '../services/workflowApi'
import type { DemoRole, WorkflowEvent, WorkflowResponse } from '../types/workflow'

export function useWorkflow() {
  const workflow = ref<WorkflowResponse | null>(null)
  const events = ref<WorkflowEvent[]>([])
  const loading = ref(false)
  const approving = ref(false)
  const error = ref('')
  let eventSource: EventSource | null = null
  let approvalKey: string | null = null
  let operationGeneration = 0

  const isTerminal = computed(() => ['COMPLETED', 'REJECTED', 'FAILED', 'WORK_ORDER_FAILED'].includes(workflow.value?.status ?? ''))

  function closeStream() {
    eventSource?.close()
    eventSource = null
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

  async function start(alertId: string): Promise<WorkflowResponse | null> {
    const generation = ++operationGeneration
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
      eventSource = subscribeToWorkflow(result.workflowId,
        (event) => handleEvent(event, generation, result.workflowId), () => {
        if (isCurrent(generation, result.workflowId) && !isTerminal.value) {
          error.value = '实时事件连接中断，请检查后端服务。'
        }
      })
      return result
    } catch (cause) {
      if (generation !== operationGeneration) return null
      error.value = cause instanceof Error ? cause.message : '无法启动工作流'
      return null
    } finally {
      if (generation === operationGeneration) loading.value = false
    }
  }

  async function approve(payload: { decision: 'APPROVE' | 'REJECT'; reviewer: string; comment: string; role: DemoRole }) {
    if (!workflow.value) return
    const generation = operationGeneration
    const workflowId = workflow.value.workflowId
    approving.value = true
    error.value = ''
    try {
      approvalKey ??= crypto.randomUUID()
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

  onScopeDispose(() => {
    operationGeneration++
    closeStream()
  })
  return { workflow, events, loading, approving, error, isTerminal, start, approve, refresh, closeStream }
}
