<script setup lang="ts">
import type { WorkflowEvent } from '../types/workflow'
import { eventSummaryLabel, workflowEventLabel, workflowNodeLabel } from '../utils/labels'

defineProps<{ events: WorkflowEvent[] }>()

function time(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}
</script>

<template>
  <section class="panel timeline-panel">
    <div class="section-heading compact">
      <div><span class="eyebrow">事件流</span><h2>实时事件</h2></div>
      <span class="live-indicator"><i></i>实时</span>
    </div>
    <div v-if="events.length" class="timeline">
      <article v-for="event in [...events].reverse()" :key="event.eventId" class="event-item" :class="event.type.toLowerCase()">
        <span class="event-icon"></span>
        <div class="event-body">
          <div><strong>{{ workflowEventLabel(event.type) }}</strong><time>{{ time(event.timestamp) }}</time></div>
          <p>{{ workflowNodeLabel(event.node) }}</p>
          <small>事件 #{{ event.sequence }} · {{ eventSummaryLabel(event.redactedSummary) }}</small>
        </div>
      </article>
    </div>
    <el-empty v-else description="启动工作流后，事件将在这里实时出现" :image-size="80" />
  </section>
</template>
