<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { DisplayPayload } from '../../types/execution'

const props = defineProps<{
  chart: DisplayPayload | null
  columns: string[]
  rows: Array<Array<unknown>>
}>()

const container = ref<HTMLElement | null>(null)
let instance: echarts.ECharts | null = null

/** Renders only backend ChartSpec + real result rows; never invents data. */
function render(): void {
  const spec = props.chart
  if (!container.value) return
  if (!spec || spec.payloadType !== 'CHART' || (spec.type !== 'LINE' && spec.type !== 'BAR')) {
    instance?.dispose()
    instance = null
    container.value.style.display = 'none'
    return
  }
  container.value.style.display = 'block'
  const xIndex = props.columns.indexOf(spec.xField)
  if (xIndex < 0) {
    container.value.style.display = 'none'
    return
  }
  const { categories, series } = buildSeries(spec, xIndex)
  try {
    instance = instance ?? echarts.init(container.value)
    instance.setOption({ tooltip: {}, xAxis: { type: 'category', data: categories }, yAxis: { type: 'value' }, series }, true)
  } catch {
    // jsdom/test environments have no canvas support; rendering is skipped gracefully.
  }
}

/**
 * Null cells stay null so ECharts renders a gap instead of plotting an
 * invented zero. When the backend specifies a seriesField, rows are grouped
 * by that dimension: one series per (yField × series value) combination,
 * matching the public chart contract.
 */
function buildSeries(spec: Extract<DisplayPayload, { payloadType: 'CHART' }>, xIndex: number) {
  const yIndexes: Array<{ field: string; index: number }> = spec.yFields
    .map((field) => ({ field, index: props.columns.indexOf(field) }))
  const sIndex = spec.seriesField ? props.columns.indexOf(spec.seriesField) : -1

  if (!spec.seriesField || sIndex < 0) {
    return {
      categories: props.rows.map((row) => String(row[xIndex] ?? '')),
      series: yIndexes
        .filter(({ index }) => index >= 0)
        .map(({ field, index }) => ({
          name: field,
          type: spec.type === 'BAR' ? ('bar' as const) : ('line' as const),
          data: props.rows.map((row) => cellValue(row[index])),
        })),
    }
  }

  const categories: string[] = []
  const categoryIndex = new Map<string, number>()
  const seriesValues = new Set<string>()
  // points[yIndex][seriesValue][categoryIndex] = cell value
  const points = new Map<number, Map<string, Map<number, number | null>>>()

  props.rows.forEach((row) => {
    const category = String(row[xIndex] ?? '')
    if (!categoryIndex.has(category)) {
      categoryIndex.set(category, categories.length)
      categories.push(category)
    }
    const c = categoryIndex.get(category)!
    const seriesValue = String(row[sIndex] ?? '')
    seriesValues.add(seriesValue)
    yIndexes.forEach(({ index }, yi) => {
      if (index < 0) return
      let bySeries = points.get(yi)
      if (!bySeries) points.set(yi, (bySeries = new Map()))
      let byCategory = bySeries.get(seriesValue)
      if (!byCategory) bySeries.set(seriesValue, (byCategory = new Map()))
      byCategory.set(c, cellValue(row[index]))
    })
  })

  const series: Array<{ name: string; type: 'bar' | 'line'; data: Array<number | null> }> = []
  for (const [yi, bySeries] of points) {
    const { field } = yIndexes[yi]
    for (const seriesValue of [...seriesValues].sort()) {
      const byCategory = bySeries.get(seriesValue)
      if (!byCategory) continue
      series.push({
        name: `${field} (${seriesValue})`,
        type: spec.type === 'BAR' ? 'bar' : 'line',
        data: categories.map((_, c) => byCategory.get(c) ?? null),
      })
    }
  }
  return { categories, series }
}

function cellValue(raw: unknown): number | null {
  return raw === null || raw === undefined ? null : Number(raw)
}

watch(() => [props.chart, props.columns, props.rows], render)
onMounted(render)
onBeforeUnmount(() => instance?.dispose())
</script>

<template>
  <div ref="container" class="analytics-chart" aria-label="分析结果图表"></div>
</template>

<style scoped>
.analytics-chart {
  width: 100%;
  height: 320px;
}
</style>
