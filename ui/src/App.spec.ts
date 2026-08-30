import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, onMounted, onUnmounted, type PropType } from 'vue'
import App from './App.vue'
import type { WorkbenchView } from './components/OperationsWorkbench.vue'

const workbenchStub = defineComponent({
  name: 'OperationsWorkbench',
  props: {
    initialView: {
      type: String as PropType<WorkbenchView>,
      required: false,
    },
  },
  emits: ['back-to-showcase'],
  setup(props, { emit }) {
    return () => h('section', { 'data-testid': 'operations-workbench' }, [
      h('span', { 'data-testid': 'initial-view' }, props.initialView),
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
      initialView: {
        type: String as PropType<WorkbenchView>,
        required: false,
      },
    },
    emits: ['back-to-showcase'],
    setup(props, { emit }) {
      onMounted(() => {
        lifecycle.mounts += 1
      })
      onUnmounted(() => {
        lifecycle.unmounts += 1
      })

      return () => h('section', { 'data-testid': 'operations-workbench' }, [
        h('span', { 'data-testid': 'initial-view' }, props.initialView),
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
  it('starts on semantic showcase placeholder markup without mounting the workbench', () => {
    const wrapper = mountApp()

    const showcase = wrapper.get('[data-showcase-surface="placeholder"]')
    expect(showcase.element.tagName).toBe('MAIN')
    expect(showcase.attributes('aria-labelledby')).toBe('showcase-placeholder-title')
    expect(wrapper.find('[data-testid="operations-workbench"]').exists()).toBe(false)
  })

  it('opens the extracted workbench with the requested operator view', async () => {
    const wrapper = mountApp()

    await wrapper.get('[data-showcase-open-workbench="analytics"]').trigger('click')

    expect(wrapper.find('[data-testid="operations-workbench"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="initial-view"]').text()).toBe('analytics')
  })

  it('keeps the same workbench instance mounted and hidden after returning to showcase', async () => {
    const lifecycle = { mounts: 0, unmounts: 0 }
    const wrapper = mountApp(createLifecycleTrackedWorkbench(lifecycle))

    await wrapper.get('[data-showcase-open-workbench="workflow"]').trigger('click')
    expect(lifecycle).toEqual({ mounts: 1, unmounts: 0 })

    await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')

    expect(wrapper.find('[data-showcase-surface="placeholder"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="operations-workbench"]').isVisible()).toBe(false)
    expect(lifecycle).toEqual({ mounts: 1, unmounts: 0 })
  })
})
