import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CollaborationCenter from './CollaborationCenter.vue'

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('CollaborationCenter', () => {
  const originalFetch = globalThis.fetch

  enableAutoUnmount(afterEach)
  afterEach(() => {
    globalThis.fetch = originalFetch
    document.body.querySelectorAll('[role="dialog"]').forEach(dialog => dialog.parentElement?.remove())
  })

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
    expect(wrapper.emitted('open-view')).toEqual([['customer', undefined, 'cs-1']])
    await wrapper.get('[data-work-item="ALERT_WORKFLOW:wf-1"] button').trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([['customer', undefined, 'cs-1'], ['workflow', 'wf-1']])
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

  it('opens a safe detail drawer with SLA metadata and closes it accessibly', async () => {
    globalThis.fetch = (async () => response([{
      id: 'ALERT_WORKFLOW:wf-detail', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
      title: '告警处置 ALT-POWER-001', safeSummary: '告警 ALT-POWER-001 · A2 · DEV-POWER-001',
      parkId: 'PARK-A', buildingId: 'A2', deviceId: 'DEV-POWER-001',
      updatedAt: '2026-09-02T09:50:00Z', openedAt: '2026-09-02T09:35:00Z',
      slaDueAt: '2026-09-02T10:05:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
    }])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { attachTo: document.body, props: { role: 'ADMIN' } })
    await flushPromises()
    await wrapper.get('[data-work-item="ALERT_WORKFLOW:wf-detail"] [data-work-item-details]').trigger('click')

    const drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    expect(drawer.textContent).toContain('演示 SLA')
    expect(drawer.textContent).toContain('即将到期')
    expect(drawer.textContent).toContain('DEV-POWER-001')
    expect(drawer.textContent).not.toContain('诊断正文')
    expect(drawer.textContent).not.toContain('审批意见')
    ;(drawer.querySelector('[aria-label="关闭详情"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
  })

  it('shows terminal items as completed SLA state', async () => {
    globalThis.fetch = (async () => response([{
      id: 'CUSTOMER_TICKET:cs-done', source: 'CUSTOMER_TICKET', status: 'CLOSED', priority: 'NORMAL',
      title: '客服工单 cs-done', safeSummary: '已完成的服务请求', parkId: null, buildingId: null, deviceId: null,
      updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T05:00:00Z',
      slaDueAt: '2026-09-02T09:00:00Z', slaState: 'COMPLETED', detailPath: 'customer',
    }])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    await wrapper.get('[data-work-item="CUSTOMER_TICKET:cs-done"] [data-work-item-details]').trigger('click')
    const drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    expect(drawer.textContent).toContain('已完成')
    expect(drawer.textContent).not.toContain('已超时')
  })

  it('closes cached details when read access is revoked', async () => {
    globalThis.fetch = (async () => response([{
      id: 'CUSTOMER_TICKET:cs-access', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
      title: '客服工单 cs-access', safeSummary: '受保护的工单摘要', parkId: null, buildingId: null, deviceId: null,
      updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
      slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
    }])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    await wrapper.get('[data-work-item-details]').trigger('click')
    expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()

    await wrapper.setProps({ role: 'VIEWER' })
    await flushPromises()

    expect(wrapper.text()).toContain('当前角色无权读取协同队列')
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    wrapper.unmount()
  })

  it('refreshes the active queue so SLA labels do not become stale', async () => {
    vi.useFakeTimers()
    try {
      let calls = 0
      globalThis.fetch = (async () => {
        calls += 1
        return response([{
          id: 'CUSTOMER_TICKET:cs-refresh', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
          title: '客服工单 cs-refresh', safeSummary: '需要持续刷新的工单', parkId: null, buildingId: null, deviceId: null,
          updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
          slaDueAt: '2026-09-02T12:00:00Z', slaState: calls === 1 ? 'ON_TRACK' : 'DUE_SOON', detailPath: 'customer',
        }])
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
      await flushPromises()
      expect(wrapper.text()).toContain('正常')

      vi.advanceTimersByTime(30_000)
      await flushPromises()

      expect(calls).toBe(2)
      expect(wrapper.text()).toContain('即将到期')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not overlap a periodic refresh with an in-flight request', async () => {
    vi.useFakeTimers()
    try {
      let calls = 0
      let resolveRequest!: (value: Response) => void
      const pending = new Promise<Response>((resolve) => { resolveRequest = resolve })
      globalThis.fetch = (async () => {
        calls += 1
        return pending
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
      expect(calls).toBe(1)

      vi.advanceTimersByTime(30_000)
      await Promise.resolve()
      expect(calls).toBe(1)

      resolveRequest(response([]))
      await flushPromises()
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('refreshes the open drawer item without losing its focus trigger', async () => {
    vi.useFakeTimers()
    try {
      let calls = 0
      globalThis.fetch = (async () => {
        calls += 1
        return response([{
          id: 'CUSTOMER_TICKET:cs-open-refresh', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
          title: '客服工单 cs-open-refresh', safeSummary: calls === 1 ? '旧摘要' : '新摘要', parkId: null, buildingId: null, deviceId: null,
          updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
          slaDueAt: '2026-09-02T12:00:00Z', slaState: calls === 1 ? 'ON_TRACK' : 'DUE_SOON', detailPath: 'customer',
        }])
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { attachTo: document.body, props: { role: 'ADMIN' } })
      await flushPromises()
      const trigger = wrapper.get('[data-work-item-details]').element as HTMLButtonElement
      await wrapper.get('[data-work-item-details]').trigger('click')
      expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()
      const dialogBeforeRefresh = document.body.querySelector('[role="dialog"]') as HTMLElement
      const focusedAction = dialogBeforeRefresh.querySelector('.collaboration-drawer__actions button:last-child') as HTMLButtonElement
      focusedAction.focus()

      vi.advanceTimersByTime(30_000)
      await flushPromises()

      const dialog = document.body.querySelector('[role="dialog"]') as HTMLElement
      expect(dialog.textContent).toContain('新摘要')
      expect(dialog.textContent).toContain('即将到期')
      expect(document.activeElement).toBe(focusedAction)
      ;(dialog.querySelector('[aria-label="关闭详情"]') as HTMLButtonElement).click()
      await flushPromises()
      await wrapper.vm.$nextTick()
      expect(document.activeElement).toBe(trigger)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('closes the open drawer when its item disappears during refresh', async () => {
    vi.useFakeTimers()
    try {
      let calls = 0
      globalThis.fetch = (async () => {
        calls += 1
        return response(calls === 1 ? [{
          id: 'CUSTOMER_TICKET:cs-removed', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
          title: '客服工单 cs-removed', safeSummary: '将被移除的工单', parkId: null, buildingId: null, deviceId: null,
          updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
          slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
        }] : [])
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
      await flushPromises()
      await wrapper.get('[data-work-item-details]').trigger('click')
      expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()

      vi.advanceTimersByTime(30_000)
      await flushPromises()

      expect(document.body.querySelector('[role="dialog"]')).toBeNull()
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('closes the open drawer when a refresh fails', async () => {
    vi.useFakeTimers()
    try {
      let calls = 0
      globalThis.fetch = (async () => {
        calls += 1
        if (calls > 1) throw new Error('offline')
        return response([{
          id: 'CUSTOMER_TICKET:cs-refresh-error', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
          title: '客服工单 cs-refresh-error', safeSummary: '刷新失败的工单', parkId: null, buildingId: null, deviceId: null,
          updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
          slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
        }])
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
      await flushPromises()
      await wrapper.get('[data-work-item-details]').trigger('click')
      expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()

      vi.advanceTimersByTime(30_000)
      await flushPromises()

      expect(wrapper.text()).toContain('当前无法读取协同队列')
      expect(document.body.querySelector('[role="dialog"]')).toBeNull()
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('teleports the drawer and traps focus with escape and focus restoration', async () => {
    globalThis.fetch = (async () => response([{
      id: 'CUSTOMER_TICKET:cs-focus', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
      title: '客服工单 cs-focus', safeSummary: '需要键盘访问的工单', parkId: null, buildingId: null, deviceId: null,
      updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
      slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
    }])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { attachTo: document.body, props: { role: 'ADMIN' } })
    await flushPromises()
    const trigger = wrapper.get('[data-work-item-details]').element as HTMLButtonElement
    await wrapper.get('[data-work-item-details]').trigger('click')

    const dialog = document.body.querySelector('[role="dialog"]') as HTMLElement
    expect(dialog.parentElement?.parentElement).toBe(document.body)
    const buttons = Array.from(dialog.querySelectorAll('button')) as HTMLButtonElement[]
    expect(document.activeElement).toBe(buttons[0])

    buttons[0].focus()
    buttons[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }))
    await flushPromises()
    expect(document.activeElement).toBe(buttons[buttons.length - 1])
    buttons[buttons.length - 1].focus()
    buttons[buttons.length - 1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    await flushPromises()
    expect(document.activeElement).toBe(buttons[0])

    buttons[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    wrapper.unmount()
  })

  it('fails closed with an explicit empty state when the read API is unavailable', async () => {
    globalThis.fetch = (async () => { throw new Error('offline') }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.text()).toContain('当前无法读取协同队列')
    expect(wrapper.text()).not.toContain('ALERT_WORKFLOW:wf-1')
  })
})
