import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CustomerAssistantPanel from './CustomerAssistantPanel.vue'
import { askCustomerService, getCustomerConversation, replyCustomerSession, WorkflowApiError } from '../../services/workflowApi'
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

  it('retries an unconfirmed first request with the same question and idempotency key', async () => {
    vi.mocked(askCustomerService)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(answer)
    vi.mocked(getCustomerConversation).mockResolvedValue({ sessionId: 'CS-1', messages: [], retrievals: [], humanHandoff: false })
    const wrapper = mountPanel()

    await wrapper.get('textarea').setValue('访客停车怎么收费？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const firstAttempt = vi.mocked(askCustomerService).mock.calls[0]!
    expect(wrapper.get('[role="alert"]').text()).toContain('请求结果尚未确认')
    expect(wrapper.get('[data-unconfirmed-assistant-request]').text()).toContain('已保留原问题与请求身份')
    expect((wrapper.vm as unknown as { resetForDemo: () => boolean }).resetForDemo()).toBe(false)

    await wrapper.get('textarea').setValue('我改问访客预约')
    await wrapper.get('form').trigger('submit')
    expect(askCustomerService).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('不能把修改后的问题与原请求身份混用')

    await wrapper.get('[data-retry-assistant-request]').trigger('click')
    await flushPromises()
    expect(askCustomerService).toHaveBeenCalledTimes(2)
    expect(vi.mocked(askCustomerService).mock.calls[1]).toEqual(firstAttempt)
    expect(wrapper.findAll('.customer-assistant__messages article.user')).toHaveLength(1)
    expect(wrapper.find('[data-unconfirmed-assistant-request]').exists()).toBe(false)
  })

  it('retries an unconfirmed reply against the same session with the same identity', async () => {
    vi.mocked(askCustomerService).mockResolvedValue(answer)
    vi.mocked(replyCustomerSession)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce({ ...answer, answer: '补充停车说明。' })
    vi.mocked(getCustomerConversation).mockResolvedValue({ sessionId: 'CS-1', messages: [], retrievals: [], humanHandoff: false })
    const wrapper = mountPanel()

    await wrapper.get('textarea').setValue('访客停车怎么收费？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea').setValue('夜间停车规则是什么？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const firstReplyAttempt = vi.mocked(replyCustomerSession).mock.calls[0]!

    await wrapper.setProps({ open: false, activePage: 'reports', context: null })
    await wrapper.setProps({ open: true })
    await wrapper.get('[data-retry-assistant-request]').trigger('click')
    await flushPromises()

    expect(replyCustomerSession).toHaveBeenCalledTimes(2)
    expect(firstReplyAttempt[0]).toBe('CS-1')
    expect(vi.mocked(replyCustomerSession).mock.calls[1]).toEqual(firstReplyAttempt)
    expect(wrapper.findAll('.customer-assistant__messages article.user')).toHaveLength(2)
  })

  it('does not resend a confirmed answer when only conversation synchronization fails', async () => {
    vi.mocked(askCustomerService).mockResolvedValue(answer)
    vi.mocked(getCustomerConversation).mockRejectedValue(new Error('read failed'))
    const wrapper = mountPanel()

    await wrapper.get('textarea').setValue('访客停车怎么收费？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(answer.answer)
    expect(wrapper.text()).toContain('回答已收到，会话详情暂未同步')
    expect(askCustomerService).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-unconfirmed-assistant-request]').exists()).toBe(false)
  })

  it('locks the composer from the confirmed handoff receipt when conversation synchronization fails', async () => {
    vi.mocked(askCustomerService).mockResolvedValue({
      ...answer,
      answer: '已转人工处理。',
      needsHuman: true,
      reason: 'INSUFFICIENT_EVIDENCE',
    })
    vi.mocked(getCustomerConversation).mockRejectedValue(new Error('read failed'))
    const wrapper = mountPanel()

    await wrapper.get('textarea').setValue('需要人工协助')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('回答已收到，会话详情暂未同步')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).disabled).toBe(true)
    expect((wrapper.get('[data-send-assistant]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('textarea').attributes('placeholder')).toBe('当前会话已转人工，请等待处理')
    expect(askCustomerService).toHaveBeenCalledTimes(1)
  })

  it('releases the composer after a confirmed answer while conversation synchronization is still pending', async () => {
    let resolveConversation: ((value: { sessionId: string; messages: never[]; retrievals: never[]; humanHandoff: boolean }) => void) | undefined
    vi.mocked(askCustomerService).mockResolvedValue(answer)
    vi.mocked(getCustomerConversation).mockReturnValue(new Promise((resolve) => { resolveConversation = resolve }))
    const wrapper = mountPanel()

    await wrapper.get('textarea').setValue('访客停车怎么收费？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(answer.answer)
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).disabled).toBe(false)
    expect((wrapper.vm as unknown as { canResetForDemo: () => boolean }).canResetForDemo()).toBe(true)

    resolveConversation?.({ sessionId: 'CS-1', messages: [], retrievals: [], humanHandoff: false })
    await flushPromises()
  })

  it('starts a new conversation after a reply is confirmed missing', async () => {
    vi.mocked(askCustomerService)
      .mockResolvedValueOnce(answer)
      .mockResolvedValueOnce({ ...answer, sessionId: 'CS-2', answer: '已创建新会话。' })
    vi.mocked(replyCustomerSession).mockRejectedValue(new WorkflowApiError('missing', 404))
    vi.mocked(getCustomerConversation).mockResolvedValue({ sessionId: 'CS-1', messages: [], retrievals: [], humanHandoff: false })
    const wrapper = mountPanel()

    await wrapper.get('textarea').setValue('访客停车怎么收费？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea').setValue('继续说明停车规则')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('原会话已失效')
    expect(wrapper.get('[role="alert"]').text()).toContain('旧会话记录已清除')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('继续说明停车规则')
    expect(wrapper.findAll('.customer-assistant__messages article')).toHaveLength(0)

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(replyCustomerSession).toHaveBeenCalledTimes(1)
    expect(askCustomerService).toHaveBeenCalledTimes(2)
    expect(vi.mocked(askCustomerService).mock.calls[1]?.[0]).toBe('继续说明停车规则')
    expect(wrapper.findAll('.customer-assistant__messages article.user')).toHaveLength(1)
    expect(wrapper.findAll('.customer-assistant__messages article.assistant')).toHaveLength(1)
    expect(wrapper.text()).not.toContain(answer.answer)
    expect(wrapper.text()).toContain('已创建新会话。')
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

  it('labels a confirmed rejection and does not fabricate a ticket', async () => {
    vi.mocked(askCustomerService).mockRejectedValue(new WorkflowApiError('invalid request', 400))
    const wrapper = mountPanel()
    await wrapper.get('textarea').setValue('洗手间漏水，需要报修')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('报修请求已确认未受理')
    expect(wrapper.get('[role="alert"]').text()).not.toContain('invalid request')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('洗手间漏水，需要报修')
    expect(wrapper.find('[data-assistant-ticket]').exists()).toBe(false)
    expect(wrapper.find('[data-unconfirmed-assistant-request]').exists()).toBe(false)
  })
})
