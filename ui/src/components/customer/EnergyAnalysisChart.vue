<script lang="ts">
import type { EChartsOption } from 'echarts'

export interface EnergyAnalysisPoint {
  timestamp: string
  actual: number | null
  baseline: number | null
}

function localHour(timestamp: string, timezone: string): number | null {
  const date = new Date(timestamp)
  if (Number.isNaN(date.valueOf())) return null
  const part = new Intl.DateTimeFormat('en-GB', {
    timeZone: timezone,
    hour: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date).find((item) => item.type === 'hour')?.value
  const hour = Number(part)
  return Number.isFinite(hour) ? hour : null
}

function pointLabel(timestamp: string, timezone: string): string {
  const date = new Date(timestamp)
  if (Number.isNaN(date.valueOf())) return timestamp
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function buildEnergyAnalysisOption(
  points: EnergyAnalysisPoint[],
  timezone: string,
  unit: string,
): EChartsOption {
  const labels = points.map((point) => pointLabel(point.timestamp, timezone))
  const nightAreas: Array<[{ xAxis: string }, { xAxis: string }]> = []
  points.forEach((point, index) => {
    const hour = localHour(point.timestamp, timezone)
    if (hour == null || (hour >= 6 && hour < 22)) return
    const next = labels[Math.min(index + 1, labels.length - 1)]
    nightAreas.push([{ xAxis: labels[index]! }, { xAxis: next! }])
  })
  return {
    animation: false,
    grid: { left: 54, right: 24, top: 46, bottom: 42 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) => value == null ? '缺失' : `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })} ${unit}`,
    },
    legend: {
      right: 12,
      top: 4,
      itemWidth: 18,
      itemHeight: 8,
      textStyle: { color: '#55718f', fontSize: 11 },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: labels,
      axisLine: { lineStyle: { color: '#d9e5f2' } },
      axisLabel: { color: '#738ba6', fontSize: 10, hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      name: `用电量（${unit}）`,
      nameTextStyle: { color: '#7289a3', padding: [0, 0, 8, 0] },
      axisLabel: { color: '#738ba6', fontSize: 10 },
      splitLine: { lineStyle: { color: '#e6eef7', type: 'dashed' } },
    },
    series: [
      {
        name: '实际用电',
        type: 'line',
        smooth: 0.22,
        connectNulls: false,
        showSymbol: false,
        data: points.map((point) => point.actual),
        lineStyle: { color: '#397cf5', width: 2.5 },
        itemStyle: { color: '#397cf5' },
        areaStyle: { color: 'rgba(57,124,245,.10)' },
        markArea: nightAreas.length ? {
          silent: true,
          itemStyle: { color: 'rgba(241, 111, 145, .08)' },
          label: { show: false },
          data: nightAreas,
        } : undefined,
      },
      {
        name: '基线用电',
        type: 'line',
        smooth: 0.18,
        connectNulls: false,
        showSymbol: false,
        data: points.map((point) => point.baseline),
        lineStyle: { color: '#2abf9c', width: 2, type: 'dashed' },
        itemStyle: { color: '#2abf9c' },
      },
    ],
  }
}
</script>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps<{
  points: EnergyAnalysisPoint[]
  timezone: string
  unit: string
}>()

const root = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

function render(): void {
  if (!root.value || props.points.length === 0) return
  chart ??= echarts.init(root.value)
  chart.setOption(buildEnergyAnalysisOption(props.points, props.timezone, props.unit), true)
}

onMounted(() => {
  render()
  if (root.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(root.value)
  }
})
watch(() => [props.points, props.timezone, props.unit], render, { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div
    ref="root"
    class="energy-analysis-chart"
    role="img"
    aria-label="所选楼宇小时实际用电与基线用电对比，夜间时段为二十二点至次日六点，缺失数据保留断点"
  ></div>
</template>
