<script setup lang="ts">
import { ArrowLeft, DocumentChecked } from '@element-plus/icons-vue'
import type { CustomerAnalysisContext } from '../../types/customer'

defineProps<{ context: CustomerAnalysisContext | null }>()
defineEmits<{ back: [] }>()

function formatTime(value: string, timezone: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}
</script>

<template>
  <div class="customer-analysis-hero">
    <div class="customer-analysis-hero__heading">
      <button type="button" class="customer-analysis-hero__back" data-analysis-shell-back @click="$emit('back')">
        <ArrowLeft aria-hidden="true" /> 返回
      </button>
      <div v-if="context">
        <span>{{ context.buildingId }} · {{ context.buildingName }}</span>
        <h1 id="customer-analysis-title">{{ context.title }}</h1>
        <p>
          异常依据周期：{{ formatTime(context.anomalyWindow.from, context.anomalyWindow.timezone) }}
          至 {{ formatTime(context.anomalyWindow.to, context.anomalyWindow.timezone) }}
          （{{ context.anomalyWindow.timezone }}）
        </p>
      </div>
      <div v-else>
        <h1 id="customer-analysis-title">运营分析</h1>
        <p>请先从园区总览选择需要分析的楼宇</p>
      </div>
      <strong
        v-if="context"
        class="customer-analysis-hero__priority"
        :class="{ 'is-high': context.priority === '高' }"
      >{{ context.priority }}优先级</strong>
    </div>
    <div class="customer-analysis-hero__actions">
      <button type="button" disabled title="事件与工单将在人工确认页面开放">
        <DocumentChecked aria-hidden="true" /> 进入人工确认
      </button>
      <span>报告暂不支持自选分析内容</span>
    </div>
  </div>
</template>
