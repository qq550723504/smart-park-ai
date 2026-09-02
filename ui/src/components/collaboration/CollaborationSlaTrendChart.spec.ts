import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import CollaborationSlaTrendChart, { buildSlaTrendOption } from './CollaborationSlaTrendChart.vue'
import type { CollaborationSlaSnapshot } from '../../types/collaborationCenter'

const { setOption, dispose, resize, init } = vi.hoisted(() => {
  const setOption = vi.fn()
  const dispose = vi.fn()
  const resize = vi.fn()
  return { setOption, dispose, resize, init: vi.fn(() => ({ setOption, dispose, resize })) }
})

vi.mock('echarts', () => ({ init }))

const snapshots: CollaborationSlaSnapshot[] = [
  { capturedAt: '2026-09-02T10:00:00Z', total: 4, overdue: 1, dueSoon: 1, onTrack: 2, completed: 0, notApplicable: 0 },
  { capturedAt: '2026-09-02T10:00:30Z', total: 4, overdue: 2, dueSoon: 1, onTrack: 1, completed: 0, notApplicable: 0 },
]

describe('CollaborationSlaTrendChart', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('ResizeObserver', undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('builds time-series lines from server snapshots', () => {
    const option = buildSlaTrendOption(snapshots)

    expect(option.xAxis).toMatchObject({ type: 'time' })
    expect(option.series).toEqual([
      { name: '已超时', type: 'line', smooth: true, data: [['2026-09-02T10:00:00Z', 1], ['2026-09-02T10:00:30Z', 2]] },
      { name: '即将到期', type: 'line', smooth: true, data: [['2026-09-02T10:00:00Z', 1], ['2026-09-02T10:00:30Z', 1]] },
      { name: '正常', type: 'line', smooth: true, data: [['2026-09-02T10:00:00Z', 2], ['2026-09-02T10:00:30Z', 1]] },
    ])
  })

  it('renders server snapshots through the shared dark ECharts theme', () => {
    const wrapper = mount(CollaborationSlaTrendChart, { props: { snapshots } })

    expect(init).toHaveBeenCalledOnce()
    expect(setOption).toHaveBeenCalledOnce()
    expect(setOption.mock.calls[0][0]).toMatchObject({
      xAxis: { type: 'time' },
      backgroundColor: 'transparent',
    })
    wrapper.unmount()
    expect(dispose).toHaveBeenCalledOnce()
  })

  it('shows a sampling empty state without inventing chart points', () => {
    const wrapper = mount(CollaborationSlaTrendChart, { props: { snapshots: [] } })

    expect(wrapper.text()).toContain('正在采样')
    expect(init).not.toHaveBeenCalled()
  })
})
