import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CollaborationCenter from './CollaborationCenter.vue'

vi.mock('./CollaborationSlaTrendChart.vue', () => ({
  default: { props: ['snapshots'], template: '<div class="collaboration-sla-trend__chart" />' },
}))

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
    }, {
      id: 'SECURITY_INCIDENT:INC-1', source: 'SECURITY_INCIDENT', status: 'COMPLETED', priority: 'HIGH',
      title: '安全事件研判 INC-1', safeSummary: 'REDACTED:安全事件摘要', parkId: 'PARK-A', buildingId: 'A1', deviceId: null,
      updatedAt: '2026-09-01T06:00:00Z', detailPath: 'security-incident',
    }])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.text()).toContain('ALERT_WORKFLOW:wf-1')
    expect(wrapper.text()).toContain('客服工单 cs-1')
    await wrapper.get('[data-work-item="CUSTOMER_TICKET:cs-1"] button').trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([['customer', undefined, 'cs-1']])
    await wrapper.get('[data-work-item="ALERT_WORKFLOW:wf-1"] button').trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([['customer', undefined, 'cs-1'], ['workflow', 'wf-1']])
    await wrapper.get('[data-work-item="SECURITY_INCIDENT:INC-1"] button').trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([
      ['customer', undefined, 'cs-1'], ['workflow', 'wf-1'], ['security-incident', 'INC-1'],
    ])
  })

  it('opens a migrated security handoff using its current incident id', async () => {
    globalThis.fetch = (async (input) => {
      return String(input).includes('/sla-trend') ? response([]) : response([{
        id: 'SECURITY_INCIDENT:INC-OLD', incidentId: 'INC-NEW', source: 'SECURITY_INCIDENT', status: 'COMPLETED', priority: 'HIGH',
        title: '安全事件研判 INC-NEW', safeSummary: 'REDACTED:安全事件摘要', parkId: 'PARK-A', buildingId: 'A1', deviceId: null,
        updatedAt: '2026-09-01T06:00:00Z', detailPath: 'security-incident',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    await wrapper.get('[data-work-item="SECURITY_INCIDENT:INC-OLD"] button').trigger('click')

    expect(wrapper.emitted('open-view')).toEqual([['security-incident', 'INC-NEW']])
  })

  it('hides security-detail navigation from customer agents', async () => {
    globalThis.fetch = (async (input) => {
      return String(input).includes('/sla-trend') ? response([]) : response([{
        id: 'SECURITY_INCIDENT:INC-CUSTOMER', incidentId: 'INC-CUSTOMER', source: 'SECURITY_INCIDENT', status: 'COMPLETED', priority: 'HIGH',
        title: '安全事件研判 INC-CUSTOMER', safeSummary: 'REDACTED:安全事件摘要', parkId: 'PARK-A', buildingId: 'A1', deviceId: null,
        updatedAt: '2026-09-01T06:00:00Z', detailPath: 'security-incident',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'CUSTOMER_AGENT' } })
    await flushPromises()

    const item = wrapper.get('[data-work-item="SECURITY_INCIDENT:INC-CUSTOMER"]')
    expect(item.find('[data-work-item-open]').exists()).toBe(false)
    await item.get('[data-work-item-details]').trigger('click')
    const drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    expect(drawer.querySelector('[data-work-item-open]')).toBeNull()
    expect(wrapper.emitted('open-view')).toBeUndefined()
  })

  it('loads and highlights a focused work item outside the first page', async () => {
    const focused = {
      id: 'SECURITY_INCIDENT:INC-1', source: 'SECURITY_INCIDENT', status: 'COMPLETED', priority: 'HIGH',
      title: '安全事件研判 INC-1', safeSummary: 'REDACTED:安全事件摘要', parkId: 'PARK-A', buildingId: 'A1', deviceId: null,
      updatedAt: '2026-09-01T06:00:00Z', detailPath: 'security-incident',
    }
    const calls: string[] = []
    globalThis.fetch = (async (input) => {
      const url = String(input)
      calls.push(url)
      if (url.includes('workItemId=')) return response([focused])
      if (url.includes('/sla-trend')) return response([])
      return response([])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN', focusWorkItemId: focused.id } })
    await flushPromises()

    expect(calls).toContain(`/api/collaboration/work-items?limit=1&sort=updatedAt&workItemId=${encodeURIComponent(focused.id)}`)
    expect(wrapper.get(`[data-work-item="${focused.id}"]`).classes()).toContain('is-focused')
  })

  it('consumes the focused-item override after the initial navigation', async () => {
    const focused = {
      id: 'SECURITY_INCIDENT:INC-1', source: 'SECURITY_INCIDENT', status: 'COMPLETED', priority: 'HIGH',
      title: '安全事件研判 INC-1', safeSummary: 'REDACTED:安全事件摘要', parkId: 'PARK-A', buildingId: 'A1', deviceId: null,
      updatedAt: '2026-09-01T06:00:00Z', detailPath: 'security-incident',
    }
    globalThis.fetch = (async (input) => {
      const url = String(input)
      if (url.includes('workItemId=') && !url.includes('source=') && !url.includes('status=')) return response([focused])
      return response([])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN', focusWorkItemId: focused.id } })
    await flushPromises()
    await wrapper.get('select').setValue('ALERT_WORKFLOW')
    await flushPromises()

    expect(wrapper.find(`[data-work-item="${focused.id}"]`).exists()).toBe(false)
  })

  it('reconsumes the focused-item override when navigation refreshes', async () => {
    const focused = {
      id: 'SECURITY_INCIDENT:INC-REVISIT', source: 'SECURITY_INCIDENT', status: 'COMPLETED', priority: 'HIGH',
      title: '安全事件研判 INC-REVISIT', safeSummary: 'REDACTED:安全事件摘要', parkId: 'PARK-A', buildingId: 'A1', deviceId: null,
      updatedAt: '2026-09-01T06:00:00Z', detailPath: 'security-incident',
    }
    const focusCalls: string[] = []
    globalThis.fetch = (async (input) => {
      const url = String(input)
      if (url.includes('workItemId=')) {
        focusCalls.push(url)
        return response([focused])
      }
      return response([])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, {
      props: { role: 'ADMIN', focusWorkItemId: focused.id, refreshToken: 0 },
    })
    await flushPromises()
    expect(focusCalls).toHaveLength(1)

    await wrapper.setProps({ refreshToken: 1 })
    await flushPromises()

    expect(focusCalls).toHaveLength(2)
    expect(wrapper.get(`[data-work-item="${focused.id}"]`).classes()).toContain('is-focused')
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
    expect(drawer.textContent).toContain('人工审批')
    ;(drawer.querySelector('[aria-label="关闭详情"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
  })

  it('lets an approver approve an alert from the detail drawer and refreshes the queue', async () => {
    const requests: Array<{ url: string; method: string; body?: string }> = []
    let queueCalls = 0
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      requests.push({ url, method: init?.method ?? 'GET', body: init?.body as string | undefined })
      if (url.includes('/sla-trend')) return response([])
      if (url.includes('/approval')) return response({ status: 'COMPLETED' })
      queueCalls += 1
      return response(queueCalls === 1 ? [{
        id: 'ALERT_WORKFLOW:wf-approval', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
        title: '待审批告警', safeSummary: '需要人工确认', parkId: 'PARK-A', buildingId: 'A2', deviceId: 'DEV-POWER-001',
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
      }] : [])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'APPROVER' } })
    await flushPromises()
    await wrapper.get('[data-work-item-details]').trigger('click')
    const drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    ;(drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement).value = '审批人'
    ;(drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement).dispatchEvent(new Event('input'))
    ;(drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement).value = '确认现场证据'
    ;(drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement).dispatchEvent(new Event('input'))
    ;(drawer.querySelector('[data-collaboration-action="approve"]') as HTMLButtonElement).click()
    await flushPromises()

    const approval = requests.find(request => request.url.includes('/approval'))
    expect(approval?.method).toBe('POST')
    expect(JSON.parse(approval?.body ?? '{}')).toMatchObject({ decision: 'APPROVE', reviewer: '审批人', comment: '确认现场证据' })
    expect(wrapper.text()).toContain('当前没有可展示的工作项')
    wrapper.unmount()
  })

  it('reuses the approval idempotency key after an ambiguous approval failure', async () => {
    const approvalBodies: Array<Record<string, string>> = []
    let queueCalls = 0
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/sla-trend')) return response([])
      if (url.includes('/approval')) {
        approvalBodies.push(JSON.parse(init?.body as string) as Record<string, string>)
        return approvalBodies.length === 1
          ? response({ message: '网关响应超时' }, 502)
          : response({ status: 'COMPLETED' })
      }
      queueCalls += 1
      return response([{
        id: 'ALERT_WORKFLOW:wf-retry', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
        title: '可重试审批告警', safeSummary: '审批响应不明确', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'APPROVER' } })
    await flushPromises()
    await wrapper.get('[data-work-item-details]').trigger('click')
    let drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    const reviewer = drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement
    const comment = drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement
    reviewer.value = '审批人'
    reviewer.dispatchEvent(new Event('input'))
    comment.value = '确认'
    comment.dispatchEvent(new Event('input'))
    ;(drawer.querySelector('[data-collaboration-action="approve"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(queueCalls).toBe(2)
    expect(drawer.textContent).toContain('网关响应超时')
    drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    ;(drawer.querySelector('[data-collaboration-action="approve"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(approvalBodies).toHaveLength(2)
    expect(approvalBodies[1].idempotencyKey).toBe(approvalBodies[0].idempotencyKey)
    wrapper.unmount()
  })

  it('offers the backend-supported in-progress transition for waiting-customer tickets', async () => {
    let patchBody: Record<string, string> | undefined
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/sla-trend')) return response([])
      if (init?.method === 'PATCH') {
        patchBody = JSON.parse(init.body as string) as Record<string, string>
        return response({ status: 'IN_PROGRESS' })
      }
      return response([{
        id: 'CUSTOMER_TICKET:cs-waiting-customer', source: 'CUSTOMER_TICKET', status: 'WAITING_CUSTOMER', priority: 'NORMAL',
        title: '等待用户回复的工单', safeSummary: '用户已补充信息', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'customer',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'CUSTOMER_AGENT' } })
    await flushPromises()
    await wrapper.get('[data-work-item-details]').trigger('click')
    const drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    const action = drawer.querySelector('[data-collaboration-action="customer-next"]') as HTMLButtonElement | null
    expect(action).not.toBeNull()
    action?.click()
    await flushPromises()

    expect(patchBody).toEqual({ status: 'IN_PROGRESS' })
    wrapper.unmount()
  })

  it('keeps a customer ticket drawer open and reports a safe action error', async () => {
    let queueCalls = 0
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/sla-trend')) return response([])
      if (init?.method === 'PATCH') return response({ message: '状态更新失败' }, 409)
      queueCalls += 1
      return response([{
        id: 'CUSTOMER_TICKET:cs-action', source: 'CUSTOMER_TICKET', status: queueCalls === 1 ? 'WAITING_AGENT' : 'ASSIGNED', priority: 'NORMAL',
        title: '待接入客服工单', safeSummary: '等待客服处理', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'customer',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'CUSTOMER_AGENT' } })
    await flushPromises()
    await wrapper.get('[data-work-item-details]').trigger('click')
    const drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    ;(drawer.querySelector('[data-collaboration-action="customer-next"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(drawer.textContent).toContain('状态更新失败')
    expect(drawer.textContent).toContain('已分派')
    expect(queueCalls).toBe(2)
    expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()
    wrapper.unmount()
  })

  it('does not submit a second alert approval while the first is pending', async () => {
    let resolveApproval!: (value: Response) => void
    const approvalPending = new Promise<Response>(resolve => { resolveApproval = resolve })
    let approvalCalls = 0
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/sla-trend')) return response([])
      if (url.includes('/approval')) {
        approvalCalls += 1
        return approvalPending
      }
      return response([{
        id: 'ALERT_WORKFLOW:wf-single-flight', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
        title: '单次审批告警', safeSummary: '只允许一次提交', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'APPROVER' } })
    await flushPromises()
    await wrapper.get('[data-work-item-details]').trigger('click')
    const drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    const reviewer = drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement
    const comment = drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement
    reviewer.value = '审批人'
    reviewer.dispatchEvent(new Event('input'))
    comment.value = '确认'
    comment.dispatchEvent(new Event('input'))
    const approve = drawer.querySelector('[data-collaboration-action="approve"]') as HTMLButtonElement
    approve.click()
    approve.click()
    await flushPromises()
    expect(approvalCalls).toBe(1)
    resolveApproval(response({ status: 'COMPLETED' }))
    await flushPromises()
    wrapper.unmount()
  })

  it('does not show an action failure in a different item drawer', async () => {
    let rejectApproval!: (cause: Error) => void
    const pendingApproval = new Promise<Response>((_, reject) => { rejectApproval = reject })
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/sla-trend')) return response([])
      if (url.includes('/approval')) return pendingApproval
      return response([{
        id: 'ALERT_WORKFLOW:wf-first', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
        title: '第一个审批项', safeSummary: '第一个摘要', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
      }, {
        id: 'ALERT_WORKFLOW:wf-second', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
        title: '第二个审批项', safeSummary: '第二个摘要', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { attachTo: document.body, props: { role: 'APPROVER' } })
    await flushPromises()
    await wrapper.get('[data-work-item="ALERT_WORKFLOW:wf-first"] [data-work-item-details]').trigger('click')
    let drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    ;(drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement).value = '审批人'
    ;(drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement).dispatchEvent(new Event('input'))
    ;(drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement).value = '确认'
    ;(drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement).dispatchEvent(new Event('input'))
    ;(drawer.querySelector('[data-collaboration-action="approve"]') as HTMLButtonElement).click()
    await Promise.resolve()

    ;(drawer.querySelector('[aria-label="关闭详情"]') as HTMLButtonElement).click()
    await wrapper.get('[data-work-item="ALERT_WORKFLOW:wf-second"] [data-work-item-details]').trigger('click')
    rejectApproval(new Error('第一个审批项失败'))
    await flushPromises()

    drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    expect(drawer.textContent).not.toContain('第一个审批项失败')
    wrapper.unmount()
  })

  it('keeps an in-flight approval locked when its drawer is closed and reopened', async () => {
    let resolveApproval!: (value: Response) => void
    const pendingApproval = new Promise<Response>(resolve => { resolveApproval = resolve })
    let approvalCalls = 0
    globalThis.fetch = (async (input, init) => {
      const url = String(input)
      if (url.includes('/sla-trend')) return response([])
      if (url.includes('/approval')) {
        approvalCalls += 1
        return pendingApproval
      }
      return response([{
        id: 'ALERT_WORKFLOW:wf-reopen', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
        title: '重开后仍锁定的审批项', safeSummary: '审批请求处理中', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T10:00:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { attachTo: document.body, props: { role: 'APPROVER' } })
    await flushPromises()
    await wrapper.get('[data-work-item-details]').trigger('click')
    let drawer = document.body.querySelector('[role="dialog"]') as HTMLElement
    ;(drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement).value = '审批人'
    ;(drawer.querySelector('[data-approval-reviewer]') as HTMLInputElement).dispatchEvent(new Event('input'))
    ;(drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement).value = '确认'
    ;(drawer.querySelector('[data-approval-comment]') as HTMLTextAreaElement).dispatchEvent(new Event('input'))
    ;(drawer.querySelector('[data-collaboration-action="approve"]') as HTMLButtonElement).click()
    await Promise.resolve()

    ;(drawer.querySelector('[aria-label="关闭详情"]') as HTMLButtonElement).click()
    await wrapper.get('[data-work-item-details]').trigger('click')
    drawer = document.body.querySelector('[role="dialog"]') as HTMLElement

    expect((drawer.querySelector('[data-collaboration-action="approve"]') as HTMLButtonElement).disabled).toBe(true)
    expect(approvalCalls).toBe(1)
    resolveApproval(response({ status: 'COMPLETED' }))
    await flushPromises()
    wrapper.unmount()
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

  it('summarizes the current queue and sorts items by SLA urgency', async () => {
    globalThis.fetch = (async () => response([
      {
        id: 'ALERT_WORKFLOW:on-track', source: 'ALERT_WORKFLOW', status: 'RUNNING', priority: 'NORMAL',
        title: '正常工作项', safeSummary: '仍在 SLA 内', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T08:00:00Z', openedAt: '2026-09-02T07:00:00Z',
        slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'workflow',
      },
      {
        id: 'CUSTOMER_TICKET:due-soon', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
        title: '即将到期工作项', safeSummary: '即将达到 SLA', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T08:30:00Z', openedAt: '2026-09-02T07:30:00Z',
        slaDueAt: '2026-09-02T09:30:00Z', slaState: 'DUE_SOON', detailPath: 'customer',
      },
      {
        id: 'ALERT_WORKFLOW:overdue', source: 'ALERT_WORKFLOW', status: 'FAILED', priority: 'HIGH',
        title: '超时工作项', safeSummary: '已超过 SLA', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T06:00:00Z',
        slaDueAt: '2026-09-02T08:00:00Z', slaState: 'OVERDUE', detailPath: 'workflow',
      },
      {
        id: 'CUSTOMER_TICKET:completed', source: 'CUSTOMER_TICKET', status: 'CLOSED', priority: 'NORMAL',
        title: '已完成工作项', safeSummary: '已完成', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T10:00:00Z', openedAt: '2026-09-02T05:00:00Z',
        slaDueAt: '2026-09-02T09:00:00Z', slaState: 'COMPLETED', detailPath: 'customer',
      },
    ])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.get('[data-sla-overview="total"] strong').text()).toBe('4')
    expect(wrapper.get('[data-sla-overview="overdue"] strong').text()).toBe('1')
    expect(wrapper.get('[data-sla-overview="due-soon"] strong').text()).toBe('1')
    expect(wrapper.get('[data-sla-overview="on-track"] strong').text()).toBe('1')

    await wrapper.get('[data-sla-sort]').setValue('sla')
    expect(wrapper.findAll('[data-work-item]').map(item => item.attributes('data-work-item'))).toEqual([
      'ALERT_WORKFLOW:overdue', 'CUSTOMER_TICKET:due-soon', 'ALERT_WORKFLOW:on-track', 'CUSTOMER_TICKET:completed',
    ])

    await wrapper.get('[data-sla-sort]').setValue('updatedAt')
    expect(wrapper.find('[data-work-item]').attributes('data-work-item')).toBe('CUSTOMER_TICKET:completed')
  })

  it('orders equal active SLA states by deadline before update time', async () => {
    globalThis.fetch = (async () => response([
      {
        id: 'CUSTOMER_TICKET:due-later', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
        title: '较晚到期工单', safeSummary: '较晚到期', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:50:00Z', openedAt: '2026-09-02T08:20:00Z',
        slaDueAt: '2026-09-02T10:20:00Z', slaState: 'DUE_SOON', detailPath: 'customer',
      },
      {
        id: 'ALERT_WORKFLOW:due-first', source: 'ALERT_WORKFLOW', status: 'RUNNING', priority: 'HIGH',
        title: '较早到期告警', safeSummary: '较早到期', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:05:00Z',
        slaDueAt: '2026-09-02T10:05:00Z', slaState: 'DUE_SOON', detailPath: 'workflow',
      },
    ])) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.findAll('[data-work-item]').map(item => item.attributes('data-work-item'))).toEqual([
      'ALERT_WORKFLOW:due-first', 'CUSTOMER_TICKET:due-later',
    ])
  })

  it('sends the selected queue sort mode to the API', async () => {
    const requests: string[] = []
    globalThis.fetch = (async (input) => {
      requests.push(String(input))
      return response([])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    expect(requests.some(url => url.includes('sort=sla'))).toBe(true)

    await wrapper.get('[data-sla-sort]').setValue('updatedAt')
    await flushPromises()
    expect(requests.some(url => url.includes('sort=updatedAt'))).toBe(true)
  })

  it('renders the session SLA trend independently from the work-item queue', async () => {
    const requests: string[] = []
    globalThis.fetch = (async (input) => {
      const url = String(input)
      requests.push(url)
      if (url.includes('/sla-trend')) {
        return response([{ capturedAt: '2026-09-02T10:00:00Z', total: 1, overdue: 1, dueSoon: 0, onTrack: 0, completed: 0, notApplicable: 0 }])
      }
      return response([{
        id: 'ALERT_WORKFLOW:trend', source: 'ALERT_WORKFLOW', status: 'FAILED', priority: 'HIGH',
        title: '趋势告警', safeSummary: '趋势采样工作项', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T10:00:00Z', openedAt: '2026-09-02T09:00:00Z',
        slaDueAt: '2026-09-02T09:30:00Z', slaState: 'OVERDUE', detailPath: 'workflow',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.text()).toContain('本次会话 SLA 趋势')
    expect(wrapper.get('[data-sla-trend-count]').text()).toContain('1')
    expect(wrapper.find('.collaboration-sla-trend__chart').exists()).toBe(true)
    expect(requests.some(url => url.includes('/sla-trend?limit=60'))).toBe(true)
  })

  it('keeps the queue available when the SLA trend request fails', async () => {
    globalThis.fetch = (async (input) => {
      if (String(input).includes('/sla-trend')) throw new Error('trend offline')
      return response([{
        id: 'CUSTOMER_TICKET:trend-error', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
        title: '趋势失败时仍可用', safeSummary: '队列不应被趋势错误阻断', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T10:00:00Z', openedAt: '2026-09-02T09:00:00Z',
        slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
      }])
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()

    expect(wrapper.text()).toContain('趋势失败时仍可用')
    expect(wrapper.text()).toContain('当前无法读取 SLA 趋势')
  })

  it('does not request SLA trend data for a role without queue access', async () => {
    const requests: string[] = []
    globalThis.fetch = (async (input) => {
      requests.push(String(input))
      return response([])
    }) as typeof fetch

    mount(CollaborationCenter, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(requests).toEqual([])
  })

  it('keeps the previous trend visible while a background trend refresh is pending', async () => {
    vi.useFakeTimers()
    try {
      let trendCalls = 0
      let resolveTrendRefresh!: (value: Response) => void
      const pendingTrendRefresh = new Promise<Response>(resolve => { resolveTrendRefresh = resolve })
      globalThis.fetch = (async (input) => {
        if (String(input).includes('/sla-trend')) {
          trendCalls += 1
          if (trendCalls === 1) {
            return response([{ capturedAt: '2026-09-02T10:00:00Z', total: 1, overdue: 0, dueSoon: 0, onTrack: 1, completed: 0, notApplicable: 0 }])
          }
          return pendingTrendRefresh
        }
        return response([{
          id: 'CUSTOMER_TICKET:trend-refresh', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
          title: '趋势刷新工作项', safeSummary: '保留趋势的工作项', parkId: null, buildingId: null, deviceId: null,
          updatedAt: '2026-09-02T10:00:00Z', openedAt: '2026-09-02T09:00:00Z',
          slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
        }])
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
      await flushPromises()
      expect(wrapper.get('[data-sla-trend-count]').text()).toContain('1')

      await vi.advanceTimersByTimeAsync(30_000)
      await wrapper.vm.$nextTick()
      expect(trendCalls).toBe(2)
      expect(wrapper.get('[data-sla-trend-count]').text()).toContain('1')

      resolveTrendRefresh(response([
        { capturedAt: '2026-09-02T10:00:00Z', total: 1, overdue: 0, dueSoon: 0, onTrack: 1, completed: 0, notApplicable: 0 },
        { capturedAt: '2026-09-02T10:00:30Z', total: 1, overdue: 1, dueSoon: 0, onTrack: 0, completed: 0, notApplicable: 0 },
      ]))
      await flushPromises()
      expect(wrapper.get('[data-sla-trend-count]').text()).toContain('2')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
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
      let queueCalls = 0
      globalThis.fetch = (async (input) => {
        if (String(input).includes('/sla-trend')) return response([])
        queueCalls += 1
        return response([{
          id: 'CUSTOMER_TICKET:cs-refresh', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
          title: '客服工单 cs-refresh', safeSummary: '需要持续刷新的工单', parkId: null, buildingId: null, deviceId: null,
          updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
          slaDueAt: '2026-09-02T12:00:00Z', slaState: queueCalls === 1 ? 'ON_TRACK' : 'DUE_SOON', detailPath: 'customer',
        }])
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
      await flushPromises()
      expect(wrapper.text()).toContain('正常')

      vi.advanceTimersByTime(30_000)
      await flushPromises()

      expect(queueCalls).toBe(2)
      expect(wrapper.text()).toContain('即将到期')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps the SLA overview visible while a background refresh is pending', async () => {
    vi.useFakeTimers()
    try {
      let calls = 0
      let resolveRefresh!: (value: Response) => void
      const pendingRefresh = new Promise<Response>((resolve) => { resolveRefresh = resolve })
      globalThis.fetch = (async (input) => {
        if (String(input).includes('/sla-trend')) return response([])
        calls += 1
        if (calls === 1) return response([{
          id: 'CUSTOMER_TICKET:cs-overview-refresh', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
          title: '客服工单 cs-overview-refresh', safeSummary: '保留概览的工单', parkId: null, buildingId: null, deviceId: null,
          updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
          slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
        }])
        return pendingRefresh
      }) as typeof fetch

      const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
      await flushPromises()
      expect(wrapper.get('[data-sla-overview="total"]')).not.toBeNull()

      vi.advanceTimersByTime(30_000)
      await Promise.resolve()
      await wrapper.vm.$nextTick()

      expect(calls).toBe(2)
      expect(wrapper.get('[data-sla-overview="total"]')).not.toBeNull()
      resolveRefresh(response([]))
      await flushPromises()
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

      const wrapper = mount(CollaborationCenter, { attachTo: document.body, props: { role: 'ADMIN' } })
      await flushPromises()
      await wrapper.get('[data-work-item-details]').trigger('click')
      expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()

      vi.advanceTimersByTime(30_000)
      await flushPromises()

      expect(document.body.querySelector('[role="dialog"]')).toBeNull()
      expect(document.activeElement).toBe(document.body.querySelector('[data-collaboration-queue-heading]'))
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

  it('hides old rows while a filter request is still loading', async () => {
    let resolveFilteredRequest!: (value: Response) => void
    const filteredRequest = new Promise<Response>((resolve) => { resolveFilteredRequest = resolve })
    let calls = 0
    globalThis.fetch = (async (input) => {
      if (String(input).includes('/sla-trend')) return response([])
      calls += 1
      if (calls === 1) return response([{
        id: 'ALERT_WORKFLOW:old-filter-result', source: 'ALERT_WORKFLOW', status: 'WAITING_APPROVAL', priority: 'HIGH',
        title: '旧告警结果', safeSummary: '不应在新筛选下继续显示', parkId: null, buildingId: null, deviceId: null,
        updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
        slaDueAt: '2026-09-02T08:30:00Z', slaState: 'OVERDUE', detailPath: 'workflow',
      }])
      return filteredRequest
    }) as typeof fetch

    const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
    await flushPromises()
    expect(wrapper.text()).toContain('旧告警结果')

    await wrapper.get('select').setValue('CUSTOMER_TICKET')
    expect(wrapper.text()).not.toContain('旧告警结果')
    expect(wrapper.text()).toContain('正在读取协同队列')

    resolveFilteredRequest(response([{
      id: 'CUSTOMER_TICKET:new-filter-result', source: 'CUSTOMER_TICKET', status: 'WAITING_AGENT', priority: 'NORMAL',
      title: '新筛选结果', safeSummary: '筛选后的工单', parkId: null, buildingId: null, deviceId: null,
      updatedAt: '2026-09-02T09:00:00Z', openedAt: '2026-09-02T08:00:00Z',
      slaDueAt: '2026-09-02T12:00:00Z', slaState: 'ON_TRACK', detailPath: 'customer',
    }]))
    await flushPromises()
    expect(wrapper.text()).toContain('新筛选结果')
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
