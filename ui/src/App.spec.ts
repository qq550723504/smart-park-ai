import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, onMounted, onUnmounted, type PropType } from 'vue'
import App from './App.vue'
import ShowcaseHome from './components/showcase/ShowcaseHome.vue'
import OperationsWorkbench from './components/OperationsWorkbench.vue'
import type { ShowcaseScenario } from './services/workflowApi'
import type { ScenarioLaunchRequest, WorkbenchView } from './types/workbench'

const showcaseStub = defineComponent({
  name: 'ShowcaseHome',
  props: {
    active: {
      type: Boolean,
      default: true,
    },
  },
  emits: ['start-scenario', 'enter-workbench'],
  setup(_, { emit }) {
    return () => h('main', { 'data-testid': 'showcase-home' }, [
      h('button', {
        type: 'button',
        'data-testid': 'enter-workbench',
        onClick: () => emit('enter-workbench'),
      }, 'Enter workbench'),
    ])
  },
})

const workbenchStub = defineComponent({
  name: 'OperationsWorkbench',
  props: {
    active: {
      type: Boolean,
      default: true,
    },
    initialView: {
      type: String as PropType<WorkbenchView>,
      required: false,
    },
    launchRequest: {
      type: Object as PropType<ScenarioLaunchRequest | null>,
      default: null,
    },
  },
  emits: ['back-to-showcase', 'retry-guided-launch'],
  setup(props, { emit }) {
    return () => h('section', { 'data-testid': 'operations-workbench' }, [
      h('span', { 'data-testid': 'initial-view' }, props.initialView),
      h('span', { 'data-testid': 'workbench-active' }, String(props.active)),
      h('button', {
        type: 'button',
        'data-testid': 'back-to-showcase',
        onClick: () => emit('back-to-showcase'),
      }, 'Back to showcase'),
    ])
  },
})

function createLifecycleTrackedWorkbench(lifecycle: { mounts: number; unmounts: number }) {
  return defineComponent({
    name: 'OperationsWorkbench',
    props: {
      active: {
        type: Boolean,
        default: true,
      },
      initialView: {
        type: String as PropType<WorkbenchView>,
        required: false,
      },
      launchRequest: {
        type: Object as PropType<ScenarioLaunchRequest | null>,
        default: null,
      },
    },
    emits: ['back-to-showcase', 'retry-guided-launch'],
    setup(props, { emit }) {
      onMounted(() => {
        lifecycle.mounts += 1
      })
      onUnmounted(() => {
        lifecycle.unmounts += 1
      })

      return () => h('section', { 'data-testid': 'operations-workbench' }, [
        h('span', { 'data-testid': 'initial-view' }, props.initialView),
        h('span', { 'data-testid': 'workbench-active' }, String(props.active)),
        h('button', {
          type: 'button',
          'data-testid': 'back-to-showcase',
          onClick: () => emit('back-to-showcase'),
        }, 'Back to showcase'),
      ])
    },
  })
}

const appStubs = {
  ShowcaseHome: showcaseStub,
  OperationsWorkbench: workbenchStub,
  OperationsAnalysisPage: true,
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

function mountApp(operationsWorkbench = workbenchStub) {
  return mount(App, {
    global: {
      stubs: {
        ...appStubs,
        OperationsWorkbench: operationsWorkbench,
      },
    },
  })
}

describe('App surface coordinator', () => {
  it('creates a guided one-shot request for a showcase scenario', async () => {
    const wrapper = mountApp()

    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'EXPERT_COLLABORATION')
    await nextTick()

    expect(wrapper.getComponent(OperationsWorkbench).props('launchRequest')).toMatchObject({
      requestId: 1,
      mode: 'guided',
      scenarioId: 'EXPERT_COLLABORATION',
      view: 'collaboration',
    })
  })

  it('clears guided launch state for the manual workbench entry', async () => {
    const wrapper = mountApp()

    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'ALERT_WORKFLOW')
    await nextTick()
    await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')
    wrapper.getComponent(ShowcaseHome).vm.$emit('enter-workbench')
    await nextTick()

    expect(wrapper.getComponent(OperationsWorkbench).props('initialView')).toBe('workflow')
    expect(wrapper.getComponent(OperationsWorkbench).props('launchRequest')).toBeNull()
  })

  it('issues a fresh request id after returning to the showcase', async () => {
    const wrapper = mountApp()

    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'OPERATIONS_ANALYSIS')
    await nextTick()
    const first = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest
    await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')
    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'OPERATIONS_ANALYSIS')
    await nextTick()
    const second = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest

    expect(second.requestId).toBeGreaterThan(first.requestId)
  })

  it('retries a failed guided launch with a fresh request id', async () => {
    const wrapper = mountApp()
    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'OPERATIONS_ANALYSIS')
    await nextTick()
    const first = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest
    wrapper.getComponent(OperationsWorkbench).vm.$emit('retry-guided-launch', 'OPERATIONS_ANALYSIS')
    await nextTick()
    const retried = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest
    expect(retried.requestId).toBeGreaterThan(first.requestId)
    expect(retried.scenarioId).toBe('OPERATIONS_ANALYSIS')
  })

  it('starts on the showcase home without mounting the workbench', () => {
    const wrapper = mountApp()

    const showcase = wrapper.get('[data-surface="showcase"]')
    expect(showcase.element.tagName).toBe('MAIN')
    expect(showcase.isVisible()).toBe(true)
    expect(wrapper.find('[data-testid="operations-workbench"]').exists()).toBe(false)
  })

  it.each([
    ['ALERT_WORKFLOW', 'workflow'],
    ['EXPERT_COLLABORATION', 'collaboration'],
    ['OPERATIONS_ANALYSIS', 'analytics'],
    ['VOICE_ASSISTANT', 'voice'],
  ] as Array<[ShowcaseScenario['id'], WorkbenchView]>)(
    'opens %s in the cached workbench %s view',
    async (scenarioId, expectedView) => {
      const wrapper = mountApp()

      expect(wrapper.get('[data-surface="showcase"]').isVisible()).toBe(true)
      wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', scenarioId)
      await nextTick()

      expect(wrapper.getComponent(OperationsWorkbench).props('initialView')).toBe(expectedView)
      expect(wrapper.get('[data-surface="workbench"]').isVisible()).toBe(true)
    },
  )

  it('opens the workbench entry action on the workflow view by default', async () => {
    const wrapper = mountApp()

    wrapper.getComponent(ShowcaseHome).vm.$emit('enter-workbench')
    await nextTick()

    expect(wrapper.getComponent(OperationsWorkbench).props('initialView')).toBe('workflow')
    expect(wrapper.get('[data-surface="workbench"]').isVisible()).toBe(true)
  })

  it('keeps the same workbench instance mounted and hidden after returning to showcase', async () => {
    const lifecycle = { mounts: 0, unmounts: 0 }
    const wrapper = mountApp(createLifecycleTrackedWorkbench(lifecycle))

    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'EXPERT_COLLABORATION')
    await nextTick()
    expect(lifecycle).toEqual({ mounts: 1, unmounts: 0 })

    await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')

    expect(wrapper.get('[data-surface="showcase"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="operations-workbench"]').isVisible()).toBe(false)
    expect(lifecycle).toEqual({ mounts: 1, unmounts: 0 })

    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'ALERT_WORKFLOW')
    await nextTick()

    expect(wrapper.getComponent(OperationsWorkbench).props('initialView')).toBe('workflow')
    expect(lifecycle).toEqual({ mounts: 1, unmounts: 0 })
  })

  it('deactivates the cached workbench whenever the showcase surface is visible', async () => {
    const wrapper = mountApp()

    wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'VOICE_ASSISTANT')
    await nextTick()
    expect(wrapper.getComponent(OperationsWorkbench).props('active')).toBe(true)

    await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')
    expect(wrapper.getComponent(OperationsWorkbench).props('active')).toBe(false)
  })

  it('moves focus to the destination surface after each surface transition', async () => {
    const wrapper = mount(App, {
      attachTo: document.body,
      global: {
        stubs: appStubs,
      },
    })

    try {
      wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'EXPERT_COLLABORATION')
      await flushPromises()

      const workbench = wrapper.get('[data-surface="workbench"]')
      expect(workbench.attributes('tabindex')).toBe('-1')
      expect(document.activeElement).toBe(workbench.element)

      await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')
      await flushPromises()

      const showcase = wrapper.get('[data-surface="showcase"]')
      expect(showcase.attributes('tabindex')).toBe('-1')
      expect(document.activeElement).toBe(showcase.element)
    } finally {
      wrapper.unmount()
    }
  })
})
