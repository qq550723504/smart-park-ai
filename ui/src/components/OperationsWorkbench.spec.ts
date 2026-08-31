import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, onMounted, onUnmounted } from 'vue'
import { ElInput } from 'element-plus'
import OperationsWorkbench from './OperationsWorkbench.vue'
import ImmersiveWorkbenchShell from './workbench/ImmersiveWorkbenchShell.vue'

const mounts = {
  analysis: 0,
  workflow: 0,
}

const unmounts = {
  analysis: 0,
  workflow: 0,
}

const analysisStub = defineComponent({
  setup() {
    onMounted(() => { mounts.analysis += 1 })
    onUnmounted(() => { unmounts.analysis += 1 })
    return () => null
  },
})

const workflowStub = defineComponent({
  setup() {
    onMounted(() => { mounts.workflow += 1 })
    onUnmounted(() => { unmounts.workflow += 1 })
    return () => null
  },
})

const voiceStub = defineComponent({
  props: {
    active: {
      type: Boolean,
      default: true,
    },
  },
  setup(props) {
    return () => h('div', { 'data-testid': 'voice-active' }, String(props.active))
  },
})

const customerStub = defineComponent({
  props: { role: { type: String, required: true } },
  template: '<div data-testid="customer-role">{{ role }}</div>',
})

const guidedStatusStub = defineComponent({
  props: { active: { type: Boolean, default: true }, launchRequest: { type: Object, default: null } },
  emits: ['launch-status'],
  setup(_, { emit }) {
    return {
      sendLaunchStatus(update: { requestId: number; state: 'preparing' | 'started' | 'ready' | 'failed'; message: string }) {
        emit('launch-status', update)
      },
    }
  },
  template: '<div data-testid="guided-status-scene" />',
})

const alertSelectorStub = defineComponent({
  emits: ['select', 'start'],
  template: '<button type="button" data-select-alternate-alert @click="$emit(\'select\', \'ALT-POWER-001\')">选择其他告警</button>',
})

const operatorStubs = {
  OperationsAnalysisPage: analysisStub,
  ExpertCollaborationPage: true,
  AlertSelector: alertSelectorStub,
  WorkflowGraph: true,
  DemoConsole: true,
  EventTimeline: true,
  CustomerServiceConsole: customerStub,
  ExecutionTraceRail: true,
  'el-select': true,
  'el-option': true,
  'el-tag': true,
  'el-button': true,
  'el-input': true,
}

async function settleCapabilities() {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

describe('OperationsWorkbench', () => {
  let originalFetch: typeof fetch

  beforeEach(() => {
    mounts.analysis = 0
    mounts.workflow = 0
    unmounts.analysis = 0
    unmounts.workflow = 0
    originalFetch = globalThis.fetch
    globalThis.fetch = (async () => new Response(JSON.stringify({
      knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
      analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('keeps analysis mounted while switching operator views', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })
    await settleCapabilities()
    await wrapper.get('[data-workbench-view="analytics"]').trigger('click')
    await wrapper.get('[data-workbench-view="workflow"]').trigger('click')
    expect(mounts.analysis).toBe(1)
    expect(unmounts.analysis).toBe(0)
  })

  it('defers the workflow graph until its visible view is first opened', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'collaboration' },
      global: {
        stubs: {
          ...operatorStubs,
          WorkflowGraph: workflowStub,
        },
      },
    })

    await settleCapabilities()
    expect(mounts.workflow).toBe(0)

    await wrapper.get('[data-workbench-view="workflow"]').trigger('click')
    await nextTick()
    expect(mounts.workflow).toBe(1)

    await wrapper.get('[data-workbench-view="collaboration"]').trigger('click')
    expect(mounts.workflow).toBe(1)
    expect(unmounts.workflow).toBe(0)
  })

  it('deactivates voice when the outer workbench surface is hidden', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'voice', active: true },
      global: {
        stubs: {
          ...operatorStubs,
          VoiceAssistantPage: voiceStub,
        },
      },
    })

    expect(wrapper.get('[data-testid="voice-active"]').text()).toBe('true')
    await wrapper.setProps({ active: false })
    expect(wrapper.get('[data-testid="voice-active"]').text()).toBe('false')
  })

  it('reapplies the requested view when the cached workbench is shown again', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'collaboration', active: true },
      global: { stubs: operatorStubs },
    })
    await settleCapabilities()

    await wrapper.get('[data-workbench-view="analytics"]').trigger('click')
    expect(wrapper.get('[data-workbench-view="analytics"]').classes()).toContain('active')

    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })

    expect(wrapper.get('[data-workbench-view="collaboration"]').classes()).toContain('active')
  })

  it('keeps every scenario inside one stable stage beside the persistent rail', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })

    await settleCapabilities()

    expect(wrapper.findAll('[data-workbench-stage] > .main-content')).toHaveLength(5)
    expect(wrapper.findAll('[data-workbench-rail] .global-rail')).toHaveLength(1)
    const shell = wrapper.get('[data-testid="immersive-workbench-shell"]')
    const rail = wrapper.get('[data-workbench-rail] .global-rail')
    await wrapper.get('[data-workbench-view="analytics"]').trigger('click')
    expect(wrapper.get('[data-testid="immersive-workbench-shell"]').element).toBe(shell.element)
    expect(wrapper.get('[data-workbench-rail] .global-rail').element).toBe(rail.element)
  })

  it('hides capability-gated operator navigation when backend capabilities are disabled', async () => {
    globalThis.fetch = (async () => new Response(JSON.stringify({
      knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
      analyticsEnabled: false, collaborationEnabled: false, voiceEnabled: false,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch

    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })

    await settleCapabilities()

    const labels = wrapper.findAll('.immersive-workbench__nav button').map((button) => button.text())
    expect(labels).not.toContain('实时语音')
    expect(labels).not.toContain('专家协作')
    expect(labels).not.toContain('运营分析')
  })

  it('emits an intent to return to the showcase surface', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })

    await settleCapabilities()
    await wrapper.get('[data-workbench-action="back-to-showcase"]').trigger('click')

    expect(wrapper.emitted('back-to-showcase')).toHaveLength(1)
  })

  it('derives evidence ribbon values from the active scene, trace state, and capabilities', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })

    await settleCapabilities()

    expect(wrapper.get('[data-evidence-item="场景"] strong').text()).toBe('告警工作流')
    expect(wrapper.get('[data-evidence-item="执行轨迹"] strong').text()).toBe('空闲')
    expect(wrapper.get('[data-evidence-item="知识检索"] strong').text()).toBe('Mock')
    expect(wrapper.get('[data-evidence-item="执行模式"] strong').text()).toBe('受控写入 · 审批后创建工单')
  })

  it('discloses scene-accurate execution semantics and verification tone', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })

    await settleCapabilities()

    const expectedModes = [
      ['workflow', '受控写入 · 审批后创建工单', 'warning'],
      ['customer', '受控写入 · 可创建客服工单', 'warning'],
      ['voice', '只读查询 · 实时语音会话', 'verified'],
      ['collaboration', '只读查询 · 多专家汇总', 'verified'],
      ['analytics', '真实只读数据', 'verified'],
    ] as const

    for (const [view, value, tone] of expectedModes) {
      await wrapper.get(`[data-workbench-view="${view}"]`).trigger('click')
      const evidence = wrapper.get('[data-evidence-item="执行模式"]')
      expect(evidence.get('strong').text()).toBe(value)
      expect(evidence.attributes('data-tone')).toBe(tone)
    }
  })

  it('keeps knowledge evidence in a loading state until capabilities resolve', async () => {
    let resolveCapabilities!: (response: Response) => void
    globalThis.fetch = (() => new Promise<Response>((resolve) => { resolveCapabilities = resolve })) as typeof fetch

    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })
    await nextTick()

    expect(wrapper.get('[data-evidence-item="知识检索"] strong').text()).toBe('检查中')
    expect(wrapper.get('[data-evidence-item="知识检索"]').attributes('data-tone')).toBe('default')

    resolveCapabilities(new Response(JSON.stringify({
      knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
      analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await settleCapabilities()

    expect(wrapper.get('[data-evidence-item="知识检索"] strong').text()).toBe('Mock')
  })

  it('shows an explicit unverified capability error when capability loading fails', async () => {
    globalThis.fetch = (async () => { throw new Error('capability request failed') }) as typeof fetch
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })

    await settleCapabilities()

    expect(wrapper.findAll('.immersive-workbench__nav button').map((button) => button.text())).toEqual(['告警工作流', '园区客服'])
    expect(wrapper.get('[data-evidence-item="知识检索"] strong').text()).toBe('能力检查失败')
    expect(wrapper.get('[data-evidence-item="知识检索"]').attributes('data-tone')).toBe('warning')
  })

  it('uses the shell view and role event contracts to update the active scene and role', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })
    await settleCapabilities()

    const shell = wrapper.getComponent(ImmersiveWorkbenchShell)
    shell.vm.$emit('switch-view', 'customer')
    shell.vm.$emit('update:role', 'CUSTOMER_AGENT')
    await nextTick()

    expect(wrapper.get('[data-workbench-view="customer"]').classes()).toContain('active')
    expect(wrapper.get('[data-testid="customer-role"]').text()).toBe('CUSTOMER_AGENT')
  })

  it('opens the narrow-screen execution rail and keeps approval fields accessibly named', async () => {
    const originalEventSource = globalThis.EventSource
    globalThis.EventSource = class {
      onerror: ((event: Event) => void) | null = null
      constructor(_url: string | URL) {}
      addEventListener(): void {}
      close(): void {}
    } as unknown as typeof EventSource
    globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return new Response(JSON.stringify({
          workflowId: 'wf-waiting-approval', alertId: 'ALT-TEMP-001', status: 'WAITING_APPROVAL',
          diagnosis: null, approval: null, workOrder: null, errors: [], eventSequence: 1, riskReasons: [],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({
        knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
        analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: true,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch

    try {
      const wrapper = mount(OperationsWorkbench, {
        props: {
          initialView: 'workflow',
          launchRequest: { requestId: 60, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow' },
        },
        global: { stubs: { ...operatorStubs, 'el-input': ElInput } },
      })
      await settleCapabilities()
      await settleCapabilities()

      expect(wrapper.get('[data-workbench-rail]').attributes('open')).toBeDefined()
      const reviewerInput = wrapper.get('.approval-form input')
      const commentInput = wrapper.get('.approval-form textarea')
      await reviewerInput.setValue('王敏')
      await commentInput.setValue('确认现场处置条件')
      expect(reviewerInput.attributes('aria-label')).toBe('审批人姓名')
      expect(commentInput.attributes('aria-label')).toBe('审批意见')
      expect((reviewerInput.element as HTMLInputElement).value).toBe('王敏')
      expect((commentInput.element as HTMLTextAreaElement).value).toBe('确认现场处置条件')
      wrapper.unmount()
    } finally {
      globalThis.EventSource = originalEventSource
    }
  })

  it('keeps the manual workbench entry idle without a guided status', () => {
    expect(() => mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })).not.toThrow()
  })

  it('starts a guided alert workflow once across workbench reactivation', async () => {
    let workflowStarts = 0
    const originalEventSource = globalThis.EventSource
    globalThis.EventSource = class {
      onerror: ((event: Event) => void) | null = null
      constructor(_url: string | URL) {}
      addEventListener(): void {}
      close(): void {}
    } as unknown as typeof EventSource
    globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        workflowStarts += 1
        return new Response(JSON.stringify({
          workflowId: 'wf-guided-1', alertId: 'ALT-TEMP-001', status: 'RUNNING',
          diagnosis: null, approval: null, workOrder: null, errors: [], eventSequence: 1, riskReasons: [],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({
        knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
        analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: true,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch

    try {
      const wrapper = mount(OperationsWorkbench, {
        props: {
          active: true,
          initialView: 'workflow',
          launchRequest: { requestId: 20, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow' },
        },
        global: { stubs: operatorStubs },
      })
      await settleCapabilities()
      expect(workflowStarts).toBe(1)
      await wrapper.setProps({ active: false })
      await wrapper.setProps({ active: true })
      await settleCapabilities()
      expect(workflowStarts).toBe(1)
      wrapper.unmount()
    } finally {
      globalThis.EventSource = originalEventSource
    }
  })

  it('resets a previously selected alert before starting the guided workflow', async () => {
    let guidedAlertId = ''
    const originalEventSource = globalThis.EventSource
    globalThis.EventSource = class {
      onerror: ((event: Event) => void) | null = null
      constructor(_url: string | URL) {}
      addEventListener(): void {}
      close(): void {}
    } as unknown as typeof EventSource
    globalThis.fetch = (async (url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        guidedAlertId = /\/api\/alerts\/([^/]+)\/workflows/.exec(String(url))?.[1] ?? ''
        return new Response(JSON.stringify({
          workflowId: 'wf-guided-default', alertId: guidedAlertId, status: 'RUNNING',
          diagnosis: null, approval: null, workOrder: null, errors: [], eventSequence: 1, riskReasons: [],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({
        knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
        analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: true,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch

    try {
      const wrapper = mount(OperationsWorkbench, {
        props: { active: true, initialView: 'workflow' },
        global: { stubs: operatorStubs },
      })
      await settleCapabilities()
      await wrapper.get('[data-select-alternate-alert]').trigger('click')

      await wrapper.setProps({
        launchRequest: { requestId: 81, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow' },
      })
      await new Promise((resolve) => setTimeout(resolve, 10))

      expect(guidedAlertId).toBe('ALT-TEMP-001')
      wrapper.unmount()
    } finally {
      globalThis.EventSource = originalEventSource
    }
  })

  it('reports a fresh guided workflow failure instead of reusing an older workflow', async () => {
    let postCount = 0
    const originalEventSource = globalThis.EventSource
    globalThis.EventSource = class {
      onerror: ((event: Event) => void) | null = null
      constructor(_url: string | URL) {}
      addEventListener(): void {}
      close(): void {}
    } as unknown as typeof EventSource
    globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        postCount += 1
        if (postCount === 2) throw new Error('second workflow launch failed')
        return new Response(JSON.stringify({
          workflowId: 'wf-guided-first', alertId: 'ALT-TEMP-001', status: 'RUNNING',
          diagnosis: null, approval: null, workOrder: null, errors: [], eventSequence: 1, riskReasons: [],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({
        knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
        analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: true,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch

    try {
      const wrapper = mount(OperationsWorkbench, {
        props: {
          active: true,
          initialView: 'workflow',
          launchRequest: { requestId: 51, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow' },
        },
        global: { stubs: operatorStubs },
      })
      await settleCapabilities()
      await wrapper.setProps({
        launchRequest: { requestId: 52, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow' },
      })
      await settleCapabilities()

      const status = wrapper.get('[role="status"]')
      expect(status.attributes('data-state')).toBe('failed')
      expect(status.text()).toContain('second workflow launch failed')
      wrapper.unmount()
    } finally {
      globalThis.EventSource = originalEventSource
    }
  })

  it('keeps the newer guided status when a stale async update arrives', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: {
        active: true,
        initialView: 'collaboration',
        launchRequest: { requestId: 41, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' },
      },
      global: { stubs: { ...operatorStubs, ExpertCollaborationPage: guidedStatusStub } },
    })
    await settleCapabilities()
    const scene = wrapper.getComponent(guidedStatusStub)

    scene.vm.sendLaunchStatus({ requestId: 41, state: 'preparing', message: '新请求准备中' })
    await nextTick()
    scene.vm.sendLaunchStatus({ requestId: 40, state: 'failed', message: '旧请求失败' })
    await nextTick()

    const status = wrapper.find('[role="status"]')
    expect(status.exists()).toBe(true)
    expect(status.text()).toBe('新请求准备中')
    expect(wrapper.text()).not.toContain('旧请求失败')
    wrapper.unmount()
  })

  it('clears a failed guided status when a newer request arrives before it updates', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: {
        active: true,
        initialView: 'collaboration',
        launchRequest: { requestId: 71, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' },
      },
      global: { stubs: { ...operatorStubs, ExpertCollaborationPage: guidedStatusStub } },
    })
    await settleCapabilities()
    const scene = wrapper.getComponent(guidedStatusStub)
    scene.vm.sendLaunchStatus({ requestId: 71, state: 'failed', message: '请求 A 失败' })
    await nextTick()
    expect(wrapper.get('[role="status"]').text()).toBe('请求 A 失败重新开始')

    await wrapper.setProps({
      launchRequest: { requestId: 72, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' },
    })

    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('[data-workbench-action="retry-guided-launch"]').exists()).toBe(false)
  })

  it('clears a failed guided status when manual entry removes the launch request', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: {
        active: true,
        initialView: 'collaboration',
        launchRequest: { requestId: 73, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' },
      },
      global: { stubs: { ...operatorStubs, ExpertCollaborationPage: guidedStatusStub } },
    })
    await settleCapabilities()
    wrapper.getComponent(guidedStatusStub).vm.sendLaunchStatus({ requestId: 73, state: 'failed', message: '引导启动失败' })
    await nextTick()

    await wrapper.setProps({ launchRequest: null })

    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('[data-workbench-action="retry-guided-launch"]').exists()).toBe(false)
  })

  it('offers a retry for the current failed guided launch', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: {
        active: true,
        initialView: 'collaboration',
        launchRequest: { requestId: 42, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' },
      },
      global: { stubs: { ...operatorStubs, ExpertCollaborationPage: guidedStatusStub } },
    })
    await settleCapabilities()

    wrapper.getComponent(guidedStatusStub).vm.sendLaunchStatus({
      requestId: 42,
      state: 'failed',
      message: '专家协作启动失败',
    })
    await nextTick()
    const retry = wrapper.find('[data-workbench-action="retry-guided-launch"]')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')

    expect(wrapper.emitted('retry-guided-launch')?.[0]).toEqual(['EXPERT_COLLABORATION'])
    wrapper.unmount()
  })

  it('retries only a visible failure for the current launch request', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: {
        initialView: 'collaboration',
        launchRequest: { requestId: 43, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' },
      },
      global: { stubs: { ...operatorStubs, ExpertCollaborationPage: guidedStatusStub } },
    })
    await settleCapabilities()

    await wrapper.setProps({
      launchRequest: { requestId: 44, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' },
    })
    wrapper.getComponent(ImmersiveWorkbenchShell).vm.$emit('retry-guided-launch')
    await nextTick()

    expect(wrapper.emitted('retry-guided-launch')).toBeUndefined()

    wrapper.getComponent(guidedStatusStub).vm.sendLaunchStatus({ requestId: 44, state: 'failed', message: '请求 B 失败' })
    await nextTick()
    await wrapper.get('[data-workbench-action="retry-guided-launch"]').trigger('click')

    expect(wrapper.emitted('retry-guided-launch')?.[0]).toEqual(['EXPERT_COLLABORATION'])
  })
})
