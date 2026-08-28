<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { DisplayPayload } from '../../types/execution'

const props = defineProps<{
  chart: DisplayPayload | null
  columns: string[]
  rows: Array<Array<unknown>>
}>()

type ChartSpec = Extract<DisplayPayload, { payloadType: 'CHART' }>
const container = ref<HTMLElement | null>(null)
let instance: echarts.ECharts | null = null

const spec = computed<ChartSpec | null>(() =>
  props.chart?.payloadType === 'CHART' ? props.chart : null,
)
const isKpi = computed(() => spec.value?.type === 'KPI')
const kpiValue = computed(() => {
  const field = spec.value?.yFields[0]
  const index = field ? props.columns.indexOf(field) : -1
  return index < 0 || !props.rows[0] ? '' : String(props.rows[0][index] ?? '-')
})

/** Renders only backend ChartSpec + real result rows; never invents data. */
function render(): void {
  const current = spec.value
  if (!container.value) return
  if (!current || current.type === 'TABLE' || current.type === 'KPI') {
    instance?.dispose()
    instance = null
    container.value.style.display = 'none'
    return
  }
  container.value.style.display = 'block'
  try {
    instance = instance ?? echarts.init(container.value)
    instance.setOption(buildOption(current), true)
  } catch {
    // jsdom/test environments have no canvas support; rendering is skipped gracefully.
  }
}

function buildOption(current: ChartSpec): Record<string, unknown> {
  switch (current.type) {
    case 'HEATMAP':
      return heatmapOption(current)
    case 'CALENDAR_HEATMAP':
      return calendarOption(current)
    case 'SCATTER':
      return scatterOption(current)
    case 'GAUGE':
      return gaugeOption(current)
    case 'MAP':
      return mapOption(current)
    default:
      return categoryOption(current)
  }
}

function categoryOption(current: ChartSpec): Record<string, unknown> {
  const xIndex = props.columns.indexOf(current.xField)
  const { categories, series } = buildCategorySeries(current, xIndex)
  const horizontal = current.orientation === 'HORIZONTAL'
  const axes = horizontal
    ? { xAxis: { type: 'value' }, yAxis: { type: 'category', data: categories } }
    : { xAxis: { type: 'category', data: categories }, yAxis: { type: 'value' } }
  return { tooltip: {}, ...axes, series: series.map((item) => ({
    ...item,
    ...(current.type === 'STACKED_BAR' ? { stack: 'total' } : {}),
  })) }
}

function buildCategorySeries(current: ChartSpec, xIndex: number) {
  const yIndexes = current.yFields
    .map((field) => ({ field, index: props.columns.indexOf(field) }))
    .filter(({ index }) => index >= 0)
  const seriesIndex = current.seriesField === '-' ? -1 : props.columns.indexOf(current.seriesField)
  const categories: string[] = []
  const categoryIndex = new Map<string, number>()
  const grouped = new Map<string, number[]>()

  props.rows.forEach((row) => {
    const category = String(row[xIndex] ?? '')
    if (!categoryIndex.has(category)) {
      categoryIndex.set(category, categories.length)
      categories.push(category)
    }
    const categoryPosition = categoryIndex.get(category)!
    const seriesName = seriesIndex < 0 ? '' : String(row[seriesIndex] ?? '')
    yIndexes.forEach(({ field, index }) => {
      const key = seriesName ? `${field} (${seriesName})` : field
      const values = grouped.get(key) ?? []
      while (values.length < categories.length) values.push(NaN)
      values[categoryPosition] = cellValue(row[index])
      grouped.set(key, values)
    })
  })
  return {
    categories,
    series: [...grouped.entries()].map(([name, data]) => ({
      name,
      type: current.type === 'LINE' ? 'line' : 'bar',
      data: data.map((value) => Number.isNaN(value) ? null : value),
    })),
  }
}

function heatmapOption(current: ChartSpec): Record<string, unknown> {
  const xIndex = props.columns.indexOf(current.xField)
  const yIndex = props.columns.indexOf(current.seriesField)
  const valueIndex = props.columns.indexOf(current.yFields[0])
  const xCategories = [...new Set(props.rows.map((row) => String(row[xIndex] ?? '')))]
  const yCategories = [...new Set(props.rows.map((row) => String(row[yIndex] ?? '')))]
  return {
    tooltip: {},
    xAxis: { type: 'category', data: xCategories },
    yAxis: { type: 'category', data: yCategories },
    visualMap: { min: 0, max: Math.max(...props.rows.map((row) => Number(row[valueIndex]) || 0), 0), calculable: true },
    series: [{ type: 'heatmap', data: props.rows.map((row) => [
      xCategories.indexOf(String(row[xIndex] ?? '')),
      yCategories.indexOf(String(row[yIndex] ?? '')),
      cellValue(row[valueIndex]),
    ]) }],
  }
}

function calendarOption(current: ChartSpec): Record<string, unknown> {
  const dateIndex = props.columns.indexOf(current.xField)
  const valueIndex = props.columns.indexOf(current.yFields[0])
  const dates = [...new Set(props.rows
    .map((row) => String(row[dateIndex] ?? ''))
    .filter((date) => date.length > 0))].sort()
  return {
    tooltip: {},
    visualMap: { min: 0, max: Math.max(...props.rows.map((row) => Number(row[valueIndex]) || 0), 0), calculable: true },
    calendar: { range: dates.length > 1 ? [dates[0], dates[dates.length - 1]] : (dates[0] ?? '') },
    series: [{ type: 'heatmap', coordinateSystem: 'calendar', data: props.rows.map((row) => [
      String(row[dateIndex] ?? ''), cellValue(row[valueIndex]),
    ]) }],
  }
}

function scatterOption(current: ChartSpec): Record<string, unknown> {
  const xIndex = props.columns.indexOf(current.xField)
  const yIndex = props.columns.indexOf(current.yFields[0])
  return {
    tooltip: {},
    xAxis: { type: 'value', name: current.xField },
    yAxis: { type: 'value', name: current.yFields[0] },
    series: [{ type: 'scatter', data: props.rows.map((row) => [cellValue(row[xIndex]), cellValue(row[yIndex])]) }],
  }
}

function gaugeOption(current: ChartSpec): Record<string, unknown> {
  const valueIndex = props.columns.indexOf(current.yFields[0])
  return {
    series: [{ type: 'gauge', max: current.targetValue ?? 100,
      data: [{ value: cellValue(props.rows[0]?.[valueIndex]), name: current.title }] }],
  }
}

function mapOption(current: ChartSpec): Record<string, unknown> {
  const xField = current.coordinateXField ?? ''
  const yField = current.coordinateYField ?? ''
  const xIndex = props.columns.indexOf(xField)
  const yIndex = props.columns.indexOf(yField)
  const valueIndex = props.columns.indexOf(current.yFields[0])
  return {
    tooltip: {},
    xAxis: { type: 'value', name: xField },
    yAxis: { type: 'value', name: yField },
    series: [{ type: 'scatter', symbolSize: 22, data: props.rows.map((row) => [
      cellValue(row[xIndex]), cellValue(row[yIndex]), cellValue(row[valueIndex]),
    ]) }],
  }
}

function cellValue(raw: unknown): number {
  const value = Number(raw)
  return Number.isFinite(value) ? value : NaN
}

watch(() => [props.chart, props.columns, props.rows], render)
onMounted(render)
onBeforeUnmount(() => instance?.dispose())
</script>

<template>
  <div v-if="isKpi" class="analytics-kpi" data-testid="analytics-kpi" :aria-label="spec?.title">
    <span>{{ spec?.title }}</span>
    <strong>{{ kpiValue }}<small v-if="spec?.unit"> {{ spec.unit }}</small></strong>
  </div>
  <div v-else ref="container" class="analytics-chart" aria-label="分析结果图表"></div>
</template>

<style scoped>
.analytics-chart { width: 100%; height: 320px; }
.analytics-kpi { display: grid; gap: 8px; padding: 18px; background: #f0f7f5; border-left: 4px solid #087f78; }
.analytics-kpi span { color: #5b817b; font-size: 11px; }
.analytics-kpi strong { color: #174f4a; font-size: 28px; }
.analytics-kpi small { font-size: 12px; font-weight: 500; }
</style>
