import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, type PropType } from 'vue'
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

function mountApp() {
  return mount(App, {
    global: {
      stubs: appStubs,
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

  it('returns to the showcase placeholder when the workbench emits back-to-showcase', async () => {
    const wrapper = mountApp()

    await wrapper.get('[data-showcase-open-workbench="workflow"]').trigger('click')
    await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')

    expect(wrapper.find('[data-testid="operations-workbench"]').exists()).toBe(false)
    expect(wrapper.find('[data-showcase-surface="placeholder"]').exists()).toBe(true)
  })
})
