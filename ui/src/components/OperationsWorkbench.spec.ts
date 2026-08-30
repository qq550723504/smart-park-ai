import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, onMounted, onUnmounted } from 'vue'
import OperationsWorkbench from './OperationsWorkbench.vue'

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

const operatorStubs = {
  OperationsAnalysisPage: analysisStub,
  ExpertCollaborationPage: true,
  AlertSelector: true,
  WorkflowGraph: true,
  DemoConsole: true,
  EventTimeline: true,
  CustomerServiceConsole: true,
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

  it('keeps every scenario page as a direct workspace sibling of the global rail', async () => {
    const wrapper = mount(OperationsWorkbench, {
      props: { initialView: 'workflow' },
      global: { stubs: operatorStubs },
    })

    await settleCapabilities()

    const children = Array.from((wrapper.find('.workspace').element as HTMLElement).children)
    expect(children.filter((child) => child.tagName === 'MAIN' && child.classList.contains('main-content'))).toHaveLength(5)
    const rail = children.find((child) => child.classList.contains('global-rail'))
    expect(rail).toBeTruthy()
    expect(rail?.parentElement).toBe(wrapper.find('.workspace').element)
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

    const labels = wrapper.findAll('.view-switch button').map((button) => button.text())
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
    const retry = wrapper.find('[data-testid="retry-guided-launch"]')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')

    expect(wrapper.emitted('retry-guided-launch')?.[0]).toEqual(['EXPERT_COLLABORATION'])
    wrapper.unmount()
  })
})
