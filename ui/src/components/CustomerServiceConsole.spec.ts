import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { ElInput } from 'element-plus'
import CustomerServiceConsole from './CustomerServiceConsole.vue'

describe('CustomerServiceConsole', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => {
    globalThis.fetch = originalFetch
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
    const calls: string[] = []
    globalThis.fetch = (async (url: RequestInfo | URL, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${String(url)}`)
      if (init?.method === 'POST') {
        return new Response(JSON.stringify({
          sessionId: 'cs-guided-1', intent: 'PARKING', answer: '访客停车按园区公示标准收费。',
          knowledgeSources: ['停车服务指南'], knowledgeCitations: [], needsHuman: false,
          reason: 'SUPPORTED', citationIds: ['KB-PARKING'], ticket: null,
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({
        sessionId: 'cs-guided-1', messages: [], retrievals: [], humanHandoff: false,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch

    const wrapper = mount(CustomerServiceConsole, {
      props: {
        role: 'VIEWER',
        active: true,
        launchRequest: {
          requestId: 101, mode: 'guided', scenarioId: 'CUSTOMER_SERVICE', view: 'customer',
          launchInput: { alertId: null, question: '访客停车怎么收费？' },
        },
      },
      global: { stubs: { 'el-input': true, 'el-button': true, 'el-tag': true, 'el-empty': true } },
    })

    await flushPromises()
    expect(calls.some((call) => call.startsWith('POST /api/customer-service/sessions'))).toBe(true)
    expect(wrapper.text()).toContain('访客停车按园区公示标准收费。')
    expect(wrapper.emitted('launch-status')?.at(-1)).toEqual([{
      requestId: 101, state: 'ready', message: '园区客服已完成停车问题演示',
    }])
    wrapper.unmount()
  })
})
