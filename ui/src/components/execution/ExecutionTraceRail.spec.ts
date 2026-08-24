import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ExecutionTraceRail from './ExecutionTraceRail.vue'
import type { ExecutionEvent } from '../../types/execution'

function eventOf(sequence: number): ExecutionEvent {
  return {
    eventId: `event-${sequence}`,
    runId: 'run-1',
    sequence,
    timestamp: '2026-08-24T08:00:00Z',
    scenario: 'OPERATIONS_ANALYSIS',
    actor: 'analytics',
    stage: 'QUERY_EXECUTION',
    eventType: sequence === 2 ? 'COMPLETED' : 'TEXT_COMPLETED',
    status: sequence === 2 ? 'SUCCEEDED' : 'RUNNING',
    safeSummary: `第 ${sequence} 步完成`,
    displayPayload: null,
  }
}

describe('ExecutionTraceRail', () => {
  it('shows an explicit empty state before any backend events arrive', () => {
    const wrapper = mount(ExecutionTraceRail, { props: { events: [], status: 'idle' } })
    expect(wrapper.text()).toContain('暂无执行轨迹')
  })

  it('renders one card per backend event in order', () => {
    const wrapper = mount(ExecutionTraceRail, {
      props: { events: [eventOf(1), eventOf(2)], status: 'completed' },
    })
    const cards = wrapper.findAllComponents({ name: 'ExecutionEventCard' })
    expect(cards).toHaveLength(2)
    expect(wrapper.text()).toContain('第 1 步完成')
  })

  it('surfaces stream errors instead of hiding them', () => {
    const wrapper = mount(ExecutionTraceRail, {
      props: { events: [eventOf(1)], status: 'failed', error: '执行事件序号缺口：期望 2，收到 5' },
    })
    expect(wrapper.find('[data-testid="trace-error"]').text()).toContain('序号缺口')
  })

  it('labels the terminal state accessibly', () => {
    const wrapper = mount(ExecutionTraceRail, {
      props: { events: [], status: 'interrupted' },
    })
    expect(wrapper.attributes('aria-label')).toContain('已中断')
  })

  it('does not manipulate scroll position when auto-scroll is disabled', async () => {
    const wrapper = mount(ExecutionTraceRail, {
      props: { events: [eventOf(1)], status: 'streaming', autoScroll: false },
      attachTo: document.body,
    })
    const list = wrapper.find('[data-testid="trace-list"]').element as HTMLElement
    const setter = vi.fn()
    Object.defineProperty(list, 'scrollTop', { set: setter, configurable: true })
    await wrapper.setProps({ events: [eventOf(1)] })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(setter).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

import { vi } from 'vitest'
