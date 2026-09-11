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
  comparisonData?: CustomerChartDatum[]
  unit?: string
  label: string
}>()

const root = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

const option = computed<echarts.EChartsOption>(() => {
  if (props.kind === 'line') {
    const hasComparison = props.comparisonData?.some((item) => item.value != null) ?? false
    return {
      animation: false,
      grid: { left: 42, right: 14, top: hasComparison ? 34 : 18, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value) => value == null ? '缺失' : `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} ${props.unit ?? ''}`.trim(),
      },
      legend: hasComparison ? {
        top: 0,
        right: 4,
        itemWidth: 10,
        itemHeight: 6,
        textStyle: { color: '#597392', fontSize: 10 },
      } : undefined,
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
      series: [
        {
          name: '近 24 小时',
          type: 'line',
          smooth: 0.28,
          connectNulls: false,
          showSymbol: false,
          data: props.data.map((item) => item.value),
          lineStyle: { color: '#4386f5', width: 2.5 },
          itemStyle: { color: '#4386f5' },
          areaStyle: { color: 'rgba(67, 134, 245, .10)' },
        },
        ...(hasComparison ? [{
          name: '前 24 小时',
          type: 'line' as const,
          smooth: 0.28,
          connectNulls: false,
          showSymbol: false,
          data: props.comparisonData!.map((item) => item.value),
          lineStyle: { color: '#35bd9a', width: 2 },
          itemStyle: { color: '#35bd9a' },
        }] : []),
      ],
    }
  }
  const donutData = props.data
    .filter((item) => item.value != null && item.value > 0)
    .map((item) => ({ name: item.name, value: item.value as number }))
  const total = donutData.reduce((sum, item) => sum + item.value, 0)
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
      formatter: (name: string) => {
        const value = donutData.find((item) => item.name === name)?.value ?? 0
        const percentage = total > 0 ? Math.round(value / total * 100) : 0
        return `${name}  ${percentage}%`
      },
    },
    title: {
      text: total.toLocaleString('zh-CN', { maximumFractionDigits: 1 }),
      subtext: props.unit ?? '',
      left: '31%',
      top: '34%',
      textAlign: 'center',
      textStyle: { color: '#173f77', fontSize: 16, fontWeight: 700 },
      subtextStyle: { color: '#7890ad', fontSize: 10, lineHeight: 14 },
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
