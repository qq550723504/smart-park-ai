import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CustomerAssistantPanel from './CustomerAssistantPanel.vue'
import { askCustomerService, getCustomerConversation, replyCustomerSession } from '../../services/workflowApi'
import type { CustomerServiceResponse } from '../../types/workflow'

vi.mock('../../services/workflowApi', async () => {
  const actual = await vi.importActual<typeof import('../../services/workflowApi')>('../../services/workflowApi')
  return {
    ...actual,
    askCustomerService: vi.fn(),
    getCustomerConversation: vi.fn(),
    replyCustomerSession: vi.fn(),
  }
})

const context = {
  buildingId: 'B2', buildingName: '研发大厦', anomalyId: 'ALT-B2', title: '研发大厦能耗偏离基线', priority: '中',
  summary: { buildingId: 'B2', alertCount: 1, highRiskAlertCount: 0, offlineDeviceCount: 0, energyDeviationPct: 12 },
  overviewDomainStatus: { alerts: 'OK', devices: 'OK', energy: 'OK' },
  anomalyWindow: { from: '2026-09-01T00:00:00Z', to: '2026-09-09T00:37:00Z', timezone: 'Asia/Shanghai' },
  energyWindow: { from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z', timezone: 'Asia/Shanghai', granularity: 'HOUR' },
  source: 'OPERATIONS_ANALYTICS',
} as const

const answer: CustomerServiceResponse = {
  sessionId: 'CS-1', intent: 'PARKING', answer: '访客停车按园区公示规则收费。',
  knowledgeSources: ['停车服务指南'],
  knowledgeCitations: [{ documentId: 'KB-PARKING', title: 'Visitor parking guide', score: 0.92 }],
  needsHuman: false, reason: 'SUPPORTED', citationIds: ['KB-PARKING'], ticket: null,
}

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(CustomerAssistantPanel, {
    props: { open: true, activePage: 'overview', context, answerMode: 'dashscope', knowledgeMode: 'rag', ...props },
  })
}

describe('CustomerAssistantPanel', () => {
  afterEach(() => vi.clearAllMocks())

  it('opens with page context but never sends until the user confirms', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('研发大厦')
    expect(wrapper.text()).toContain('只有点击发送后才调用')
    expect(askCustomerService).not.toHaveBeenCalled()

    await wrapper.get('[data-use-assistant-context]').trigger('click')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('研发大厦（B2）')
    expect(askCustomerService).not.toHaveBeenCalled()

    await wrapper.get('[data-close-assistant]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('keeps one customer session, safe citations and its draft across close and page changes', async () => {
    vi.mocked(askCustomerService).mockResolvedValue(answer)
    vi.mocked(replyCustomerSession).mockResolvedValue({ ...answer, answer: '补充停车说明。' })
    vi.mocked(getCustomerConversation).mockResolvedValue({ sessionId: 'CS-1', messages: [], retrievals: [], humanHandoff: false })
    const wrapper = mountPanel()

    await wrapper.get('textarea').setValue('访客停车怎么收费？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(askCustomerService).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('访客停车指南')
    expect(wrapper.text()).not.toContain('KB-PARKING')

    await wrapper.get('textarea').setValue('我还想了解夜间停车。')
    await wrapper.setProps({ open: false, activePage: 'reports', context: null })
    await wrapper.setProps({ open: true })
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('我还想了解夜间停车。')
    expect(wrapper.text()).toContain('访客停车按园区公示规则收费。')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(replyCustomerSession).toHaveBeenCalledWith('CS-1', '我还想了解夜间停车。', expect.any(String))
    expect(askCustomerService).toHaveBeenCalledTimes(1)
  })

  it('suppresses duplicate submission while a request is pending', async () => {
    let resolve!: (value: CustomerServiceResponse) => void
    vi.mocked(askCustomerService).mockReturnValue(new Promise((done) => { resolve = done }))
    vi.mocked(getCustomerConversation).mockResolvedValue({ sessionId: 'CS-1', messages: [], retrievals: [], humanHandoff: false })
    const wrapper = mountPanel()
    await wrapper.get('textarea').setValue('访客停车怎么收费？')

    await Promise.all([wrapper.get('form').trigger('submit'), wrapper.get('form').trigger('submit')])
    expect(askCustomerService).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-send-assistant]').attributes('disabled')).toBeDefined()

    resolve(answer)
    await flushPromises()
    expect(wrapper.text()).toContain(answer.answer)
  })

  it('shows a real repair receipt without claiming resolution', async () => {
    vi.mocked(askCustomerService).mockResolvedValue({
      ...answer,
      intent: 'REPAIR', answer: '已提交人工报修。', needsHuman: true, reason: 'INSUFFICIENT_EVIDENCE',
      ticket: { id: 'T-100', sessionId: 'CS-1', intent: 'REPAIR', status: 'WAITING_AGENT', safeSummary: '洗手间漏水', createdAt: '2026-09-10T00:00:00Z' },
    })
    vi.mocked(getCustomerConversation).mockResolvedValue({ sessionId: 'CS-1', messages: [], retrievals: [], humanHandoff: true })
    const wrapper = mountPanel()
    await wrapper.get('textarea').setValue('洗手间漏水，需要报修')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-assistant-ticket]').text()).toContain('T-100')
    expect(wrapper.get('[data-assistant-ticket]').text()).toContain('等待客服接入')
    expect(wrapper.get('[data-assistant-ticket]').text()).toContain('不表示问题已经解决')
  })

  it('retains a failed repair question and does not fabricate a ticket', async () => {
    vi.mocked(askCustomerService).mockRejectedValue(new Error('internal provider trace'))
    const wrapper = mountPanel()
    await wrapper.get('textarea').setValue('洗手间漏水，需要报修')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('报修请求未完成')
    expect(wrapper.get('[role="alert"]').text()).not.toContain('internal provider trace')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('洗手间漏水，需要报修')
    expect(wrapper.find('[data-assistant-ticket]').exists()).toBe(false)
  })
})
