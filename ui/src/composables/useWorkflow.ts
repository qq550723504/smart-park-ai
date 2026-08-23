import { computed, onBeforeUnmount, ref } from 'vue'
import { getWorkflow, startWorkflow, submitApproval, subscribeToWorkflow } from '../services/workflowApi'
import type { WorkflowEvent, WorkflowResponse } from '../types/workflow'

export function useWorkflow() {
  const workflow = ref<WorkflowResponse | null>(null)
  const events = ref<WorkflowEvent[]>([])
  const loading = ref(false)
  const approving = ref(false)
  const error = ref('')
  let eventSource: EventSource | null = null
  let approvalKey: string | null = null

  const isTerminal = computed(() => ['COMPLETED', 'REJECTED', 'FAILED'].includes(workflow.value?.status ?? ''))

  function closeStream() {
    eventSource?.close()
    eventSource = null
  }

  function mergeWorkflow(next: WorkflowResponse) {
    workflow.value = next
  }

  function handleEvent(event: WorkflowEvent) {
    if (!events.value.some((item) => item.eventId === event.eventId)) {
      events.value = [...events.value, event]
    }
    if (workflow.value) {
      workflow.value = { ...workflow.value, eventSequence: Math.max(workflow.value.eventSequence, event.sequence) }
    }
    if (['COMPLETED', 'FAILED'].includes(event.type)) {
      void refresh()
    }
  }

  async function refresh() {
    if (!workflow.value) return
    try {
      mergeWorkflow(await getWorkflow(workflow.value.workflowId))
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '无法刷新工作流状态'
    }
  }

  async function start(alertId: string) {
    closeStream()
    loading.value = true
    error.value = ''
    events.value = []
    approvalKey = null
    try {
      const result = await startWorkflow(alertId)
      mergeWorkflow(result)
      eventSource = subscribeToWorkflow(result.workflowId, handleEvent, () => {
        if (!isTerminal.value) error.value = '实时事件连接中断，请检查后端服务。'
      })
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '无法启动工作流'
    } finally {
      loading.value = false
    }
  }

  async function approve(payload: { decision: 'APPROVE' | 'REJECT'; reviewer: string; comment: string }) {
    if (!workflow.value) return
    approving.value = true
    error.value = ''
    try {
      approvalKey ??= crypto.randomUUID()
      mergeWorkflow(await submitApproval(workflow.value.workflowId, {
        ...payload,
        idempotencyKey: approvalKey,
      }))
      approvalKey = null
      await refresh()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '审批提交失败'
    } finally {
      approving.value = false
    }
  }

  onBeforeUnmount(closeStream)
  return { workflow, events, loading, approving, error, isTerminal, start, approve, refresh, closeStream }
}
