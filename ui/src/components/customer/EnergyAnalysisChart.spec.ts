import { describe, expect, it } from 'vitest'
import { buildEnergyAnalysisOption } from './EnergyAnalysisChart.vue'

describe('EnergyAnalysisChart', () => {
  it('keeps missing values as breaks and shades only real 22:00-06:00 park-time buckets', () => {
    const option = buildEnergyAnalysisOption([
      { timestamp: '2026-09-08T14:00:00Z', actual: 12, baseline: 10 },
      { timestamp: '2026-09-08T15:00:00Z', actual: null, baseline: 10 },
      { timestamp: '2026-09-09T00:00:00Z', actual: 9, baseline: null },
    ], 'Asia/Shanghai', 'kWh') as {
      series: Array<{
        data: Array<number | null>
        connectNulls: boolean
        markArea?: { data: Array<unknown> }
      }>
    }

    expect(option.series[0]!.data).toEqual([12, null, 9])
    expect(option.series[1]!.data).toEqual([10, 10, null])
    expect(option.series.every((item) => item.connectNulls === false)).toBe(true)
    expect(option.series[0]!.markArea?.data).toHaveLength(2)
  })
})
