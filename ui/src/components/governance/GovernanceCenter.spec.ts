import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import GovernanceCenter from './GovernanceCenter.vue'
import { getAuditEntries, getGovernanceOverview } from '../../services/workflowApi'
import type { GovernanceOverview } from '../../services/workflowApi'

vi.mock('../../services/workflowApi', async () => {
  const actual = await vi.importActual<typeof import('../../services/workflowApi')>('../../services/workflowApi')
  return { ...actual, getAuditEntries: vi.fn(), getGovernanceOverview: vi.fn() }
})

const overview: GovernanceOverview = {
  capturedAt: '2026-09-01T08:00:00Z',
  scenarios: { total: 5, ready: 3, notReady: 1, disabled: 1 },
  capabilities: { knowledgeMode: 'rag', customerAnswerMode: 'dashscope', vectorStore: 'simple-vector-store', analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: false },
  business: { workflowCount: 4, completedWorkflowCount: 3, customerSessionCount: 5, humanTicketCount: 1 },
  governance: { auditEntryCount: 7, feedbackCount: 4, positiveFeedbackCount: 3, knowledgeDocumentCount: 6, activeKnowledgeDocumentCount: 5, completionRate: 0.75, positiveFeedbackRate: 0.75 },
  boundaries: ['演示角色，不是生产认证'],
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

beforeEach(() => {
  vi.mocked(getGovernanceOverview).mockResolvedValue(overview)
  vi.mocked(getAuditEntries).mockResolvedValue([])
})

describe('GovernanceCenter', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => { globalThis.fetch = originalFetch })

  it('renders safe aggregated counters and capability modes', async () => {
    const wrapper = mount(GovernanceCenter, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(wrapper.text()).toContain('3/5')
    expect(wrapper.text()).toContain('RAG')
    expect(wrapper.text()).toContain('DashScope')
    expect(wrapper.text()).toContain('75%')
    expect(wrapper.text()).toContain('演示角色，不是生产认证')
  })

  it('fails closed without static business numbers', async () => {
    vi.mocked(getGovernanceOverview).mockRejectedValue(new Error('offline'))

    const wrapper = mount(GovernanceCenter)
    await flushPromises()

    expect(wrapper.text()).toContain('当前无法读取治理概览')
    expect(wrapper.text()).not.toContain('3/5')
  })

  it('loads audit details only for administrators without replacing the overview', async () => {
    vi.mocked(getAuditEntries).mockResolvedValue([{
      actorRole: 'ADMIN', action: 'APPROVE_WORKFLOW', resourceId: 'wf-1', outcome: 'SUCCESS', timestamp: '2026-09-01T08:01:00Z',
    }])
    const wrapper = mount(GovernanceCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(getAuditEntries).toHaveBeenCalledWith('ADMIN')
    expect(wrapper.text()).toContain('APPROVE_WORKFLOW')
    expect(wrapper.text()).toContain('3/5')
  })

  it('ignores an older overview response after a newer activation load', async () => {
    const first = deferred<typeof overview>()
    const secondOverview = { ...overview, scenarios: { ...overview.scenarios, ready: 4 } }
    const second = deferred<typeof overview>()
    vi.mocked(getGovernanceOverview)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)

    const wrapper = mount(GovernanceCenter, { props: { active: true, role: 'VIEWER' } })
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    second.resolve(secondOverview)
    await flushPromises()
    first.resolve(overview)
    await flushPromises()

    expect(wrapper.text()).toContain('4/5')
    expect(wrapper.text()).not.toContain('3/5')
  })
})
