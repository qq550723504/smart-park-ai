import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ShowcaseHome from '../../showcase/ShowcaseHome.vue'
import { resetB2NightEnergyScenarioSingleton } from '../../../scenario/b2-night-energy/store'

function stubFetch(): void {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({
    knowledgeMode: 'mock',
    customerAnswerMode: 'mock',
    vectorStore: 'none',
    analyticsEnabled: true,
    collaborationEnabled: false,
    voiceEnabled: false,
    securityIncidentEnabled: false,
  }), { status: 200 }))))
}

async function mountScenario() {
  const wrapper = mount(ShowcaseHome, {
    props: { active: true },
    attachTo: document.body,
    global: {
      stubs: {
        CustomerAssistantPanel: { template: '<aside data-assistant-stub />' },
      },
    },
  })
  await wrapper.get('[data-enter-scenario]').trigger('click')
  await flushPromises()
  return wrapper
}

describe('B2 scenario customer integration', () => {
  beforeEach(() => {
    resetB2NightEnergyScenarioSingleton()
    sessionStorage.clear()
    stubFetch()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('renders the scenario workspace instead of the online pages when entered', async () => {
    const wrapper = await mountScenario()
    expect(wrapper.find('[data-scenario-workspace]').exists()).toBe(true)
    expect(wrapper.find('[data-park-overview]').exists()).toBe(false)
    expect(wrapper.get('[data-scenario-banner]').text()).toContain('模拟场景')
    wrapper.unmount()
  })

  it('runs the recommended story end to end through the shared run', async () => {
    const wrapper = await mountScenario()

    // Overview: patrol builds one event.
    await wrapper.get('[data-scenario-start-patrol]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-patrol-summary]').text()).toContain('2 项关注')

    // Analysis: choose the recommended plan and confirm.
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-run-assessment]').trigger('click')
    await flushPromises()
    const planCard = wrapper.get('[data-scenario-plan="SCN-PLAN-PUBLIC-HVAC"]')
    await planCard.get('button').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-confirm-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-confirmed]').text()).toContain('80')

    // Work orders: future result stays hidden until explicit verification.
    await wrapper.get('[data-customer-nav="work-orders"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-order-id]').text()).toBe('SCN-WO-B2-001-001')
    expect(wrapper.find('[data-scenario-followup-hidden]').exists()).toBe(true)
    expect(wrapper.find('[data-scenario-verified]').exists()).toBe(false)

    await wrapper.get('[data-scenario-take-order]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-apply-plan]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-scenario-followup-hidden]').exists()).toBe(true)

    await wrapper.get('[data-scenario-verify]').trigger('click')
    await flushPromises()
    const verified = wrapper.get('[data-scenario-verified]').text()
    expect(verified).toContain('72')
    expect(verified).toContain('22.8')

    // Reports: the snapshot freezes the verified state.
    await wrapper.get('[data-customer-nav="reports"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-generate-report]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-report-view]').text()).toContain('SCN-RPT-B2-001-01')
    wrapper.unmount()
  })

  it('keeps the run after a page switch and a reload', async () => {
    const wrapper = await mountScenario()
    await wrapper.get('[data-scenario-start-patrol]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-customer-nav="overview"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-stage]').text()).toBe('巡检已完成')
    wrapper.unmount()

    resetB2NightEnergyScenarioSingleton()
    const reloaded = mount(ShowcaseHome, {
      props: { active: true },
      global: { stubs: { CustomerAssistantPanel: { template: '<aside />' } } },
    })
    await flushPromises()
    expect(reloaded.find('[data-scenario-workspace]').exists()).toBe(true)
    expect(reloaded.get('[data-scenario-stage]').text()).toBe('巡检已完成')
    reloaded.unmount()
  })

  it('blocks confirmation and shows partial data under the PARTIAL_DATA variant', async () => {
    const wrapper = await mountScenario()
    await wrapper.get('[data-scenario-variant]').setValue('PARTIAL_DATA')
    await flushPromises()
    expect(wrapper.get('[data-scenario-partial]').text()).toContain('SCN-B2-HVAC-PUBLIC:15')

    await wrapper.get('[data-scenario-start-patrol]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-run-assessment]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-plan="SCN-PLAN-PUBLIC-HVAC"] button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-confirm-order]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('surfaces the lost-response fault and reuses the same order on retry', async () => {
    const wrapper = await mountScenario()
    await wrapper.get('[data-scenario-variant]').setValue('LOST_CREATE_RESPONSE')
    await flushPromises()
    await wrapper.get('[data-scenario-start-patrol]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-run-assessment]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-plan="SCN-PLAN-PUBLIC-HVAC"] button').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-confirm-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-error]').text()).toContain('响应丢失')
    expect(wrapper.get('[data-scenario-pending]').text()).toContain('LOST_RESPONSE')

    await wrapper.get('[data-scenario-retry-order]').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.get('[data-scenario-order-id]').text()).toBe('SCN-WO-B2-001-001')
    expect(wrapper.find('[data-scenario-pending]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('switches the chosen plan to the combined 90/1980 values', async () => {
    const wrapper = await mountScenario()
    await wrapper.get('[data-scenario-start-patrol]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-customer-nav="analysis"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-run-assessment]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-plan="SCN-PLAN-HVAC-LIGHT"] button').trigger('click')
    await flushPromises()
    await wrapper.get('[data-scenario-confirm-order]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-scenario-confirmed]').text()).toContain('90')
    wrapper.unmount()
  })
})
