import { beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useWorkflow } from './useWorkflow'
import { startWorkflow, subscribeToWorkflow } from '../services/workflowApi'
import type { WorkflowResponse } from '../types/workflow'

vi.mock('../services/workflowApi', () => ({
  getWorkflow: vi.fn(),
  startWorkflow: vi.fn(),
  submitApproval: vi.fn(),
  subscribeToWorkflow: vi.fn(),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

function workflow(workflowId: string): WorkflowResponse {
  return { workflowId, alertId: 'ALT-TEMP-001', status: 'RUNNING', diagnosis: null, approval: null, workOrder: null, errors: [], eventSequence: 1, riskReasons: [] }
}

describe('useWorkflow', () => {
  beforeEach(() => vi.clearAllMocks())

  it('keeps the later start and stream when an earlier start resolves late', async () => {
    const first = deferred<ReturnType<typeof workflow>>()
    const second = deferred<ReturnType<typeof workflow>>()
    const streamErrors: Array<() => void> = []
    vi.mocked(startWorkflow)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    vi.mocked(subscribeToWorkflow).mockImplementation((_id, _event, onError) => {
      streamErrors.push(onError)
      return { close: vi.fn() } as unknown as EventSource
    })
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    const startingA = binding.start('A')
    const startingB = binding.start('B')
    second.resolve(workflow('wf-b'))
    await expect(startingB).resolves.toMatchObject({ workflowId: 'wf-b' })
    first.resolve(workflow('wf-a'))

    await expect(startingA).resolves.toBeNull()
    expect(binding.workflow.value?.workflowId).toBe('wf-b')
    expect(vi.mocked(subscribeToWorkflow)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(subscribeToWorkflow)).toHaveBeenLastCalledWith('wf-b', expect.any(Function), expect.any(Function))

    streamErrors[0]?.()
    expect(binding.error.value).toBe('实时事件连接中断，请检查后端服务。')
    scope.stop()
  })

  it('ignores a closed stream callback after a later workflow owns the session', async () => {
    const streamErrors: Array<() => void> = []
    vi.mocked(startWorkflow)
      .mockResolvedValueOnce(workflow('wf-a'))
      .mockResolvedValueOnce(workflow('wf-b'))
    vi.mocked(subscribeToWorkflow).mockImplementation((_id, _event, onError) => {
      streamErrors.push(onError)
      return { close: vi.fn() } as unknown as EventSource
    })
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    await binding.start('A')
    await binding.start('B')
    streamErrors[0]?.()

    expect(binding.workflow.value?.workflowId).toBe('wf-b')
    expect(binding.error.value).toBe('')
    scope.stop()
  })
})
