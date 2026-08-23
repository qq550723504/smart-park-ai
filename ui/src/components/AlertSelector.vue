<script setup lang="ts">
import type { DemoAlert } from '../types/workflow'

defineProps<{ alerts: DemoAlert[]; selectedId: string; loading: boolean }>()
const emit = defineEmits<{ select: [alertId: string]; start: [] }>()
</script>

<template>
  <section class="panel alert-panel">
    <div class="section-heading">
      <div>
        <span class="eyebrow">告警收件箱</span>
        <h2>选择演示告警</h2>
      </div>
      <span class="count-badge">{{ alerts.length }} 条待处理</span>
    </div>
    <div class="alert-list">
      <button
        v-for="alert in alerts"
        :key="alert.id"
        class="alert-card"
        :class="{ active: alert.id === selectedId }"
        type="button"
        @click="emit('select', alert.id)"
      >
        <span class="risk-mark" :class="alert.risk.toLowerCase()"></span>
        <span class="alert-content">
          <span class="alert-title-row">
            <strong>{{ alert.title }}</strong>
            <el-tag :type="alert.risk === 'HIGH' ? 'danger' : 'success'" size="small" effect="dark">
              {{ alert.risk === 'HIGH' ? '高风险' : '低风险' }}
            </el-tag>
          </span>
          <span class="alert-meta">{{ alert.id }} · {{ alert.building }}</span>
          <span class="alert-description">{{ alert.description }}</span>
        </span>
      </button>
    </div>
    <el-button class="start-button" type="primary" size="large" :loading="loading" @click="emit('start')">
      启动 AI 诊断工作流
    </el-button>
    <p class="helper">演示告警来自后端模拟数据，启动需要配置可用的 DashScope 模型。</p>
  </section>
</template>
