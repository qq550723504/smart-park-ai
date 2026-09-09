import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrchestrationPanel from './OrchestrationPanel.vue'
import { cancelOrchestration, getOrchestration, OrchestrationApiError, startOrchestration } from '../../services/orchestrationApi'
import type { OrchestrationRun, OrchestrationStepStatus, OrchestrationStatus } from '../../types/orchestration'

vi.mock('../../services/orchestrationApi', () => ({
  OrchestrationApiError: class OrchestrationApiError extends Error {
    constructor(public readonly status: number, message: string) {
      super(message)
      this.name = 'OrchestrationApiError'
    }
  },
  startOrchestration: vi.fn(),
  getOrchestration: vi.fn(),
  cancelOrchestration: vi.fn(),
}))

function run(status: OrchestrationStatus = 'RUNNING', stepStatus: OrchestrationStepStatus = 'RUNNING'): OrchestrationRun {
  return {
    runId: '11111111-1111-1111-1111-111111111111',
    definitionId: 'JOINT_ANOMALY_ASSESSMENT',
    status,
    createdAt: '2026-09-08T00:00:00Z',
    startedAt: '2026-09-08T00:00:00Z', completedAt: status === 'RUNNING' ? null : '2026-09-08T00:01:00Z',
    role: 'ADMIN',
    input: { question: '研判 B1', alertId: 'ALT-TEMP-001', buildingIds: ['B1'], energyRelated: true,
      crossDomain: true, securityRelated: true, requestAction: true },
    summary: status === 'PARTIAL' ? '部分结论' : null,
    steps: [{ id: 'operations-analysis', type: 'OPERATIONS_ANALYSIS', capability: 'analytics', required: true,
      status: stepStatus, startedAt: '2026-09-08T00:00:00Z', completedAt: null,
      inputSummary: '调用真实分析', outputSummary: null, runReference: 'child-run',
      evidenceReferences: [], sourceReferences: [], recommendations: [],
      failureReason: stepStatus === 'SKIPPED' ? '能力不可用' : null,
      approvalResult: null, approvalExpiresAt: null }],
    evidence: [], traceId: '11111111-1111-1111-1111-111111111111', failureReason: null,
    result: status === 'PARTIAL' ? { conclusion: '部分结论', recommendations: ['人工复核'],
      evidenceReferences: ['analysis:child-run'], sourceReferences: ['analytics'], childRuns: {},
      skippedSteps: ['security-review'], partialReasons: ['安全域未完成研判'], humanApprovalResult: null } : null,
    cancelRequested: false, revision: 4,
  }
}

beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
})

describe('OrchestrationPanel', () => {
  it('starts the real backend run and renders only returned step state', async () => {
    vi.mocked(startOrchestration).mockResolvedValue({ runId: run().runId, status: 'RUNNING',
      statusUrl: '/status', traceUrl: '/trace', idempotentReplay: false })
    vi.mocked(getOrchestration).mockResolvedValue(run())
    const wrapper = mount(OrchestrationPanel, { props: { role: 'ADMIN', available: true } })

    await wrapper.get('[data-start-orchestration]').trigger('click')
    await flushPromises()

    expect(startOrchestration).toHaveBeenCalledOnce()
    expect(vi.mocked(startOrchestration).mock.calls[0][2]).toMatchObject({ alertId: 'ALT-ORCH-ENERGY-B1-001', buildingIds: ['B1'],
      energyRelated: true, crossDomain: true, securityRelated: true, requestAction: true })
    expect(wrapper.get('[data-step-status="RUNNING"]').text()).toContain('调用真实分析')
    expect(wrapper.emitted('open-trace')).toEqual([[run().traceId]])
    wrapper.unmount()
  })

  it('shows skipped, partial and evidence from the server without claiming full completion', async () => {
    localStorage.setItem('smartpark.orchestration.last.ADMIN', run().runId)
    vi.mocked(getOrchestration).mockResolvedValue(run('PARTIAL', 'SKIPPED'))
    const wrapper = mount(OrchestrationPanel, { props: { role: 'ADMIN', available: true } })
    await flushPromises()

    expect(wrapper.get('[data-run-status="PARTIAL"]').text()).toBe('部分完成')
    expect(wrapper.get('[data-step-status="SKIPPED"]').text()).toContain('已跳过')
    expect(wrapper.text()).toContain('安全域未完成研判')
    expect(wrapper.text()).toContain('人工复核')
    expect(wrapper.text()).toContain('analysis:child-run')
    wrapper.unmount()
  })

  it('restores a saved run on refresh and never starts a duplicate', async () => {
    localStorage.setItem('smartpark.orchestration.last.APPROVER', run().runId)
    vi.mocked(getOrchestration).mockResolvedValue(run('WAITING_APPROVAL', 'WAITING_APPROVAL'))
    const wrapper = mount(OrchestrationPanel, { props: { role: 'APPROVER', available: true } })
    await flushPromises()

    expect(getOrchestration).toHaveBeenCalledWith('APPROVER', run().runId)
    expect(startOrchestration).not.toHaveBeenCalled()
    expect(wrapper.get('[data-run-status="WAITING_APPROVAL"]').text()).toBe('等待人工审批')
    wrapper.unmount()
  })

  it('blocks launch while a saved run is still being restored', async () => {
    let resolveGet!: (value: OrchestrationRun) => void
    localStorage.setItem('smartpark.orchestration.last.OPERATOR', run().runId)
    vi.mocked(getOrchestration).mockReturnValue(new Promise((resolve) => { resolveGet = resolve }))
    const wrapper = mount(OrchestrationPanel, { props: { role: 'OPERATOR', available: true } })

    expect(wrapper.get('[data-start-orchestration]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-start-orchestration]').trigger('click')
    expect(startOrchestration).not.toHaveBeenCalled()

    resolveGet({ ...run(), role: 'OPERATOR' })
    await flushPromises()
    expect(wrapper.get('[data-run-status="RUNNING"]').text()).toBe('执行中')
    wrapper.unmount()
  })

  it('does not expose a launch action to customer agent', () => {
    const wrapper = mount(OrchestrationPanel, { props: { role: 'CUSTOMER_AGENT', available: true } })
    expect(wrapper.get('[data-start-orchestration]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('stays unavailable when the required analytics capability is offline', () => {
    const wrapper = mount(OrchestrationPanel, { props: { role: 'ADMIN', available: false } })

    expect(wrapper.get('[data-start-orchestration]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Operations Analysis 当前未启用')
    wrapper.unmount()
  })

  it('clears loading when an in-flight launch is invalidated', async () => {
    let resolveStart!: (value: Awaited<ReturnType<typeof startOrchestration>>) => void
    vi.mocked(startOrchestration).mockReturnValue(new Promise((resolve) => { resolveStart = resolve }))
    const wrapper = mount(OrchestrationPanel, { props: { role: 'ADMIN', active: true, available: true } })

    await wrapper.get('[data-start-orchestration]').trigger('click')
    expect(wrapper.get('[data-start-orchestration]').text()).toBe('处理中…')
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })

    expect(wrapper.get('[data-start-orchestration]').text()).toBe('运行完整研判')
    expect(wrapper.get('[data-start-orchestration]').attributes('disabled')).toBeUndefined()
    resolveStart({ runId: run().runId, status: 'RUNNING', statusUrl: '/status',
      traceUrl: '/trace', idempotentReplay: false })
    await flushPromises()
    expect(getOrchestration).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not assign a post-start response after the role changes', async () => {
    let resolveGet!: (value: OrchestrationRun) => void
    vi.mocked(startOrchestration).mockResolvedValue({ runId: run().runId, status: 'RUNNING',
      statusUrl: '/status', traceUrl: '/trace', idempotentReplay: false })
    vi.mocked(getOrchestration).mockReturnValue(new Promise((resolve) => { resolveGet = resolve }))
    const wrapper = mount(OrchestrationPanel, { props: { role: 'ADMIN', available: true } })

    await wrapper.get('[data-start-orchestration]').trigger('click')
    await flushPromises()
    await wrapper.setProps({ role: 'VIEWER' })
    resolveGet(run())
    await flushPromises()

    expect(wrapper.find('[data-run-status="RUNNING"]').exists()).toBe(false)
    expect(wrapper.emitted('open-trace')).toBeUndefined()
    wrapper.unmount()
  })

  it('discards a definitively conflicting pending key before the next launch', async () => {
    localStorage.setItem('smartpark.orchestration.pending.OPERATOR', 'stale-key')
    vi.mocked(startOrchestration)
      .mockRejectedValueOnce(new OrchestrationApiError(409, 'Idempotency-Key conflicts with another request'))
      .mockResolvedValueOnce({ runId: run().runId, status: 'RUNNING', statusUrl: '/status',
        traceUrl: '/trace', idempotentReplay: false })
    vi.mocked(getOrchestration).mockResolvedValue({ ...run(), role: 'OPERATOR' })
    const wrapper = mount(OrchestrationPanel, { props: { role: 'OPERATOR', available: true } })

    await wrapper.get('[data-start-orchestration]').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('smartpark.orchestration.pending.OPERATOR')).toBeNull()

    await wrapper.get('[data-start-orchestration]').trigger('click')
    await flushPromises()
    expect(startOrchestration).toHaveBeenCalledTimes(2)
    expect(vi.mocked(startOrchestration).mock.calls[0][1]).toBe('stale-key')
    expect(vi.mocked(startOrchestration).mock.calls[1][1]).not.toBe('stale-key')
    wrapper.unmount()
  })

  it('polls the newly accepted run when its immediate GET fails after an old terminal run', async () => {
    vi.useFakeTimers()
    try {
      const oldRun = run('COMPLETED', 'COMPLETED')
      const newRunId = '22222222-2222-2222-2222-222222222222'
      localStorage.setItem('smartpark.orchestration.last.OPERATOR', oldRun.runId)
      vi.mocked(startOrchestration).mockResolvedValue({ runId: newRunId, status: 'RUNNING',
        statusUrl: '/status', traceUrl: '/trace', idempotentReplay: false })
      vi.mocked(getOrchestration)
        .mockResolvedValueOnce({ ...oldRun, role: 'OPERATOR' })
        .mockRejectedValueOnce(new Error('temporary post-start GET failure'))
        .mockResolvedValueOnce({ ...run(), runId: newRunId, traceId: newRunId, role: 'OPERATOR' })
      const wrapper = mount(OrchestrationPanel, { props: { role: 'OPERATOR', available: true } })
      await flushPromises()

      await wrapper.get('[data-start-orchestration]').trigger('click')
      await flushPromises()
      expect(localStorage.getItem('smartpark.orchestration.last.OPERATOR')).toBe(newRunId)
      expect(wrapper.text()).toContain('temporary post-start GET failure')

      await vi.advanceTimersByTimeAsync(1000)
      await flushPromises()

      expect(getOrchestration).toHaveBeenLastCalledWith('OPERATOR', newRunId)
      expect(wrapper.get('[data-run-status="RUNNING"]').text()).toBe('执行中')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('retries a transient refresh failure with capped exponential backoff', async () => {
    vi.useFakeTimers()
    try {
      localStorage.setItem('smartpark.orchestration.last.OPERATOR', run().runId)
      vi.mocked(getOrchestration)
        .mockRejectedValueOnce(new Error('temporary outage'))
        .mockResolvedValueOnce({ ...run('COMPLETED', 'COMPLETED'), role: 'OPERATOR' })
      const wrapper = mount(OrchestrationPanel, { props: { role: 'OPERATOR', available: true } })
      await flushPromises()

      expect(wrapper.text()).toContain('temporary outage')
      expect(wrapper.get('[data-start-orchestration]').attributes('disabled')).toBeDefined()
      await vi.advanceTimersByTimeAsync(1999)
      expect(getOrchestration).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(1)
      await flushPromises()

      expect(getOrchestration).toHaveBeenCalledTimes(2)
      expect(wrapper.get('[data-run-status="COMPLETED"]').text()).toBe('已完成')
      expect(wrapper.text()).not.toContain('temporary outage')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('clears a stale saved run after a definitive 404 and releases launch', async () => {
    vi.useFakeTimers()
    try {
      localStorage.setItem('smartpark.orchestration.last.OPERATOR', run().runId)
      vi.mocked(getOrchestration).mockRejectedValue(new OrchestrationApiError(404, 'missing'))
      const wrapper = mount(OrchestrationPanel, { props: { role: 'OPERATOR', available: true } })
      await flushPromises()

      expect(localStorage.getItem('smartpark.orchestration.last.OPERATOR')).toBeNull()
      expect(wrapper.get('[data-start-orchestration]').attributes('disabled')).toBeUndefined()
      expect(wrapper.text()).toContain('上次编排记录已失效，请重新启动')
      await vi.advanceTimersByTimeAsync(20_000)
      expect(getOrchestration).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not let an older polling response overwrite a newer cancelled state', async () => {
    vi.useFakeTimers()
    let resolvePoll!: (value: OrchestrationRun) => void
    try {
      localStorage.setItem('smartpark.orchestration.last.OPERATOR', run().runId)
      vi.mocked(getOrchestration)
        .mockResolvedValueOnce({ ...run(), role: 'OPERATOR', revision: 4 })
        .mockReturnValueOnce(new Promise((resolve) => { resolvePoll = resolve }))
      vi.mocked(cancelOrchestration).mockResolvedValue({
        ...run('CANCELLED', 'CANCELLED'), role: 'OPERATOR', revision: 6,
      })
      const wrapper = mount(OrchestrationPanel, {
        props: { role: 'OPERATOR', available: true },
      })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(1000)

      await wrapper.get('[data-cancel-orchestration]').trigger('click')
      await flushPromises()
      resolvePoll({ ...run(), role: 'OPERATOR', revision: 5 })
      await flushPromises()

      expect(wrapper.get('[data-run-status="CANCELLED"]').text()).toBe('已取消')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('cancels through the backend and renders the returned terminal state', async () => {
    localStorage.setItem('smartpark.orchestration.last.OPERATOR', run().runId)
    vi.mocked(getOrchestration).mockResolvedValue({ ...run(), role: 'OPERATOR' })
    vi.mocked(cancelOrchestration).mockResolvedValue({ ...run('CANCELLED', 'CANCELLED'), role: 'OPERATOR' })
    const wrapper = mount(OrchestrationPanel, { props: { role: 'OPERATOR', available: true } })
    await flushPromises()
    await wrapper.get('[data-cancel-orchestration]').trigger('click')
    await flushPromises()

    expect(cancelOrchestration).toHaveBeenCalledWith('OPERATOR', run().runId)
    expect(wrapper.get('[data-run-status="CANCELLED"]').text()).toBe('已取消')
    wrapper.unmount()
  })
})
