<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { ExecutionEvent } from '../../types/execution'
import ExecutionEventCard from './ExecutionEventCard.vue'

const props = withDefaults(
  defineProps<{
    events: ExecutionEvent[]
    status: 'idle' | 'streaming' | 'completed' | 'failed' | 'interrupted'
    error?: string
    autoScroll?: boolean
  }>(),
  { error: '', autoScroll: true },
)

const statusLabels = {
  idle: '等待执行',
  streaming: '实时同步中',
  completed: '已完成',
  failed: '已失败',
  interrupted: '已中断',
} as const

const listElement = ref<HTMLElement | null>(null)

watch(
  () => props.events.length,
  async () => {
    if (!props.autoScroll) return
    await nextTick()
    listElement.value?.scrollTo({ top: listElement.value.scrollHeight })
  },
)
</script>

<template>
  <aside class="trace-rail panel" :aria-label="`统一执行轨迹栏 · ${statusLabels[props.status]}`">
    <div class="section-heading">
      <div>
        <span class="eyebrow">统一执行轨迹</span>
        <h2>真实后端事件</h2>
      </div>
      <el-tag :type="props.status === 'failed' ? 'danger' : props.status === 'completed' ? 'success' : 'info'" effect="plain" round>
        {{ statusLabels[props.status] }}
      </el-tag>
    </div>

    <p v-if="props.error" class="trace-error" data-testid="trace-error">{{ props.error }}</p>

    <p v-if="props.events.length === 0 && !props.error" class="trace-empty">暂无执行轨迹。启动任一场景后，这里将按时间显示真实的后端事件。</p>

    <ol v-else ref="listElement" class="trace-list" data-testid="trace-list">
      <ExecutionEventCard v-for="event in props.events" :key="event.eventId" :event="event" />
    </ol>
  </aside>
</template>

<style scoped src="./execution-rail.css"></style>
