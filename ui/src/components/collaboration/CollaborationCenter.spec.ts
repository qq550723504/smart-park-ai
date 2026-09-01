import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import CollaborationCenter from './CollaborationCenter.vue'

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('CollaborationCenter', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => { globalThis.fetch = originalFetch })

  it('renders safe work items and opens their existing scene', async () => {
    globalThis.fetch = (async () => response([{
      id: 'ALERT_WORKFLOW:wf-1', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
      title: '告警处置 ALT-POWER-001', safeSummary: '告警 ALT-POWER-001 · A2 · DEV-POWER-001',
      parkId: 'PARK-A', buildingId: 'A2', deviceId: 'DEV-POWER-001',
      updatedAt: '2026-09-01T08:00:00Z', detailPath: 'workflow',
    }, {
      id: 'CUSTOMER_TICKET:cs-1', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
      title: '客服工单 cs-1', safeSummary: 'A1 洗手间漏水，等待客服接入。',
      parkId: null, buildingId: null, deviceId: null,
      updatedAt: '2026-09-01T07:00:00Z', detailPath: 'customer',
    }])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.text()).toContain('ALERT_WORKFLOW:wf-1')
    expect(wrapper.text()).toContain('客服工单 cs-1')
    await wrapper.get('[data-work-item="CUSTOMER_TICKET:cs-1"] button').trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([['customer']])
    await wrapper.get('[data-work-item="ALERT_WORKFLOW:wf-1"] button').trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([['customer'], ['workflow', 'wf-1']])
  })

  it('ignores a stale queue response after the filters change', async () => {
    let resolveFirst!: (value: Response) => void
    let resolveSecond!: (value: Response) => void
    const first = new Promise<Response>((resolve) => { resolveFirst = resolve })
    const second = new Promise<Response>((resolve) => { resolveSecond = resolve })
    let calls = 0
    globalThis.fetch = (async () => ++calls === 1 ? first : second) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await wrapper.get('select').setValue('CUSTOMER_TICKET')
    resolveSecond(response([{
      id: 'CUSTOMER_TICKET:latest', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
      title: '最新工单', safeSummary: '最新结果', parkId: null, buildingId: null, deviceId: null,
      updatedAt: '2026-09-01T09:00:00Z', detailPath: 'customer',
    }]))
    await flushPromises()
    resolveFirst(response([{
      id: 'ALERT_WORKFLOW:stale', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
      title: '旧告警', safeSummary: '旧结果', parkId: null, buildingId: null, deviceId: null,
      updatedAt: '2026-09-01T08:00:00Z', detailPath: 'workflow',
    }]))
    await flushPromises()

    expect(wrapper.text()).toContain('最新工单')
    expect(wrapper.text()).not.toContain('旧告警')
  })

  it('fails closed with an explicit empty state when the read API is unavailable', async () => {
    globalThis.fetch = (async () => { throw new Error('offline') }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.text()).toContain('当前无法读取协同队列')
    expect(wrapper.text()).not.toContain('ALERT_WORKFLOW:wf-1')
  })
})
