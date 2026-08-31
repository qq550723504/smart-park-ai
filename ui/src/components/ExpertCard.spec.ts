import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ExpertCard from './ExpertCard.vue'

describe('ExpertCard', () => {
  it('preserves the business meaning of a plain-text finding conclusion', () => {
    const wrapper = mount(ExpertCard, {
      props: {
        domain: 'ENERGY',
        plan: {
          normalizedQuestion: 'q',
          selectedDomains: ['ENERGY'],
          assignments: { ENERGY: '检查电表 MTR-2 的能耗' },
          selectionReason: 'energy',
        },
        finding: {
          domain: 'ENERGY',
          status: 'SUPPORTED',
          conclusion: 'MTR-2 is normal and below baseline',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.text()).toContain('MTR-2 is normal and below baseline')
    wrapper.unmount()
  })

  it('does not expose unknown JSON fields when no customer-facing value can be mapped', () => {
    const wrapper = mount(ExpertCard, {
      props: {
        domain: 'DEVICE',
        plan: {
          normalizedQuestion: 'q',
          selectedDomains: ['DEVICE'],
          assignments: { DEVICE: '检查设备 DEV-1' },
          selectionReason: 'device',
        },
        finding: {
          domain: 'DEVICE',
          status: 'SUPPORTED',
          conclusion: '工具返回: {"internalId":"secret-123","rawState":"ACTIVE"}',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.text()).toContain('设备专家已返回核查结果')
    expect(wrapper.text()).not.toContain('internalId')
    expect(wrapper.text()).not.toContain('secret-123')
    wrapper.unmount()
  })

  it('renders structured tool evidence as a Chinese customer-facing summary', () => {
    const wrapper = mount(ExpertCard, {
      props: {
        domain: 'ENERGY',
        plan: {
          normalizedQuestion: 'q',
          selectedDomains: ['ENERGY'],
          assignments: { ENERGY: '检查电表 DEV-ENERGY-001 的能耗' },
          selectionReason: 'energy',
        },
        finding: {
          domain: 'ENERGY',
          status: 'SUPPORTED',
          conclusion: '已验证工具结果[tool:lookupEnergyConsumption#abc123]: {"meterId":"DEV-ENERGY-001","parkId":"PARK-A","buildingId":"A2","measuredAt":"2025-08-25T00:00:00Z","currentKwh":138.2,"baselineKwh":120.0,"peakDemandKw":20.3,"varianceKwh":18.2,"varianceRatio":0.1517}',
          evidenceRefs: ['tool:lookupEnergyConsumption#abc123'],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    const card = wrapper.get('[data-testid="expert-card-ENERGY"]')
    expect(card.text()).toContain('能耗专家已完成核查')
    expect(card.text()).toContain('当前能耗')
    expect(card.text()).toContain('138.2 kWh')
    expect(card.text()).toContain('基线能耗')
    expect(card.text()).not.toContain('{')
    expect(card.text()).not.toContain('buildingId')

    const evidence = wrapper.get('.evidence-list').text()
    expect(evidence).toContain('能耗查询')
    expect(evidence).not.toContain('abc123')
  })
})
