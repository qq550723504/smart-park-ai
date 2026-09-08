import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import OperationsBoard from './OperationsBoard.vue'

describe('OperationsBoard', () => {
  it('exposes read-only parking, energy and space questions without static metrics', async () => {
    const wrapper = mount(OperationsBoard, { props: { role: 'ADMIN' } })

    expect(wrapper.get('[data-operations-board]').text()).toContain('停车与交通')
    expect(wrapper.get('[data-operations-board]').text()).toContain('能耗与空间')
    expect(wrapper.findAll('[data-board-question]')).toHaveLength(14)
    expect(wrapper.text()).toContain('过去5天各停车区域停车利用率')
    expect(wrapper.text()).toContain('过去5天各楼宇平均占用人数')
    expect(wrapper.text()).not.toMatch(/\d+\s*(kWh|辆|%|人)/)

    await wrapper.find('[data-board-question][data-question="过去5天各停车区域停车利用率"]').trigger('click')
    expect(wrapper.emitted('open-analysis')).toEqual([['过去5天各停车区域停车利用率']])
  })

  it('exposes alert and device health questions as read-only analysis entries', async () => {
    const wrapper = mount(OperationsBoard, { props: { role: 'ADMIN' } })

    expect(wrapper.get('[data-operations-board]').text()).toContain('告警与设备')
    expect(wrapper.findAll('[data-board-question]')).toHaveLength(14)
    expect(wrapper.text()).toContain('过去7天告警数量')
    expect(wrapper.text()).toContain('过去7天高风险告警数量')
    expect(wrapper.text()).toContain('各楼宇离线设备数')
    expect(wrapper.text()).toContain('各设备类型离线设备数')
    expect(wrapper.text()).toContain('过去7天各风险等级告警数量')
    expect(wrapper.text()).toContain('过去7天各类别告警数量')
    expect(wrapper.text()).toContain('过去7天各状态告警数量')
    expect(wrapper.text()).toContain('过去7天各楼宇告警数量排行')
    expect(wrapper.text()).toContain('过去7天各楼宇高风险告警排行')
    expect(wrapper.text()).not.toMatch(/\d+\s*(kWh|辆|%|人|条|台)/)

    await wrapper.find('[data-board-question][data-question="过去7天告警数量"]').trigger('click')
    expect(wrapper.emitted('open-analysis')).toEqual([['过去7天告警数量']])
  })

  it('keeps unsupported cockpit functions explicit and non-executable', () => {
    const wrapper = mount(OperationsBoard, { props: { role: 'ADMIN' } })

    expect(wrapper.get('[data-cockpit-feature="energy-trend"]').attributes('data-feature-state')).toBe('NOT_READY')
    expect(wrapper.get('[data-cockpit-feature="telemetry"]').text()).toContain('数据源未接入')
    expect(wrapper.get('[data-cockpit-feature="run-all-agents"]').text()).toContain('组合编排 API')
    expect(wrapper.get('[data-cockpit-feature="report-history"]').text()).toContain('尚无列表或下载 API')
  })

  it('routes only available Agent entries to existing workbench views', async () => {
    const wrapper = mount(OperationsBoard, {
      props: { role: 'ADMIN', collaborationAvailable: true, securityIncidentAvailable: false },
    })

    await wrapper.get('[data-agent-entry="collaboration"]').trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([['collaboration']])
    expect(wrapper.get('[data-agent-entry="security"]').attributes('disabled')).toBeDefined()
  })
})
