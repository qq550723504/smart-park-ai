import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElInput } from 'element-plus'
import CustomerServiceConsole from './CustomerServiceConsole.vue'
import { askCustomerService, getCustomerConversation, listCustomerTickets } from '../services/workflowApi'

vi.mock('../services/workflowApi', async () => {
  const actual = await vi.importActual<typeof import('../services/workflowApi')>('../services/workflowApi')
  return {
    ...actual,
    askCustomerService: vi.fn(),
    getCustomerConversation: vi.fn(),
    listCustomerTickets: vi.fn(),
  }
})

afterEach(() => {
  vi.clearAllMocks()
})

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
})
