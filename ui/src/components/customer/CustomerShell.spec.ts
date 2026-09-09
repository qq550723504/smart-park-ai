import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CustomerShell from './CustomerShell.vue'

describe('CustomerShell', () => {
  it('keeps unavailable customer navigation non-interactive without exposing delivery metadata', () => {
    const wrapper = mount(CustomerShell)
    const plannedNavigation = wrapper.findAll('.customer-shell__nav [aria-disabled="true"]')

    expect(plannedNavigation).toHaveLength(4)
    expect(plannedNavigation.every((item) => item.element.tagName === 'SPAN')).toBe(true)
    expect(plannedNavigation.every((item) => item.attributes('title') === '功能暂未开放')).toBe(true)
    expect(wrapper.text()).not.toMatch(/#(?:70|71|72|73)|后续 Issue|联调项/)
  })

  it('serves responsive WebP artwork with a PNG fallback', () => {
    const wrapper = mount(CustomerShell)
    const source = wrapper.get('.customer-shell__hero source[type="image/webp"]')
    const fallback = wrapper.get('.customer-shell__hero img')

    expect(source.attributes('srcset')).toContain('960w')
    expect(source.attributes('srcset')).toContain('2172w')
    expect(source.attributes('sizes')).toContain('98vw')
    expect(fallback.attributes('src')).toContain('campus-banner.png')
    expect(fallback.attributes('alt')).toBe('')
  })

  it('retains the internal workbench handoff', async () => {
    const wrapper = mount(CustomerShell)

    await wrapper.get('[data-enter-workbench]').trigger('click')

    expect(wrapper.emitted('enter-workbench')).toHaveLength(1)
  })
})
