import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SecurityIncidentCenter from './SecurityIncidentCenter.vue'

const messageSpies = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: messageSpies }))

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const summary = {
  incidentId: 'INC-1', parkId: 'PARK-A', buildingId: 'A1', eventType: 'ACCESS_ANOMALY',
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
  beforeEach(() => { messageSpies.success.mockClear(); messageSpies.error.mockClear() })

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

  it('records a human false-positive disposition instead of fabricating review data', async () => {
    const bodies: Array<string | undefined> = []
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/api/security/incidents?')) {
        return response({ items: [{ ...summary, disposition: 'UNREVIEWED' }], total: 1 })
      }
      if (url.endsWith('/review')) {
        bodies.push(init?.body as string | undefined)
        return response({
          ...detail,
          status: 'REVIEWED',
          disposition: 'FALSE_POSITIVE',
          dispositionSource: 'HUMAN_REVIEW',
          dispositionDecidedAt: '2026-09-02T10:00:00Z',
        })
      }
      return response({ ...detail, disposition: 'UNREVIEWED' })
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'APPROVER' } })
    await flushPromises()
    expect(wrapper.get('[data-security-false-positive]').text()).toContain('暂无复核结论')

    await wrapper.get('[data-security-action="review-false-positive"]').trigger('click')
    await flushPromises()

    expect(bodies).toEqual([JSON.stringify({ disposition: 'FALSE_POSITIVE' })])
    expect(wrapper.get('[data-security-disposition]').text()).toContain('误报（人工复核结论）')
    expect(wrapper.get('[data-security-false-positive]').text()).toContain('1')
  })

  it('reports the persisted disposition when the review request is a no-op', async () => {
    globalThis.fetch = (async (input) => {
      const url = String(input)
      if (url.includes('/api/security/incidents?')) {
        return response({ items: [{ ...summary, disposition: 'UNREVIEWED' }], total: 1 })
      }
      if (url.endsWith('/review')) {
        return response({
          ...detail,
          status: 'REVIEWED',
          disposition: 'CONFIRMED_INCIDENT',
          dispositionSource: 'HUMAN_REVIEW',
        })
      }
      return response({ ...detail, disposition: 'UNREVIEWED' })
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'APPROVER' } })
    await flushPromises()

    await wrapper.get('[data-security-action="review-false-positive"]').trigger('click')
    await flushPromises()

    expect(messageSpies.success).toHaveBeenCalledWith('事件已记录研判：确认事件')
  })

  it('labels event type and source metadata without exposing raw media', async () => {
    const enriched = {
      ...detail,
      disposition: 'UNREVIEWED',
      evidence: [{
        ...detail.evidence[0],
        rawEventType: 'UNAUTHORIZED_ACCESS_ATTEMPT',
        sourceType: 'ACCESS_CONTROL',
        eventSourceId: 'demo-access',
        severity: 'MEDIUM',
        confidence: 0.6,
      }],
    }
    globalThis.fetch = (async (input) => {
      const url = String(input)
      return url.includes('/api/security/incidents?')
        ? response({ items: [{ ...summary, disposition: 'UNREVIEWED' }], total: 1 })
        : response(enriched)
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.get('[data-security-event-type]').text()).toContain('门禁异常')
    expect(wrapper.get('[data-security-evidence-source]').text()).toContain('UNAUTHORIZED_ACCESS_ATTEMPT')
    expect(wrapper.get('[data-security-evidence-source]').text()).toContain('门禁系统')
    expect(wrapper.get('[data-security-confidence]').text()).toBe('60%')
    expect(wrapper.text()).not.toContain('data:image')
  })

  it('keeps duplicate event ids from different sources as distinct list entries', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const duplicated = {
      ...detail,
      evidence: [
        { sourceId: 'SEC-DUP', occurredAt: summary.openedAt, summary: 'REDACTED:门禁', sourceType: 'ACCESS_CONTROL', eventSourceId: 'demo-access' },
        { sourceId: 'SEC-DUP', occurredAt: summary.openedAt, summary: 'REDACTED:摄像机', sourceType: 'CAMERA_ANALYTICS', eventSourceId: 'demo-camera' },
      ],
      timeline: [
        { sourceType: 'SECURITY_EVENT', sourceId: 'SEC-DUP', occurredAt: summary.openedAt, label: '安全事件', reference: 'security-event:source:1#a' },
        { sourceType: 'SECURITY_EVENT', sourceId: 'SEC-DUP', occurredAt: summary.openedAt, label: '安全事件', reference: 'security-event:source:1#b' },
      ],
    }
    globalThis.fetch = (async (input) => {
      const url = String(input)
      return url.includes('/api/security/incidents?') ? response({ items: [summary], total: 1 }) : response(duplicated)
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.findAll('.security-incident-evidence li')).toHaveLength(2)
    expect(wrapper.findAll('.security-incident-timeline li')).toHaveLength(2)
    expect(warn.mock.calls.flat().join(' ')).not.toContain('Duplicate keys')
    warn.mockRestore()
  })

  it('disables every disposition action once the incident is reviewed', async () => {
    globalThis.fetch = (async (input) => {
      const url = String(input)
      return url.includes('/api/security/incidents?')
        ? response({ items: [{ ...summary, status: 'REVIEWED', disposition: 'CONFIRMED_INCIDENT' }], total: 1 })
        : response({ ...detail, status: 'REVIEWED', disposition: 'CONFIRMED_INCIDENT', dispositionSource: 'HUMAN_REVIEW' })
    }) as typeof fetch

    const wrapper = mount(SecurityIncidentCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    for (const action of ['review', 'review-false-positive', 'review-inconclusive', 'review-duplicate']) {
      expect(wrapper.get(`[data-security-action="${action}"]`).attributes('disabled')).toBeDefined()
    }
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
