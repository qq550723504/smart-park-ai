import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CustomerShell from './CustomerShell.vue'

describe('CustomerShell', () => {
  it('opens all delivered customer pages while keeping only the assistant non-interactive', async () => {
    const wrapper = mount(CustomerShell)
    const plannedNavigation = wrapper.findAll('.customer-shell__nav [aria-disabled="true"]')

    expect(plannedNavigation).toHaveLength(1)
    expect(plannedNavigation.every((item) => item.element.tagName === 'SPAN')).toBe(true)
    expect(plannedNavigation.every((item) => item.attributes('title') === '功能暂未开放')).toBe(true)
    expect(wrapper.text()).not.toMatch(/#(?:70|71|72|73)|后续 Issue|联调项/)
    expect(wrapper.get('[data-customer-nav="analysis"]').element.tagName).toBe('BUTTON')

    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['analysis']])
    await wrapper.get('[data-customer-nav="work-orders"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['analysis'], ['work-orders']])
    await wrapper.get('[data-customer-nav="reports"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['analysis'], ['work-orders'], ['reports']])
  })

  it('marks reports as the current page and exposes its skip target', () => {
    const wrapper = mount(CustomerShell, { props: { activePage: 'reports' } })

    expect(wrapper.get('[data-customer-nav="reports"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('.customer-shell__skip').attributes('href')).toBe('#customer-reports-main')
    expect(wrapper.get('.customer-shell__hero').attributes('aria-labelledby')).toBe('customer-reports-title')
    expect(wrapper.get('.customer-shell__hero').text()).toContain('运营报告中心')
  })

  it('marks work orders as the current page and exposes its skip target', () => {
    const wrapper = mount(CustomerShell, { props: { activePage: 'work-orders' } })

    expect(wrapper.get('[data-customer-nav="work-orders"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('.customer-shell__skip').attributes('href')).toBe('#customer-work-orders-main')
    expect(wrapper.get('.customer-shell__hero').attributes('aria-labelledby')).toBe('customer-work-orders-title')
    expect(wrapper.get('.customer-shell__hero').text()).toContain('事件与工单中心')
  })

  it('marks the active customer page and updates the skip link', () => {
    const wrapper = mount(CustomerShell, { props: { activePage: 'analysis' } })

    expect(wrapper.get('[data-customer-nav="analysis"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-customer-nav="overview"]').attributes('aria-current')).toBeUndefined()
    expect(wrapper.get('.customer-shell__skip').attributes('href')).toBe('#customer-analysis-main')
    expect(wrapper.get('.customer-shell__hero').text()).toContain('运营分析')
    expect(wrapper.get('.customer-shell__hero').attributes('aria-labelledby')).toBe('customer-analysis-title')
    expect(wrapper.find('.customer-shell__hero picture').exists()).toBe(true)
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
