import { beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useWorkflow } from './useWorkflow'
import * as workflowApi from '../services/workflowApi'
import { startWorkflow } from '../services/workflowApi'
import type { WorkflowResponse } from '../types/workflow'

vi.mock('../services/workflowApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/workflowApi')>()
  return {
    ...actual,
    getWorkflow: vi.fn(),
    getWorkflowEventHistory: vi.fn(),
    startWorkflow: vi.fn(),
    submitApproval: vi.fn(),
  }
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

function workflow(workflowId: string): WorkflowResponse {
  return { workflowId, alertId: 'ALT-TEMP-001', status: 'RUNNING', diagnosis: null, approval: null, workOrder: null, errors: [], eventSequence: 1, riskReasons: [] }
}

describe('useWorkflow', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
  })

  it('keeps the later start and stream when an earlier start resolves late', async () => {
    const first = deferred<ReturnType<typeof workflow>>()
    const second = deferred<ReturnType<typeof workflow>>()
    const streamErrors: Array<() => void> = []
    vi.mocked(startWorkflow)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    vi.spyOn(workflowApi, 'subscribeToWorkflow').mockImplementation((_id, _event, onError) => {
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
    expect(workflowApi.subscribeToWorkflow).toHaveBeenCalledTimes(1)
    expect(workflowApi.subscribeToWorkflow).toHaveBeenLastCalledWith('wf-b', expect.any(Function), expect.any(Function))

    streamErrors[0]?.()
    expect(binding.error.value).toBe('实时事件连接中断，请检查后端服务。')
    scope.stop()
  })

  it('ignores a closed stream callback after a later workflow owns the session', async () => {
    const streamErrors: Array<() => void> = []
    vi.mocked(startWorkflow)
      .mockResolvedValueOnce(workflow('wf-a'))
      .mockResolvedValueOnce(workflow('wf-b'))
    vi.spyOn(workflowApi, 'subscribeToWorkflow').mockImplementation((_id, _event, onError) => {
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

  it('keeps an accepted workflow when EventSource construction throws', async () => {
    const originalEventSource = globalThis.EventSource
    globalThis.EventSource = class {
      constructor() {
        throw new Error('SSE construction failed')
      }
    } as unknown as typeof EventSource
    vi.mocked(startWorkflow).mockResolvedValue(workflow('wf-b'))
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    try {
      await expect(binding.start('B')).resolves.toMatchObject({ workflowId: 'wf-b' })
      expect(binding.workflow.value?.workflowId).toBe('wf-b')
      expect(binding.error.value).toBe('实时事件连接中断，请检查后端服务。')
    } finally {
      scope.stop()
      globalThis.EventSource = originalEventSource
    }
  })

  it('resets workflow state and closes its stream without affecting other sessions', async () => {
    const close = vi.fn()
    vi.mocked(startWorkflow).mockResolvedValue(workflow('wf-reset'))
    vi.spyOn(workflowApi, 'subscribeToWorkflow').mockReturnValue({ close } as unknown as EventSource)
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    await binding.start('A')
    binding.reset()

    expect(close).toHaveBeenCalledTimes(1)
    expect(binding.workflow.value).toBeNull()
    expect(binding.events.value).toEqual([])
    expect(binding.loading.value).toBe(false)
    expect(binding.approving.value).toBe(false)
    expect(binding.error.value).toBe('')
    scope.stop()
  })

  it('clears the previous workflow while loading a selected workflow', async () => {
    const selected = deferred<WorkflowResponse>()
    vi.mocked(startWorkflow).mockResolvedValue(workflow('wf-old'))
    vi.mocked(workflowApi.getWorkflow).mockReturnValueOnce(selected.promise)
    vi.spyOn(workflowApi, 'subscribeToWorkflow').mockReturnValue({ close: vi.fn() } as unknown as EventSource)
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    await binding.start('A')
    const loading = binding.load('wf-selected')
    expect(binding.workflow.value).toBeNull()

    selected.resolve(workflow('wf-selected'))
    await expect(loading).resolves.toMatchObject({ workflowId: 'wf-selected' })
    expect(binding.workflow.value?.workflowId).toBe('wf-selected')
    scope.stop()
  })

  it('cancels an abandoned workflow load before it can subscribe', async () => {
    const selected = deferred<WorkflowResponse>()
    vi.mocked(workflowApi.getWorkflow).mockReturnValueOnce(selected.promise)
    const subscribe = vi.spyOn(workflowApi, 'subscribeToWorkflow')
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    const loading = binding.load('wf-abandoned')
    binding.cancelPendingLoad()
    selected.resolve(workflow('wf-abandoned'))

    await expect(loading).resolves.toBeNull()
    expect(binding.workflow.value).toBeNull()
    expect(binding.loading.value).toBe(false)
    expect(subscribe).not.toHaveBeenCalled()
    scope.stop()
  })

  it('does not subscribe to a terminal workflow loaded from the queue', async () => {
    vi.mocked(workflowApi.getWorkflow).mockResolvedValueOnce({
      ...workflow('wf-completed'),
      status: 'COMPLETED',
    })
    const subscribe = vi.spyOn(workflowApi, 'subscribeToWorkflow')
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    await expect(binding.load('wf-completed')).resolves.toMatchObject({ workflowId: 'wf-completed', status: 'COMPLETED' })

    expect(binding.isTerminal.value).toBe(true)
    expect(subscribe).not.toHaveBeenCalled()
    scope.stop()
  })

  it('hydrates terminal workflow history through a finite request', async () => {
    vi.mocked(workflowApi.getWorkflow).mockResolvedValueOnce({
      ...workflow('wf-history'),
      status: 'FAILED',
    })
    vi.mocked(workflowApi.getWorkflowEventHistory).mockResolvedValueOnce([
      {
        eventId: '1', type: 'NODE_STARTED', node: 'diagnoseAlert', sequence: 1,
        timestamp: '2026-09-01T00:00:00Z', redactedSummary: 'diagnoseAlert started',
      },
    ])
    const scope = effectScope()
    let binding!: ReturnType<typeof useWorkflow>
    scope.run(() => { binding = useWorkflow() })

    await expect(binding.load('wf-history')).resolves.toMatchObject({ workflowId: 'wf-history', status: 'FAILED' })

    expect(workflowApi.getWorkflowEventHistory).toHaveBeenCalledWith('wf-history')
    expect(binding.events.value).toHaveLength(1)
    expect(binding.events.value[0]).toMatchObject({ eventId: '1', node: 'diagnoseAlert' })
    scope.stop()
  })
})
