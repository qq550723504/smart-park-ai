import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElInput } from 'element-plus'
import CustomerServiceConsole from './CustomerServiceConsole.vue'
import { askCustomerService, getCustomerConversation, listCustomerTickets, replyCustomerSession } from '../services/workflowApi'
import type { CustomerServiceResponse } from '../types/workflow'

vi.mock('../services/workflowApi', async () => {
  const actual = await vi.importActual<typeof import('../services/workflowApi')>('../services/workflowApi')
  return {
    ...actual,
    askCustomerService: vi.fn(),
    getCustomerConversation: vi.fn(),
    listCustomerTickets: vi.fn(),
    replyCustomerSession: vi.fn(),
  }
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

describe('CustomerServiceConsole', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => {
    globalThis.fetch = originalFetch
    vi.clearAllMocks()
  })

  it('keeps the customer question accessibly named after value entry', async () => {
    const wrapper = mount(CustomerServiceConsole, {
      props: { role: 'VIEWER' },
      global: {
        stubs: {
          'el-input': ElInput,
          'el-button': true,
          'el-tag': true,
          'el-empty': true,
        },
      },
    })

    const questionInput = wrapper.get('.chat-composer input')
    await questionInput.setValue('A1 洗手间漏水，需要报修')

    expect(questionInput.attributes('aria-label')).toBe('园区服务问题')
    expect((questionInput.element as HTMLInputElement).value).toBe('A1 洗手间漏水，需要报修')
    wrapper.unmount()
  })

  it('runs the fixed parking question when launched from the showcase', async () => {
    vi.mocked(listCustomerTickets).mockResolvedValue([])
    vi.mocked(askCustomerService).mockResolvedValue({
      sessionId: 'cs-guided-1', intent: 'PARKING', answer: '访客停车按园区公示标准收费。',
      knowledgeSources: ['停车服务指南'], knowledgeCitations: [], needsHuman: false,
      reason: 'SUPPORTED', citationIds: ['KB-PARKING'], ticket: null,
    })
    vi.mocked(getCustomerConversation).mockResolvedValue({
      sessionId: 'cs-guided-1', messages: [], retrievals: [], humanHandoff: false,
    })

    const wrapper = mount(CustomerServiceConsole, {
      props: {
        role: 'VIEWER',
        active: true,
        launchRequest: {
          requestId: 101, mode: 'guided', scenarioId: 'CUSTOMER_SERVICE', view: 'customer',
          launchInput: { alertId: null, question: '访客停车怎么收费？' },
        },
      },
      global: { stubs: { 'el-input': ElInput, 'el-button': true, 'el-tag': true, 'el-empty': true } },
    })

    await flushPromises()
    expect(askCustomerService).toHaveBeenCalledWith('访客停车怎么收费？', expect.any(String))
    expect(wrapper.text()).toContain('访客停车按园区公示标准收费。')
    expect(wrapper.emitted('launch-status')?.at(-1)).toEqual([{
      requestId: 101, state: 'started', message: '园区客服已启动',
    }])
    wrapper.unmount()
  })

  it('starts a guided customer run in a fresh session after an existing conversation', async () => {
    vi.mocked(listCustomerTickets).mockResolvedValue([])
    vi.mocked(askCustomerService)
      .mockResolvedValueOnce({
        sessionId: 'CS-SESSION-OLD', intent: 'REPAIR', answer: '已转人工客服。', knowledgeSources: [],
        knowledgeCitations: [], needsHuman: true, reason: 'INSUFFICIENT_EVIDENCE', citationIds: [], ticket: null,
      })
      .mockResolvedValueOnce({
        sessionId: 'CS-SESSION-NEW', intent: 'PARKING_POLICY', answer: '访客停车按园区规则执行。',
        knowledgeSources: ['KB-PARKING-001'], knowledgeCitations: [], needsHuman: false,
        reason: 'SUPPORTED', citationIds: ['KB-PARKING-001'], ticket: null,
      })
    vi.mocked(replyCustomerSession).mockResolvedValue({
      sessionId: 'CS-SESSION-OLD', intent: 'REPAIR', answer: '旧会话回复', knowledgeSources: [],
      knowledgeCitations: [], needsHuman: true, reason: 'POLICY_LIMIT', citationIds: [], ticket: null,
    })
    vi.mocked(getCustomerConversation).mockResolvedValue({
      sessionId: 'CS-SESSION-OLD', messages: [], retrievals: [], humanHandoff: true,
    })

    const wrapper = mount(CustomerServiceConsole, {
      props: { role: 'VIEWER', active: true },
      global: { stubs: { 'el-input': ElInput, 'el-button': true, 'el-tag': true, 'el-empty': true } },
    })
    await wrapper.get('.chat-composer input').setValue('A1 洗手间漏水，需要报修')
    await wrapper.get('.chat-composer').trigger('submit')
    await flushPromises()
    await wrapper.setProps({
      launchRequest: {
        requestId: 8, mode: 'guided', scenarioId: 'CUSTOMER_SERVICE', view: 'customer',
        launchInput: { alertId: null, question: '访客停车怎么收费？' },
      },
    })
    await flushPromises()

    expect(askCustomerService).toHaveBeenNthCalledWith(2, '访客停车怎么收费？', expect.any(String))
    expect(replyCustomerSession).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not restore a stale manual response after a guided launch', async () => {
    vi.mocked(listCustomerTickets).mockResolvedValue([])
    const oldRequest = deferred<CustomerServiceResponse>()
    vi.mocked(askCustomerService)
      .mockImplementationOnce(() => oldRequest.promise)
      .mockResolvedValueOnce({
        sessionId: 'CS-SESSION-NEW', intent: 'PARKING_POLICY', answer: '访客停车按园区规则执行。',
        knowledgeSources: ['KB-PARKING-001'], knowledgeCitations: [], needsHuman: false,
        reason: 'SUPPORTED', citationIds: ['KB-PARKING-001'], ticket: null,
      })
    vi.mocked(getCustomerConversation).mockResolvedValue({
      sessionId: 'CS-SESSION-NEW', messages: [], retrievals: [], humanHandoff: false,
    })

    const wrapper = mount(CustomerServiceConsole, {
      props: { role: 'VIEWER', active: true },
      global: { stubs: { 'el-input': ElInput, 'el-button': true, 'el-tag': true, 'el-empty': true } },
    })
    await wrapper.get('.chat-composer input').setValue('旧的现场报修问题')
    await wrapper.get('.chat-composer').trigger('submit')
    await wrapper.setProps({
      launchRequest: {
        requestId: 9, mode: 'guided', scenarioId: 'CUSTOMER_SERVICE', view: 'customer',
        launchInput: { alertId: null, question: '访客停车怎么收费？' },
      },
    })
    await flushPromises()

    oldRequest.resolve({
      sessionId: 'CS-SESSION-OLD', intent: 'REPAIR', answer: '旧请求回答', knowledgeSources: [],
      knowledgeCitations: [], needsHuman: true, reason: 'INSUFFICIENT_EVIDENCE', citationIds: [], ticket: null,
    })
    await flushPromises()

    expect(wrapper.find('.chat-stream').text()).not.toContain('旧请求回答')
    expect(wrapper.find('.chat-stream').text()).toContain('访客停车按园区规则执行。')
    wrapper.unmount()
  })

  it('ignores a privileged ticket list response after the role changes', async () => {
    const adminTickets = deferred<CustomerServiceResponse[]>()
    vi.mocked(listCustomerTickets)
      .mockReturnValueOnce(adminTickets.promise)
      .mockResolvedValueOnce([])

    const wrapper = mount(CustomerServiceConsole, {
      props: { role: 'ADMIN', active: true },
      global: { stubs: { 'el-input': ElInput, 'el-button': true, 'el-tag': true, 'el-empty': true } },
    })
    await wrapper.setProps({ role: 'CUSTOMER_AGENT' })
    await flushPromises()
    adminTickets.resolve([{
      sessionId: 'CS-OLD', intent: 'REPAIR', answer: '旧工单', knowledgeSources: [], knowledgeCitations: [],
      needsHuman: true, reason: 'INSUFFICIENT_EVIDENCE', citationIds: [],
      ticket: { id: 'T-OLD', sessionId: 'CS-OLD', intent: 'REPAIR', status: 'WAITING_AGENT', safeSummary: '旧特权工单', createdAt: '2026-09-01T08:00:00Z' },
    }])
    await flushPromises()

    expect(wrapper.text()).not.toContain('旧特权工单')
    wrapper.unmount()
  })
})
