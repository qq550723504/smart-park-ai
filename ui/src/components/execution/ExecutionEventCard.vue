<script setup lang="ts">
import type { ExecutionEvent } from '../../types/execution'

const props = defineProps<{ event: ExecutionEvent }>()

const statusLabels: Record<string, string> = {
  RUNNING: '进行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  INTERRUPTED: '已中断',
  NEEDS_CLARIFICATION: '待澄清',
}

const stageLabels: Record<string, string> = {
  INITIALIZATION: '初始化',
  INPUT_CAPTURE: '输入采集',
  UNDERSTANDING: '意图理解',
  PLANNING: '任务规划',
  TOOL_EXECUTION: '工具调用',
  ANALYSIS: '分析',
  SQL_VALIDATION: 'SQL 校验',
  QUERY_EXECUTION: '查询执行',
  RENDERING: '结果呈现',
  RESPONSE_DELIVERY: '回答输出',
  HUMAN_APPROVAL: '人工审批',
  COMPLETION: '完成',
  FAILURE: '失败',
}

function stageLabel(stage: string) {
  return stageLabels[stage] ?? stage
}

function statusLabel(status: string) {
  return statusLabels[status] ?? status
}

function formatRange(payload: { fromInclusive: string | null; toExclusive: string | null }) {
  try {
    const fmt = (iso: string) => new Date(iso).toLocaleString('zh-CN', { hour12: false })
    return `${fmt(payload.fromInclusive ?? '')} ~ ${fmt(payload.toExclusive ?? '')}`
  } catch {
    return `${payload.fromInclusive ?? ''} ~ ${payload.toExclusive ?? ''}`
  }
}

function timeLabel(timestamp: string) {
  try {
    return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    return timestamp
  }
}
</script>

<template>
  <article
    class="execution-event-card"
    :class="{ 'is-error': props.event.eventType === 'FAILED' || props.event.displayPayload?.payloadType === 'ERROR' }"
    role="listitem"
    :aria-label="`${props.event.actor} · ${props.event.eventType}`"
  >
    <header class="event-meta">
      <span class="event-actor">{{ props.event.actor }}</span>
      <span class="event-stage">{{ stageLabel(props.event.stage) }}</span>
      <span class="event-status" :data-status="props.event.status">{{ statusLabel(props.event.status) }}</span>
      <time class="event-time">{{ timeLabel(props.event.timestamp) }}</time>
    </header>
    <p class="event-summary">{{ props.event.safeSummary }}</p>

    <div v-if="props.event.displayPayload?.payloadType === 'TEXT'" class="payload payload-text">
      <p>{{ props.event.displayPayload.text }}<span v-if="props.event.displayPayload.partial" class="partial-mark">（部分）</span></p>
    </div>

    <div v-else-if="props.event.displayPayload?.payloadType === 'TOOL_CALL'" class="payload payload-tool">
      <strong>{{ props.event.displayPayload.toolName }}</strong>
      <dl v-if="Object.keys(props.event.displayPayload.safeArguments).length" class="tool-arguments">
        <template v-for="(value, key) in props.event.displayPayload.safeArguments" :key="key">
          <dt>{{ key }}</dt>
          <dd>{{ value }}</dd>
        </template>
      </dl>
      <span v-if="props.event.displayPayload.resultSummary" class="tool-result">{{ props.event.displayPayload.resultSummary }}</span>
    </div>

    <div v-else-if="props.event.displayPayload?.payloadType === 'EXPERT_HANDOFF'" class="payload payload-handoff">
      <strong>{{ props.event.displayPayload.domain }}</strong>
      <span>{{ props.event.displayPayload.direction }}</span>
      <span>{{ props.event.displayPayload.findingStatus }}</span>
    </div>

    <div v-else-if="props.event.displayPayload?.payloadType === 'SQL'" class="payload payload-sql">
      <code class="safe-sql">{{ props.event.displayPayload.safeSql }}</code>
      <span v-if="props.event.displayPayload.parameterNames.length" class="sql-params">
        绑定参数：{{ props.event.displayPayload.parameterNames.join('、') }}
      </span>
      <span class="sql-status">{{ props.event.displayPayload.validationStatus }}</span>
    </div>

    <div v-else-if="props.event.displayPayload?.payloadType === 'CHART'" class="payload payload-chart">
      <strong>{{ props.event.displayPayload.type }} · {{ props.event.displayPayload.title }}</strong>
      <span>x：{{ props.event.displayPayload.xField }} / y：{{ props.event.displayPayload.yFields.join(', ') }}</span>
      <span v-if="props.event.displayPayload.unit">单位：{{ props.event.displayPayload.unit }}</span>
    </div>

    <div v-else-if="props.event.displayPayload?.payloadType === 'AUDIO'" class="payload payload-audio">
      <span>语音状态：{{ props.event.displayPayload.state }}</span>
      <span v-if="props.event.displayPayload.durationMs != null">时长 {{ props.event.displayPayload.durationMs }}ms</span>
    </div>

    <div v-else-if="props.event.displayPayload?.payloadType === 'TIME_RANGE'" class="payload payload-time-range">
      <strong>时间范围（{{ props.event.displayPayload.empty ? '空' : props.event.displayPayload.status === 'PARSED' ? '已指定' : '未指定' }}）</strong>
      <span v-if="!props.event.displayPayload.empty && props.event.displayPayload.fromInclusive">
        {{ formatRange(props.event.displayPayload) }}
      </span>
      <span>{{ props.event.displayPayload.explanation }}</span>
    </div>

    <div v-else-if="props.event.displayPayload?.payloadType === 'ERROR'" class="payload payload-error">
      <strong>{{ props.event.displayPayload.errorCode }}</strong>
      <span>{{ props.event.displayPayload.safeMessage }}</span>
      <span v-if="props.event.displayPayload.retryable" class="retry-hint">可重试</span>
    </div>
  </article>
</template>

<style scoped src="./execution-rail.css"></style>
