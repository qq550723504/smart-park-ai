<script setup lang="ts">
import type { WorkflowEvent } from '../types/workflow'

defineProps<{ events: WorkflowEvent[] }>()

const nodeNames: Record<string, string> = {
  workflow: '工作流', classifyAlert: '告警分诊', collectParkContext: '收集上下文',
  retrieveKnowledge: '检索知识', diagnoseAlert: 'AI 诊断', riskGate: '风险判断',
  humanApproval: '人工审批', createWorkOrder: '创建工单', summarizeResult: '汇总结果',
}
const typeNames: Record<string, string> = {
  STARTED: '已启动', NODE_STARTED: '节点开始', TOOL_CALLED: '调用工具', NODE_COMPLETED: '节点完成',
  PAUSED: '等待审批', RESUMED: '恢复执行', COMPLETED: '已完成', FAILED: '执行失败',
}
function time(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}
</script>

<template>
  <section class="panel timeline-panel">
    <div class="section-heading compact">
      <div><span class="eyebrow">EVENT STREAM</span><h2>实时事件</h2></div>
      <span class="live-indicator"><i></i> SSE</span>
    </div>
    <div v-if="events.length" class="timeline">
      <article v-for="event in [...events].reverse()" :key="event.eventId" class="event-item" :class="event.type.toLowerCase()">
        <span class="event-icon"></span>
        <div class="event-body">
          <div><strong>{{ typeNames[event.type] ?? event.type }}</strong><time>{{ time(event.timestamp) }}</time></div>
          <p>{{ nodeNames[event.node] ?? event.node }}</p>
          <small>事件 #{{ event.sequence }} · {{ event.redactedSummary }}</small>
        </div>
      </article>
    </div>
    <el-empty v-else description="启动工作流后，事件将在这里实时出现" :image-size="80" />
  </section>
</template>
