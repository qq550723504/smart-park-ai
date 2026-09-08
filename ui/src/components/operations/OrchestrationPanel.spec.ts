import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrchestrationPanel from './OrchestrationPanel.vue'
import { cancelOrchestration, getOrchestration, startOrchestration } from '../../services/orchestrationApi'
import type { OrchestrationRun, OrchestrationStepStatus, OrchestrationStatus } from '../../types/orchestration'

vi.mock('../../services/orchestrationApi', () => ({
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
      evidenceReferences: [], recommendations: [], failureReason: stepStatus === 'SKIPPED' ? '能力不可用' : null }],
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
    const wrapper = mount(OrchestrationPanel, { props: { role: 'ADMIN' } })

    await wrapper.get('[data-start-orchestration]').trigger('click')
    await flushPromises()

    expect(startOrchestration).toHaveBeenCalledOnce()
    expect(vi.mocked(startOrchestration).mock.calls[0][2]).toMatchObject({ buildingIds: ['B1'],
      energyRelated: true, crossDomain: true, securityRelated: true, requestAction: true })
    expect(wrapper.get('[data-step-status="RUNNING"]').text()).toContain('调用真实分析')
    expect(wrapper.emitted('open-trace')).toEqual([[run().traceId]])
    wrapper.unmount()
  })

  it('shows skipped, partial and evidence from the server without claiming full completion', async () => {
    localStorage.setItem('smartpark.orchestration.last.ADMIN', run().runId)
    vi.mocked(getOrchestration).mockResolvedValue(run('PARTIAL', 'SKIPPED'))
    const wrapper = mount(OrchestrationPanel, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.get('[data-run-status="PARTIAL"]').text()).toBe('部分完成')
    expect(wrapper.get('[data-step-status="SKIPPED"]').text()).toContain('已跳过')
    expect(wrapper.text()).toContain('安全域未完成研判')
    expect(wrapper.text()).toContain('analysis:child-run')
    wrapper.unmount()
  })

  it('restores a saved run on refresh and never starts a duplicate', async () => {
    localStorage.setItem('smartpark.orchestration.last.APPROVER', run().runId)
    vi.mocked(getOrchestration).mockResolvedValue(run('WAITING_APPROVAL', 'WAITING_APPROVAL'))
    const wrapper = mount(OrchestrationPanel, { props: { role: 'APPROVER' } })
    await flushPromises()

    expect(getOrchestration).toHaveBeenCalledWith('APPROVER', run().runId)
    expect(startOrchestration).not.toHaveBeenCalled()
    expect(wrapper.get('[data-run-status="WAITING_APPROVAL"]').text()).toBe('等待人工审批')
    wrapper.unmount()
  })

  it('does not expose a launch action to customer agent', () => {
    const wrapper = mount(OrchestrationPanel, { props: { role: 'CUSTOMER_AGENT' } })
    expect(wrapper.get('[data-start-orchestration]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('cancels through the backend and renders the returned terminal state', async () => {
    localStorage.setItem('smartpark.orchestration.last.OPERATOR', run().runId)
    vi.mocked(getOrchestration).mockResolvedValue({ ...run(), role: 'OPERATOR' })
    vi.mocked(cancelOrchestration).mockResolvedValue({ ...run('CANCELLED', 'CANCELLED'), role: 'OPERATOR' })
    const wrapper = mount(OrchestrationPanel, { props: { role: 'OPERATOR' } })
    await flushPromises()
    await wrapper.get('[data-cancel-orchestration]').trigger('click')
    await flushPromises()

    expect(cancelOrchestration).toHaveBeenCalledWith('OPERATOR', run().runId)
    expect(wrapper.get('[data-run-status="CANCELLED"]').text()).toBe('已取消')
    wrapper.unmount()
  })
})
