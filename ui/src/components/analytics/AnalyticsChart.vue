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
  const categories = props.rows.map((row) => String(row[xIndex] ?? ''))
  const series = spec.yFields
        .map((field) => {
          const index = props.columns.indexOf(field)
          return index < 0 ? null : {
            name: field,
            type: spec.type === 'BAR' ? 'bar' as const : 'line' as const,
            data: props.rows.map((row) => Number(row[index])),
          }
        })
        .filter(Boolean)
  try {
    instance = instance ?? echarts.init(container.value)
    instance.setOption({ tooltip: {}, xAxis: { type: 'category', data: categories }, yAxis: { type: 'value' }, series }, true)
  } catch {
    // jsdom/test environments have no canvas support; rendering is skipped gracefully.
  }
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
