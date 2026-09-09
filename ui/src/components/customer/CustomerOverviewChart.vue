<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

export interface CustomerChartDatum {
  name: string
  value: number | null
}

const props = defineProps<{
  kind: 'line' | 'donut'
  data: CustomerChartDatum[]
  unit?: string
  label: string
}>()

const root = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

const option = computed<echarts.EChartsOption>(() => {
  if (props.kind === 'line') {
    return {
      animation: false,
      grid: { left: 42, right: 14, top: 18, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value) => value == null ? '缺失' : `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} ${props.unit ?? ''}`.trim(),
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: props.data.map((item) => item.name),
        axisLine: { lineStyle: { color: '#dce8f5' } },
        axisLabel: { color: '#7890ad', fontSize: 10 },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: '#7890ad', fontSize: 10 },
        splitLine: { lineStyle: { color: '#e8f0f8', type: 'dashed' } },
      },
      series: [{
        name: '实际观测',
        type: 'line',
        smooth: 0.28,
        connectNulls: false,
        showSymbol: false,
        data: props.data.map((item) => item.value),
        lineStyle: { color: '#4386f5', width: 2.5 },
        itemStyle: { color: '#4386f5' },
        areaStyle: { color: 'rgba(67, 134, 245, .10)' },
      }],
    }
  }
  const donutData = props.data
    .filter((item) => item.value != null && item.value > 0)
    .map((item) => ({ name: item.name, value: item.value as number }))
  return {
    animation: false,
    tooltip: {
      trigger: 'item',
      valueFormatter: (value) => `${Number(value).toLocaleString('zh-CN')} ${props.unit ?? ''}`.trim(),
    },
    legend: {
      type: 'scroll',
      orient: 'vertical',
      right: 4,
      top: 'center',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#597392', fontSize: 10 },
    },
    series: [{
      type: 'pie',
      radius: ['48%', '72%'],
      center: ['31%', '52%'],
      label: { show: false },
      color: ['#4386f5', '#28bf9b', '#f7b84b', '#8271ea', '#6bc2dc'],
      data: donutData,
    }],
  }
})

function render(): void {
  if (!root.value || props.data.length === 0) return
  chart ??= echarts.init(root.value)
  chart.setOption(option.value, true)
}

onMounted(() => {
  render()
  if (root.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(root.value)
  }
})
watch(option, render, { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="root" class="customer-chart" role="img" :aria-label="label"></div>
</template>
