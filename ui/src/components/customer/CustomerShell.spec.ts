import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CustomerShell from './CustomerShell.vue'

describe('CustomerShell', () => {
  it('opens overview and analysis while keeping later customer navigation non-interactive', async () => {
    const wrapper = mount(CustomerShell)
    const plannedNavigation = wrapper.findAll('.customer-shell__nav [aria-disabled="true"]')

    expect(plannedNavigation).toHaveLength(3)
    expect(plannedNavigation.every((item) => item.element.tagName === 'SPAN')).toBe(true)
    expect(plannedNavigation.every((item) => item.attributes('title') === '功能暂未开放')).toBe(true)
    expect(wrapper.text()).not.toMatch(/#(?:70|71|72|73)|后续 Issue|联调项/)
    expect(wrapper.get('[data-customer-nav="analysis"]').element.tagName).toBe('BUTTON')

    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['analysis']])
  })

  it('marks the active customer page and updates the skip link', () => {
    const wrapper = mount(CustomerShell, { props: { activePage: 'analysis' } })

    expect(wrapper.get('[data-customer-nav="analysis"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-customer-nav="overview"]').attributes('aria-current')).toBeUndefined()
    expect(wrapper.get('.customer-shell__skip').attributes('href')).toBe('#customer-analysis-main')
    expect(wrapper.find('.customer-shell__hero').exists()).toBe(false)
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
