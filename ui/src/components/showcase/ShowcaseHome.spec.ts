import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ShowcaseHome from './ShowcaseHome.vue'

describe('ShowcaseHome customer shell', () => {
  it('defaults to the customer overview and keeps later issues non-interactive', () => {
    const wrapper = mount(ShowcaseHome, {
      props: { active: false },
      global: { stubs: { ParkOverview: { template: '<main data-park-overview />' } } },
    })

    expect(wrapper.find('[data-customer-shell]').exists()).toBe(true)
    expect(wrapper.get('[data-customer-nav="overview"]').attributes('aria-current')).toBe('page')
    for (const page of ['analysis', 'work-orders', 'reports', 'assistant']) {
      const item = wrapper.get(`[data-customer-nav="${page}"]`)
      expect(item.element.tagName).toBe('SPAN')
      expect(item.attributes('aria-disabled')).toBe('true')
    }
  })

  it('uses the existing App event to enter the long-lived internal workbench', async () => {
    const wrapper = mount(ShowcaseHome, {
      props: { active: false },
      global: { stubs: { ParkOverview: { template: '<main data-park-overview />' } } },
    })

    await wrapper.get('[data-enter-workbench]').trigger('click')

    expect(wrapper.emitted('enter-workbench')).toEqual([[]])
  })
})
