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

afterEach(() => {
  vi.clearAllMocks()
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

describe('CustomerServiceConsole', () => {
  it('keeps the customer question accessibly named after value entry', async () => {
    vi.mocked(listCustomerTickets).mockResolvedValue([])
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

  it('submits the server-owned question for a guided customer launch', async () => {
    vi.mocked(listCustomerTickets).mockResolvedValue([])
    vi.mocked(askCustomerService).mockResolvedValue({
      sessionId: 'CS-SESSION-001',
      intent: 'PARKING_POLICY',
      answer: '访客停车按园区规则执行。',
      knowledgeSources: ['KB-PARKING-001'],
      knowledgeCitations: [],
      needsHuman: false,
      reason: 'SUPPORTED',
      citationIds: ['KB-PARKING-001'],
      ticket: null,
    })
    vi.mocked(getCustomerConversation).mockResolvedValue({
      sessionId: 'CS-SESSION-001',
      messages: [],
      retrievals: [],
      humanHandoff: false,
    })

    const wrapper = mount(CustomerServiceConsole, {
      props: {
        role: 'VIEWER',
        active: true,
        launchRequest: {
          requestId: 7,
          mode: 'guided',
          scenarioId: 'CUSTOMER_SERVICE',
          view: 'customer',
          launchInput: { alertId: null, question: '访客停车怎么收费？' },
        },
      },
      global: {
        stubs: {
          'el-input': ElInput,
          'el-button': true,
          'el-tag': true,
          'el-empty': true,
        },
      },
    })

    await flushPromises()

    expect(askCustomerService).toHaveBeenCalledWith('访客停车怎么收费？', expect.any(String))
    expect(wrapper.find('.chat-message.user').text()).toContain('访客停车怎么收费？')
    expect(wrapper.find('.chat-message.assistant').text()).toContain('访客停车按园区规则执行。')
    wrapper.unmount()
  })

  it('starts a guided customer run in a fresh session after an existing conversation', async () => {
    vi.mocked(listCustomerTickets).mockResolvedValue([])
    vi.mocked(askCustomerService)
      .mockResolvedValueOnce({
        sessionId: 'CS-SESSION-OLD',
        intent: 'REPAIR',
        answer: '已转人工客服。',
        knowledgeSources: [],
        knowledgeCitations: [],
        needsHuman: true,
        reason: 'INSUFFICIENT_EVIDENCE',
        citationIds: [],
        ticket: null,
      })
      .mockResolvedValueOnce({
        sessionId: 'CS-SESSION-NEW',
        intent: 'PARKING_POLICY',
        answer: '访客停车按园区规则执行。',
        knowledgeSources: ['KB-PARKING-001'],
        knowledgeCitations: [],
        needsHuman: false,
        reason: 'SUPPORTED',
        citationIds: ['KB-PARKING-001'],
        ticket: null,
      })
    vi.mocked(replyCustomerSession).mockResolvedValue({
      sessionId: 'CS-SESSION-OLD',
      intent: 'REPAIR',
      answer: '旧会话回复',
      knowledgeSources: [],
      knowledgeCitations: [],
      needsHuman: true,
      reason: 'POLICY_LIMIT',
      citationIds: [],
      ticket: null,
    })
    vi.mocked(getCustomerConversation).mockResolvedValue({
      sessionId: 'CS-SESSION-OLD',
      messages: [],
      retrievals: [],
      humanHandoff: true,
    })

    const wrapper = mount(CustomerServiceConsole, {
      props: { role: 'VIEWER', active: true },
      global: {
        stubs: {
          'el-input': ElInput,
          'el-button': true,
          'el-tag': true,
          'el-empty': true,
        },
      },
    })

    await wrapper.get('.chat-composer input').setValue('A1 洗手间漏水，需要报修')
    await wrapper.get('.chat-composer').trigger('submit')
    await flushPromises()

    await wrapper.setProps({
      launchRequest: {
        requestId: 8,
        mode: 'guided',
        scenarioId: 'CUSTOMER_SERVICE',
        view: 'customer',
        launchInput: { alertId: null, question: '访客停车怎么收费？' },
      },
    })
    await flushPromises()

    expect(askCustomerService).toHaveBeenNthCalledWith(2, '访客停车怎么收费？', expect.any(String))
    expect(replyCustomerSession).not.toHaveBeenCalled()
    expect(wrapper.find('.chat-message.user').text()).toContain('访客停车怎么收费？')
    wrapper.unmount()
  })

  it('invalidates an in-flight manual request before starting a guided run', async () => {
    vi.mocked(listCustomerTickets).mockResolvedValue([])
    const oldRequest = deferred<CustomerServiceResponse>()
    vi.mocked(askCustomerService)
      .mockImplementationOnce(() => oldRequest.promise)
      .mockResolvedValueOnce({
        sessionId: 'CS-SESSION-NEW',
        intent: 'PARKING_POLICY',
        answer: '访客停车按园区规则执行。',
        knowledgeSources: ['KB-PARKING-001'],
        knowledgeCitations: [],
        needsHuman: false,
        reason: 'SUPPORTED',
        citationIds: ['KB-PARKING-001'],
        ticket: null,
      })
    vi.mocked(getCustomerConversation).mockResolvedValue({
      sessionId: 'CS-SESSION-NEW',
      messages: [],
      retrievals: [],
      humanHandoff: false,
    })

    const wrapper = mount(CustomerServiceConsole, {
      props: { role: 'VIEWER', active: true },
      global: {
        stubs: {
          'el-input': ElInput,
          'el-button': true,
          'el-tag': true,
          'el-empty': true,
        },
      },
    })

    await wrapper.get('.chat-composer input').setValue('旧的现场报修问题')
    await wrapper.get('.chat-composer').trigger('submit')
    expect(askCustomerService).toHaveBeenCalledTimes(1)

    await wrapper.setProps({
      launchRequest: {
        requestId: 9,
        mode: 'guided',
        scenarioId: 'CUSTOMER_SERVICE',
        view: 'customer',
        launchInput: { alertId: null, question: '访客停车怎么收费？' },
      },
    })
    await flushPromises()

    expect(askCustomerService).toHaveBeenNthCalledWith(2, '访客停车怎么收费？', expect.any(String))
    oldRequest.resolve({
      sessionId: 'CS-SESSION-OLD',
      intent: 'REPAIR',
      answer: '旧请求回答',
      knowledgeSources: [],
      knowledgeCitations: [],
      needsHuman: true,
      reason: 'INSUFFICIENT_EVIDENCE',
      citationIds: [],
      ticket: null,
    })
    await flushPromises()

    expect(wrapper.find('.chat-stream').text()).not.toContain('旧请求回答')
    expect(wrapper.find('.chat-stream').text()).toContain('访客停车按园区规则执行。')
    wrapper.unmount()
  })
})
