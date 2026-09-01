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

  it('projects recognized values from nested tool results into customer-facing details', () => {
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
          conclusion: '工具结果: {"meterId":"MTR-2","reading":{"currentKwh":138.2,"baselineKwh":120,"varianceRatio":0.1517}}',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.text()).toContain('当前能耗')
    expect(wrapper.text()).toContain('138.2 kWh')
    expect(wrapper.text()).toContain('基线能耗')
    expect(wrapper.text()).toContain('120 kWh')
    expect(wrapper.text()).toContain('偏差率')
    expect(wrapper.text()).toContain('15.17%')
    wrapper.unmount()
  })

  it('keeps nested device identifiers in the device context', () => {
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
          conclusion: '工具结果: {"deviceId":"DEV-1","device":{"id":"device-record-1","status":"ONLINE"}}',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.text()).toContain('设备DEV-1')
    expect(wrapper.text()).toContain('状态在线')
    expect(wrapper.text()).not.toContain('知识文档')
    wrapper.unmount()
  })

  it('deduplicates repeated identifiers inside one tool result', () => {
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
          conclusion: '工具结果: {"meterId":"MTR-2","reading":{"meterId":"MTR-2","currentKwh":138.2}}',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.findAll('.expert-details > div').filter((item) => item.text() === '电表MTR-2')).toHaveLength(1)
    wrapper.unmount()
  })

  it('projects alert fields returned by alert lookup tools', () => {
    const wrapper = mount(ExpertCard, {
      props: {
        domain: 'DEVICE',
        plan: {
          normalizedQuestion: 'q',
          selectedDomains: ['DEVICE'],
          assignments: { DEVICE: '查询设备 DEV-1 的关联告警' },
          selectionReason: 'device alerts',
        },
        finding: {
          domain: 'DEVICE',
          status: 'SUPPORTED',
          conclusion: '工具结果: {"alertId":"ALT-1","alert":{"classification":"POWER","riskHint":"HIGH","summary":"HVAC overload","occurredAt":"2026-08-25T00:00:00Z"}}',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.text()).toContain('告警类型')
    expect(wrapper.text()).toContain('功率')
    expect(wrapper.text()).toContain('风险提示')
    expect(wrapper.text()).toContain('高风险')
    expect(wrapper.text()).toContain('告警摘要')
    expect(wrapper.text()).toContain('HVAC overload')
    wrapper.unmount()
  })

  it('includes recognized details from every cited tool result', () => {
    const wrapper = mount(ExpertCard, {
      props: {
        domain: 'ENERGY',
        plan: {
          normalizedQuestion: 'q',
          selectedDomains: ['ENERGY'],
          assignments: { ENERGY: '比较电表 DEV-ENERGY-001 和 DEV-ENERGY-002 的能耗' },
          selectionReason: 'energy comparison',
        },
        finding: {
          domain: 'ENERGY',
          status: 'SUPPORTED',
          conclusion: '已验证工具结果: {"meterId":"DEV-ENERGY-001","reading":{"currentKwh":138.2}}；已验证工具结果: {"meterId":"DEV-ENERGY-002","reading":{"currentKwh":101.5}}',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.text()).toContain('DEV-ENERGY-001')
    expect(wrapper.text()).toContain('DEV-ENERGY-002')
    expect(wrapper.text()).toContain('138.2 kWh')
    expect(wrapper.text()).toContain('101.5 kWh')
    wrapper.unmount()
  })

  it('renders safe knowledge metadata instead of dropping the matched playbook', () => {
    const wrapper = mount(ExpertCard, {
      props: {
        domain: 'ENERGY',
        plan: {
          normalizedQuestion: 'q',
          selectedDomains: ['ENERGY'],
          assignments: { ENERGY: '检索能耗处置知识' },
          selectionReason: 'knowledge',
        },
        finding: {
          domain: 'ENERGY',
          status: 'SUPPORTED',
          conclusion: '工具结果: {"query":"能耗","documents":[{"id":"KD-1","domain":"ALERT_OPERATIONS","title":"Energy playbook","tags":["energy","baseline"],"updatedAt":"2026-08-25T00:00:00Z"}]}',
          evidenceRefs: [],
          confidence: 0.92,
          nextChecks: [],
        },
      },
    })

    expect(wrapper.text()).toContain('能耗')
    expect(wrapper.text()).toContain('KD-1')
    expect(wrapper.text()).toContain('Energy playbook')
    expect(wrapper.text()).toContain('告警运维')
    expect(wrapper.text()).toContain('energy、baseline')
    expect(wrapper.text()).toContain('2026/8/25')
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
