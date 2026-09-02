import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import SecurityIncidentCenter from './SecurityIncidentCenter.vue'

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const summary = {
  incidentId: 'INC-1', parkId: 'PARK-A', buildingId: 'A1', eventType: 'UNAUTHORIZED_ACCESS_ATTEMPT',
  riskLevel: 'HIGH', status: 'OPEN', openedAt: '2026-09-02T08:00:00Z', lastOccurredAt: '2026-09-02T08:09:00Z',
  eventCount: 2, alertCount: 1, summary: 'REDACTED:安全事件摘要',
}
const detail = {
  ...summary, eventIds: ['SEC-1', 'SEC-2'], alertIds: ['ALT-1'],
  evidence: [{ sourceId: 'SEC-1', occurredAt: summary.openedAt, summary: summary.summary }],
  timeline: [{ sourceType: 'SECURITY_EVENT', sourceId: 'SEC-1', occurredAt: summary.openedAt, label: '安全事件' }],
  recommendations: ['核对安全处置手册。'],
}

describe('SecurityIncidentCenter', () => {
  const originalFetch = globalThis.fetch
  enableAutoUnmount(afterEach)
  afterEach(() => { globalThis.fetch = originalFetch })

  it('renders a safe incident and allows review and handoff for approver', async () => {
    const requests: Array<{ url: string; method: string }> = []
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      requests.push({ url, method: init?.method ?? 'GET' })
      if (url.includes('/api/security/incidents?')) return response({ items: [summary], total: 1 })
      if (url.endsWith('/review')) return response({ ...detail, status: 'REVIEWED', reviewedAt: '2026-09-02T10:00:00Z' })
      if (url.endsWith('/handoff')) return response({ ...detail, status: 'HANDOFF', handoffWorkItemId: 'SECURITY_INCIDENT:INC-1' })
      return response(detail)
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'APPROVER' } })
    await flushPromises()
    expect(wrapper.text()).toContain('REDACTED:安全事件摘要')
    await wrapper.get('[data-security-action="review"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已研判')
    await wrapper.get('[data-security-action="handoff"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已转协同')
    expect(requests.map(request => request.method)).toEqual(['GET', 'GET', 'POST', 'POST'])
    expect(wrapper.emitted('open-collaboration')?.[0]).toEqual([{ incidentId: 'INC-1', workItemId: 'SECURITY_INCIDENT:INC-1' }])
  })

  it('hides the view for customer agent and reports an empty state', async () => {
    const calls: string[] = []
    globalThis.fetch = (async (input) => { calls.push(String(input)); return response({ items: [], total: 0 }) }) as typeof fetch
    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'CUSTOMER_AGENT' } })
    await flushPromises()
    expect(calls).toHaveLength(0)
    expect(wrapper.text()).toContain('仅授权安全角色可查看')
  })

  it('ignores a stale detail response after selecting another incident', async () => {
    let resolveFirst!: (value: Response) => void
    const first = new Promise<Response>(resolve => { resolveFirst = resolve })
    let detailCalls = 0
    globalThis.fetch = (async (input) => {
      const url = String(input)
      if (url.includes('/api/security/incidents?')) return response({ items: [summary, { ...summary, incidentId: 'INC-2', summary: 'REDACTED:第二事件' }], total: 2 })
      detailCalls += 1
      if (detailCalls === 1) return first
      return response({ ...detail, incidentId: 'INC-2', summary: 'REDACTED:第二事件', evidence: [{ ...detail.evidence[0], summary: 'REDACTED:第二事件' }] })
    }) as typeof fetch
    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    await wrapper.get('[data-security-incident="INC-2"]').trigger('click')
    await flushPromises()
    resolveFirst(response(detail))
    await flushPromises()
    expect(wrapper.text()).toContain('REDACTED:第二事件')
    expect(wrapper.text()).not.toContain('REDACTED:安全事件摘要')
  })
})
