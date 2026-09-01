import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import OperationsBoard from './OperationsBoard.vue'

describe('OperationsBoard', () => {
  it('exposes read-only parking, energy and space questions without static metrics', async () => {
    const wrapper = mount(OperationsBoard)

    expect(wrapper.get('[data-operations-board]').text()).toContain('停车与交通')
    expect(wrapper.get('[data-operations-board]').text()).toContain('能耗与空间')
    expect(wrapper.findAll('[data-board-question]')).toHaveLength(5)
    expect(wrapper.text()).toContain('过去5天各停车区域停车利用率')
    expect(wrapper.text()).toContain('过去5天各楼宇平均占用人数')
    expect(wrapper.text()).not.toMatch(/\d+\s*(kWh|辆|%|人)/)

    await wrapper.find('[data-board-question][data-question="过去5天各停车区域停车利用率"]').trigger('click')
    expect(wrapper.emitted('open-analysis')).toEqual([['过去5天各停车区域停车利用率']])
  })
})
