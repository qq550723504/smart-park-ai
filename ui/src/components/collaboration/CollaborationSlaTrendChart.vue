<script lang="ts">
import type { CollaborationSlaSnapshot } from '../../types/collaborationCenter'

export function buildSlaTrendOption(snapshots: CollaborationSlaSnapshot[]): Record<string, unknown> {
  const series = [
    { name: '已超时', key: 'overdue' as const },
    { name: '即将到期', key: 'dueSoon' as const },
    { name: '正常', key: 'onTrack' as const },
  ].map(({ name, key }) => ({
    name,
    type: 'line',
    smooth: true,
    data: snapshots.map(snapshot => [snapshot.capturedAt, snapshot[key]]),
  }))
  return {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', min: 0, minInterval: 1 },
    series,
  }
}
</script>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { withDarkTheme } from '../analytics/AnalyticsChart.vue'
import type { CollaborationSlaSnapshot } from '../../types/collaborationCenter'

const props = defineProps<{ snapshots: CollaborationSlaSnapshot[] }>()
const container = ref<HTMLElement | null>(null)
let instance: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

function disconnectResizeObserver(): void {
  resizeObserver?.disconnect()
  resizeObserver = null
}

function render(): void {
  if (!container.value) return
  if (props.snapshots.length === 0) {
    disconnectResizeObserver()
    instance?.dispose()
    instance = null
    return
  }
  if (typeof ResizeObserver === 'function' && !resizeObserver) {
    resizeObserver = new ResizeObserver(() => instance?.resize())
    resizeObserver.observe(container.value)
  }
  try {
    instance = instance ?? echarts.init(container.value)
    instance.setOption(withDarkTheme(buildSlaTrendOption(props.snapshots)), true)
  } catch {
    // jsdom/test environments have no canvas support; rendering is skipped gracefully.
  }
}

watch(() => props.snapshots, render, { deep: true })
watch(container, render)
onMounted(render)
onBeforeUnmount(() => {
  disconnectResizeObserver()
  instance?.dispose()
  instance = null
})
</script>

<template>
  <p v-if="snapshots.length === 0" class="collaboration-sla-trend__empty" role="status">正在采样会话 SLA 数据…</p>
  <div v-else ref="container" class="collaboration-sla-trend__chart" aria-label="本次会话 SLA 趋势图"></div>
</template>

<style scoped>
.collaboration-sla-trend__chart { width: 100%; height: 300px; }
.collaboration-sla-trend__empty { margin: 0; padding: 28px 16px; color: var(--showcase-muted); text-align: center; }
</style>
