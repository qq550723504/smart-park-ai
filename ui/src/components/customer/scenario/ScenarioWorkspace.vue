<script setup lang="ts">
import { computed } from 'vue'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'
import { useB2NightEnergyScenario } from '../../../scenario/b2-night-energy/store'
import type { CustomerPage } from '../../../types/customer'
import type { ScenarioVariantId } from '../../../types/scenarioEnergy'
import ScenarioOverviewPanel from './ScenarioOverviewPanel.vue'
import ScenarioAnalysisPanel from './ScenarioAnalysisPanel.vue'
import ScenarioWorkOrderPanel from './ScenarioWorkOrderPanel.vue'
import ScenarioReportPanel from './ScenarioReportPanel.vue'
import './scenario-surface.css'

const props = defineProps<{ activePage: CustomerPage }>()
const emit = defineEmits<{ exit: [] }>()
const store = useB2NightEnergyScenario()
const snapshot = store.snapshot

const variants: Array<{ id: ScenarioVariantId; label: string }> = [
  { id: 'NORMAL', label: '完整主故事' },
  { id: 'NO_ACTION', label: '保持现状' },
  { id: 'PARTIAL_DATA', label: '缺失关键小时' },
  { id: 'LOST_CREATE_RESPONSE', label: '建单响应丢失' },
]
const activeVariant = computed(() => snapshot.value.variant)

async function selectVariant(variant: ScenarioVariantId): Promise<void> {
  if (store.busy.value) return
  await store.setVariant(variant)
}
</script>

<template>
  <div class="scenario-workspace" data-scenario-workspace>
    <div class="scenario-workspace__bar">
      <p data-scenario-banner>
        <WarningFilled aria-hidden="true" />
        {{ snapshot.banner }} · {{ snapshot.scenarioId }} v{{ snapshot.scenarioVersion }} ·
        <strong>非真实业务数据</strong>，不代表现场模型运行结果
      </p>
      <div class="scenario-workspace__controls">
        <label>
          演示变体
          <select :value="activeVariant" data-scenario-variant @change="selectVariant(($event.target as HTMLSelectElement).value as ScenarioVariantId)">
            <option v-for="variant in variants" :key="variant.id" :value="variant.id">{{ variant.label }}</option>
          </select>
        </label>
        <button type="button" class="scenario-button" :disabled="!store.canReset.value" data-scenario-reset @click="store.reset()">
          <Refresh aria-hidden="true" /> 重开本场景
        </button>
        <button type="button" class="scenario-button" data-exit-scenario @click="emit('exit')">
          退出演示场景
        </button>
      </div>
    </div>

    <ScenarioOverviewPanel v-show="props.activePage === 'overview'" />
    <ScenarioAnalysisPanel v-show="props.activePage === 'analysis'" />
    <ScenarioWorkOrderPanel v-show="props.activePage === 'work-orders'" />
    <ScenarioReportPanel v-show="props.activePage === 'reports'" />
  </div>
</template>
