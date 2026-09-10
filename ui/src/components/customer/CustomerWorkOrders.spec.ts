import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CustomerWorkOrders from './CustomerWorkOrders.vue'
import { WorkflowApiError } from '../../services/workflowApi'

const mocks = vi.hoisted(() => ({
  getAnomalyEvidence: vi.fn(),
  getActionableAlert: vi.fn(),
  listCollaborationWorkItems: vi.fn(),
  startWorkflow: vi.fn(),
  getWorkflow: vi.fn(),
  getWorkflowEventHistory: vi.fn(),
  submitApproval: vi.fn(),
  subscribeToWorkflow: vi.fn(() => ({ close: vi.fn() })),
}))

vi.mock('../../services/operationsAnomalyApi', () => ({ getAnomalyEvidence: mocks.getAnomalyEvidence }))
vi.mock('../../services/workflowApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../services/workflowApi')>()),
  getActionableAlert: mocks.getActionableAlert,
  listCollaborationWorkItems: mocks.listCollaborationWorkItems,
  startWorkflow: mocks.startWorkflow,
  getWorkflow: mocks.getWorkflow,
  getWorkflowEventHistory: mocks.getWorkflowEventHistory,
  submitApproval: mocks.submitApproval,
  subscribeToWorkflow: mocks.subscribeToWorkflow,
}))

const context = {
  buildingId: 'B1', buildingName: '创新中心', anomalyId: 'ALT-ORCH-ENERGY-B1-001', title: '创新中心能耗偏离基线', priority: '高',
  summary: { buildingId: 'B1', alertCount: 2, highRiskAlertCount: 2, offlineDeviceCount: 1, energyDeviationPct: 12 },
  overviewDomainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
  anomalyWindow: { from: '2026-09-01T00:00:00Z', to: '2026-09-09T00:37:00Z', timezone: 'Asia/Shanghai' },
  energyWindow: { from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai', granularity: 'HOUR' },
  source: 'OPERATIONS_ANALYTICS',
} as const

const evidence = {
  buildingId: 'B1', window: context.anomalyWindow, asOf: '2026-09-09T00:37:00Z',
  alerts: [{ alertId: context.anomalyId, buildingId: 'B1', deviceId: 'DEV-ENERGY-B1-001', category: 'ENERGY', riskLevel: 'HIGH', status: 'OPEN', occurredAt: '2026-09-08T02:12:00Z' }],
  devices: [], energy: [], domainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
}

const waiting = {
  workflowId: 'wf-71', alertId: context.anomalyId, status: 'WAITING_APPROVAL', diagnosis: null, approval: null, workOrder: null,
  errors: [], eventSequence: 7, riskReasons: ['原始告警风险为 HIGH'],
} as const
const completed = {
  ...waiting, status: 'COMPLETED', approval: { decision: 'APPROVED', reviewer: 'withheld', comment: 'recorded', decidedAt: '2026-09-10T07:00:00Z' },
  workOrder: { id: 'WO-0001', workflowId: 'wf-71', parkId: 'PARK-A', buildingId: 'B1', deviceId: 'DEV-ENERGY-B1-001', alertId: context.anomalyId,
    summary: 'withheld', riskLevel: 'HIGH', status: 'PENDING_EXECUTION', approval: null, evidence: [], createdAt: '2026-09-10T07:00:01Z', updatedAt: '2026-09-10T07:00:01Z' },
} as const

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getAnomalyEvidence.mockResolvedValue(evidence)
  mocks.getActionableAlert.mockResolvedValue({ alertId: context.anomalyId, parkId: 'PARK-A', buildingId: 'B1', deviceId: 'DEV-ENERGY-B1-001', category: 'ENERGY', riskLevel: 'HIGH', occurredAt: '2026-08-23T00:27:00Z' })
  mocks.listCollaborationWorkItems.mockResolvedValue([])
  mocks.startWorkflow.mockResolvedValue(waiting)
  mocks.submitApproval.mockResolvedValue(completed)
  mocks.getWorkflow.mockResolvedValue(completed)
})

describe('CustomerWorkOrders', () => {
  it('verifies the exact cross-source identity and creates one real pending work order after confirmation', async () => {
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()

    expect(wrapper.get('[data-actionability]').text()).toContain('楼宇、设备与类别一致')
    await wrapper.get('[data-confirm-work-order]').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain(context.anomalyId)
    await wrapper.get('[data-submit-work-order]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(mocks.startWorkflow).toHaveBeenCalledTimes(1)
    expect(mocks.startWorkflow).toHaveBeenCalledWith(context.anomalyId)
    expect(mocks.submitApproval).toHaveBeenCalledTimes(1)
    expect(mocks.submitApproval.mock.calls[0][2]).toBe('APPROVER')
    expect(wrapper.get('[data-work-order-id]').text()).toBe('WO-0001')
    expect(wrapper.get('[data-work-order-status]').text()).toBe('已创建，待处理')
    expect(wrapper.get('[data-work-order-outcome]').text()).toContain('不表示问题已解决')
  })

  it('blocks a mismatched actionable alert instead of substituting another alert', async () => {
    mocks.getActionableAlert.mockResolvedValue({ alertId: context.anomalyId, parkId: 'PARK-A', buildingId: 'A1', deviceId: 'DEV-ENERGY-B1-001', category: 'ENERGY', riskLevel: 'HIGH', occurredAt: '2026-08-23T00:27:00Z' })
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()

    expect(wrapper.get('[data-actionability]').text()).toContain('不会替换成另一条告警')
    expect(wrapper.get('[data-confirm-work-order]').attributes('disabled')).toBeDefined()
    expect(mocks.startWorkflow).not.toHaveBeenCalled()
  })

  it('cancels the confirmation without starting a workflow', async () => {
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true, demoRole: 'APPROVER' } })
    await flushPromises()
    await wrapper.get('[data-confirm-work-order]').trigger('click')
    await wrapper.get('[role="dialog"] footer button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(mocks.startWorkflow).not.toHaveBeenCalled()
    expect(wrapper.get('[data-work-order-outcome]').text()).toContain('没有创建工单')
  })

  it('keeps the workflow waiting when the current demo role has no approval permission', async () => {
    mocks.submitApproval.mockRejectedValue(new WorkflowApiError('Operation is not allowed for the current demo role', 403))
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true, demoRole: 'VIEWER' } })
    await flushPromises()
    await wrapper.get('[data-confirm-work-order]').trigger('click')
    await wrapper.get('[data-submit-work-order]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('not allowed')
    expect(wrapper.get('[data-work-order-id]').text()).toBe('尚未创建')
  })

  it('ignores stale identity responses after switching to another event', async () => {
    let resolveOldEvidence!: (value: typeof evidence) => void
    let resolveOldIdentity!: (value: Awaited<ReturnType<typeof mocks.getActionableAlert>>) => void
    const oldEvidence = new Promise<typeof evidence>((resolve) => { resolveOldEvidence = resolve })
    const oldIdentity = new Promise<Awaited<ReturnType<typeof mocks.getActionableAlert>>>((resolve) => { resolveOldIdentity = resolve })
    const nextContext = {
      ...context,
      buildingId: 'B2',
      buildingName: '研发大厦',
      anomalyId: 'ALT-ORCH-ENERGY-B2-001',
      title: '研发大厦能耗偏离基线',
      summary: { ...context.summary, buildingId: 'B2' },
    }
    const nextEvidence = {
      ...evidence,
      buildingId: 'B2',
      alerts: [{ ...evidence.alerts[0], alertId: nextContext.anomalyId, buildingId: 'B2', deviceId: 'DEV-ENERGY-B2-001' }],
    }
    const nextIdentity = {
      alertId: nextContext.anomalyId,
      parkId: 'PARK-A',
      buildingId: 'B2',
      deviceId: 'DEV-ENERGY-B2-001',
      category: 'ENERGY',
      riskLevel: 'HIGH',
      occurredAt: '2026-08-23T00:27:00Z',
    }
    mocks.getAnomalyEvidence.mockReturnValueOnce(oldEvidence).mockResolvedValueOnce(nextEvidence)
    mocks.getActionableAlert.mockReturnValueOnce(oldIdentity).mockResolvedValueOnce(nextIdentity)

    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()
    await wrapper.setProps({ context: nextContext })
    await flushPromises()

    expect(wrapper.get('[data-actionability]').text()).toContain(nextContext.anomalyId)
    resolveOldEvidence(evidence)
    resolveOldIdentity({
      alertId: context.anomalyId,
      parkId: 'PARK-A',
      buildingId: 'B1',
      deviceId: 'DEV-ENERGY-B1-001',
      category: 'ENERGY',
      riskLevel: 'HIGH',
      occurredAt: '2026-08-23T00:27:00Z',
    })
    await flushPromises()

    expect(wrapper.get('[data-actionability]').text()).toContain(nextContext.anomalyId)
    expect(wrapper.get('[data-actionability]').text()).not.toContain(context.anomalyId)
  })

  it('keeps the actual receipt through refresh, leave, and return without restarting or reapproving', async () => {
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()
    await wrapper.get('[data-confirm-work-order]').trigger('click')
    await wrapper.get('[data-submit-work-order]').trigger('click')
    await flushPromises()
    await flushPromises()
    const statusReadsBeforeRefresh = mocks.getWorkflow.mock.calls.length
    mocks.getWorkflow.mockResolvedValueOnce({
      ...completed,
      workOrder: { ...completed.workOrder, status: 'IN_PROGRESS' },
    })

    await wrapper.get('[data-refresh-work-orders]').trigger('click')
    await flushPromises()

    expect(mocks.getWorkflow).toHaveBeenCalledTimes(statusReadsBeforeRefresh + 1)
    expect(mocks.getWorkflow).toHaveBeenLastCalledWith('wf-71')
    expect(mocks.startWorkflow).toHaveBeenCalledTimes(1)
    expect(mocks.submitApproval).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-work-order-id]').text()).toBe('WO-0001')
    expect(wrapper.get('[data-work-order-status]').text()).toBe('处理中')

    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(mocks.getWorkflow).toHaveBeenCalledTimes(statusReadsBeforeRefresh + 1)
    expect(mocks.startWorkflow).toHaveBeenCalledTimes(1)
    expect(mocks.submitApproval).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-work-order-id]').text()).toBe('WO-0001')
    expect(wrapper.get('[data-work-order-status]').text()).toBe('处理中')
  })

  it('retains the known receipt and marks the latest status unknown when its refresh fails', async () => {
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()
    await wrapper.get('[data-confirm-work-order]').trigger('click')
    await wrapper.get('[data-submit-work-order]').trigger('click')
    await flushPromises()
    await flushPromises()
    mocks.getWorkflow.mockRejectedValueOnce(new Error('temporary offline'))

    await wrapper.get('[data-refresh-work-orders]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-work-order-id]').text()).toBe('WO-0001')
    expect(wrapper.get('[data-work-order-status]').text()).toBe('已创建，待处理')
    expect(wrapper.get('[data-work-order-outcome]').text()).toContain('最新状态暂未确认')
    expect(wrapper.get('[role="alert"]').text()).toContain('最新工作流状态暂未确认')
    expect(wrapper.get('[data-confirm-work-order]').attributes('disabled')).toBeDefined()
    expect(mocks.startWorkflow).toHaveBeenCalledTimes(1)
    expect(mocks.submitApproval).toHaveBeenCalledTimes(1)
  })

  it('restarts an interrupted initial evidence load when returning to the same event', async () => {
    const staleEvidence = {
      ...evidence,
      alerts: evidence.alerts.map((item) => ({ ...item, alertId: 'ALT-STALE-001' })),
    }
    const oldEvidence = deferred<typeof staleEvidence>()
    const oldIdentity = deferred<Awaited<ReturnType<typeof mocks.getActionableAlert>>>()
    mocks.getAnomalyEvidence.mockReset().mockReturnValueOnce(oldEvidence.promise).mockResolvedValue(evidence)
    mocks.getActionableAlert.mockReset().mockReturnValueOnce(oldIdentity.promise).mockResolvedValue({
      alertId: context.anomalyId,
      parkId: 'PARK-A',
      buildingId: 'B1',
      deviceId: 'DEV-ENERGY-B1-001',
      category: 'ENERGY',
      riskLevel: 'HIGH',
      occurredAt: '2026-08-23T00:27:00Z',
    })
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()

    await wrapper.setProps({ active: false })
    oldEvidence.resolve(staleEvidence)
    oldIdentity.resolve({
      alertId: 'ALT-STALE-001',
      parkId: 'PARK-A',
      buildingId: 'B1',
      deviceId: 'DEV-ENERGY-B1-001',
      category: 'ENERGY',
      riskLevel: 'HIGH',
      occurredAt: '2026-08-23T00:27:00Z',
    })
    await flushPromises()
    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(mocks.getAnomalyEvidence).toHaveBeenCalledTimes(2)
    expect(mocks.getActionableAlert).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-actionability]').text()).toContain(context.anomalyId)
    expect(wrapper.get('[data-actionability]').text()).not.toContain('ALT-STALE-001')
    expect(wrapper.get('[data-confirm-work-order]').attributes('disabled')).toBeUndefined()
  })

  it('disables page refresh while approval is in flight and keeps the same workflow association', async () => {
    const approval = deferred<typeof completed>()
    mocks.submitApproval.mockReturnValueOnce(approval.promise)
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()
    await wrapper.get('[data-confirm-work-order]').trigger('click')
    await wrapper.get('[data-submit-work-order]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-refresh-work-orders]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-refresh-work-orders]').trigger('click')
    expect(mocks.startWorkflow).toHaveBeenCalledTimes(1)
    expect(mocks.submitApproval).toHaveBeenCalledTimes(1)
    expect(mocks.getWorkflow).not.toHaveBeenCalled()

    approval.resolve(completed)
    await flushPromises()
    await flushPromises()

    expect(wrapper.get('[data-work-order-id]').text()).toBe('WO-0001')
    expect(wrapper.get('[data-work-order-status]').text()).toBe('已创建，待处理')
    expect(mocks.startWorkflow).toHaveBeenCalledTimes(1)
    expect(mocks.submitApproval).toHaveBeenCalledTimes(1)
  })

  it('disables page refresh while workflow start is in flight', async () => {
    const starting = deferred<typeof waiting>()
    mocks.startWorkflow.mockReturnValueOnce(starting.promise)
    const wrapper = mount(CustomerWorkOrders, { props: { context, active: true } })
    await flushPromises()
    await wrapper.get('[data-confirm-work-order]').trigger('click')
    await wrapper.get('[data-submit-work-order]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-refresh-work-orders]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-refresh-work-orders]').trigger('click')
    expect(mocks.startWorkflow).toHaveBeenCalledTimes(1)
    expect(mocks.submitApproval).not.toHaveBeenCalled()
    expect(mocks.getWorkflow).not.toHaveBeenCalled()

    starting.resolve(waiting)
    await flushPromises()
    await flushPromises()

    expect(mocks.submitApproval).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-work-order-id]').text()).toBe('WO-0001')
  })
})
