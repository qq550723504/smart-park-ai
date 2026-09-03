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
    expect(wrapper.get('[data-correlation-times]').text()).toContain('2026')
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

  it('loads incidents when the view becomes active', async () => {
    let calls = 0
    globalThis.fetch = (async (input) => {
      calls += 1
      return String(input).includes('/api/security/incidents?')
        ? response({ items: [summary], total: 1 })
        : response(detail)
    }) as typeof fetch
    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN', active: false } })

    await flushPromises()
    expect(calls).toBe(0)

    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(calls).toBe(2)
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

  it('shows zero-valued metrics as zero', async () => {
    globalThis.fetch = (async (input) => {
      const url = String(input)
      if (url.includes('/api/security/incidents?')) return response({ items: [{ ...summary, status: 'REVIEWED' }], total: 1 })
      return response({ ...detail, status: 'REVIEWED' })
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.find('.hero-metrics').text()).toContain('0待研判')
    expect(wrapper.find('.hero-metrics').text()).toContain('0已转协同')
  })

  it('clears the previous queue when a refreshed list request fails', async () => {
    let listCalls = 0
    globalThis.fetch = (async (input) => {
      const url = String(input)
      if (url.includes('/api/security/incidents?')) {
        listCalls += 1
        return listCalls === 1
          ? response({ items: [summary], total: 1 })
          : response({ message: '安全事件读取失败' }, 503)
      }
      return response(detail)
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    expect(wrapper.findAll('[data-security-incident]')).toHaveLength(1)

    await wrapper.setProps({ focusIncidentId: 'INC-REFRESHED' })
    await flushPromises()

    expect(wrapper.findAll('[data-security-incident]')).toHaveLength(0)
    expect(wrapper.get('.count-badge').text()).toBe('0')
    expect(wrapper.text()).toContain('安全事件读取失败')
  })

  it('loads every incident page so retained incidents remain reachable and metrics stay complete', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      ...summary,
      incidentId: `INC-${index + 1}`,
      status: index === 0 ? 'OPEN' : 'REVIEWED',
    }))
    const lastPage = { ...summary, incidentId: 'INC-101', status: 'HANDOFF' }
    const requests: string[] = []
    globalThis.fetch = (async (input) => {
      const url = String(input)
      requests.push(url)
      if (url.includes('offset=100')) return response({ items: [lastPage], total: 101 })
      if (url.includes('/api/security/incidents?')) return response({ items: firstPage, total: 101 })
      return response({ ...detail, incidentId: 'INC-1' })
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(requests.filter(url => url.includes('/api/security/incidents?'))).toHaveLength(2)
    expect(wrapper.get('.count-badge').text()).toBe('101')
    expect(wrapper.findAll('[data-security-incident]')).toHaveLength(101)
    expect(wrapper.find('.hero-metrics').text()).toContain('1待研判')
    expect(wrapper.find('.hero-metrics').text()).toContain('1已转协同')
  })

  it('offers navigation to an already completed handoff without creating another handoff', async () => {
    const completed = { ...detail, status: 'HANDOFF', handoffWorkItemId: 'SECURITY_INCIDENT:INC-1' }
    globalThis.fetch = (async (input, init) => {
      expect(init?.method ?? 'GET').toBe('GET')
      return String(input).includes('/api/security/incidents?')
        ? response({ items: [{ ...summary, status: 'HANDOFF' }], total: 1 })
        : response(completed)
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    await wrapper.get('[data-security-action="open-handoff"]').trigger('click')

    expect(wrapper.emitted('open-collaboration')).toEqual([[
      { incidentId: 'INC-1', workItemId: 'SECURITY_INCIDENT:INC-1' },
    ]])
  })

  it('ignores a late handoff response after selecting another incident', async () => {
    let resolveHandoff!: (value: Response) => void
    const handoff = new Promise<Response>(resolve => { resolveHandoff = resolve })
    let detailCalls = 0
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/api/security/incidents?')) return response({ items: [summary, { ...summary, incidentId: 'INC-2', summary: 'REDACTED:第二事件' }], total: 2 })
      if (init?.method === 'POST') return handoff
      detailCalls += 1
      return response(detailCalls === 1 ? detail : { ...detail, incidentId: 'INC-2', summary: 'REDACTED:第二事件', evidence: [{ ...detail.evidence[0], summary: 'REDACTED:第二事件' }] })
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    await wrapper.get('[data-security-action="handoff"]').trigger('click')
    await wrapper.get('[data-security-incident="INC-2"]').trigger('click')
    await flushPromises()
    resolveHandoff(response({ ...detail, status: 'HANDOFF', handoffWorkItemId: 'SECURITY_INCIDENT:INC-1' }))
    await flushPromises()

    expect(wrapper.text()).toContain('REDACTED:第二事件')
    expect(wrapper.emitted('open-collaboration')).toBeUndefined()
  })
})
