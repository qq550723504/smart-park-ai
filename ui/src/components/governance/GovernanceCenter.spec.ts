import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import GovernanceCenter from './GovernanceCenter.vue'

describe('GovernanceCenter', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => { globalThis.fetch = originalFetch })

  it('renders safe aggregated counters and capability modes', async () => {
    globalThis.fetch = (async () => new Response(JSON.stringify({
      capturedAt: '2026-09-01T08:00:00Z',
      scenarios: { total: 5, ready: 3, notReady: 1, disabled: 1 },
      capabilities: {
        knowledgeMode: 'rag', customerAnswerMode: 'dashscope', vectorStore: 'simple-vector-store',
        analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: false,
      },
      business: { workflowCount: 4, completedWorkflowCount: 3, customerSessionCount: 5, humanTicketCount: 1 },
      governance: {
        auditEntryCount: 7, feedbackCount: 4, positiveFeedbackCount: 3,
        knowledgeDocumentCount: 6, activeKnowledgeDocumentCount: 5,
        completionRate: 0.75, positiveFeedbackRate: 0.75,
      },
      boundaries: ['演示角色，不是生产认证'],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch

    const wrapper = mount(GovernanceCenter)
    await flushPromises()

    expect(wrapper.text()).toContain('3/5')
    expect(wrapper.text()).toContain('RAG')
    expect(wrapper.text()).toContain('DashScope')
    expect(wrapper.text()).toContain('75%')
    expect(wrapper.text()).toContain('演示角色，不是生产认证')
  })

  it('fails closed without static business numbers', async () => {
    globalThis.fetch = (async () => { throw new Error('offline') }) as typeof fetch

    const wrapper = mount(GovernanceCenter)
    await flushPromises()

    expect(wrapper.text()).toContain('当前无法读取治理概览')
    expect(wrapper.text()).not.toContain('3/5')
  })
})
