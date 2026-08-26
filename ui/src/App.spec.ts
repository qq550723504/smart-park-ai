import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, onMounted, onUnmounted } from 'vue'
import App from './App.vue'

describe('App view persistence', () => {
  let originalFetch: typeof fetch

  beforeEach(() => {
    originalFetch = globalThis.fetch
    globalThis.fetch = (async () => new Response(JSON.stringify({
      knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
      analyticsEnabled: true, collaborationEnabled: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('keeps the analysis page mounted while switching to another view', async () => {
    let mounts = 0
    let unmounts = 0
    const analysisStub = defineComponent({
      setup() {
        onMounted(() => { mounts += 1 })
        onUnmounted(() => { unmounts += 1 })
        return () => null
      },
    })
    const wrapper = mount(App, {
      global: {
        stubs: {
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
        },
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    const analyticsButton = wrapper.findAll('.view-switch button')
      .find((button) => button.text() === '运营分析')
    const workflowButton = wrapper.findAll('.view-switch button')
      .find((button) => button.text() === '告警工作流')
    await analyticsButton!.trigger('click')
    await nextTick()
    await workflowButton!.trigger('click')
    await nextTick()

    expect(mounts).toBe(1)
    expect(unmounts).toBe(0)
    wrapper.unmount()
    expect(unmounts).toBe(1)
  })

  it('keeps every scenario page as a direct workspace sibling of the global rail', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
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
        },
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()

    const children = Array.from((wrapper.find('.workspace').element as HTMLElement).children)
    expect(children.filter((child) => child.classList.contains('main-content'))).toHaveLength(4)
    expect(children.some((child) => child.classList.contains('global-rail'))).toBe(true)
    wrapper.unmount()
  })
})
