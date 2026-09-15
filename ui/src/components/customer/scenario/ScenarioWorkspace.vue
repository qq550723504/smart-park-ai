<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
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

// resetPolicy requires an explicit confirmation before the current run (work
// order, reports and progress) is discarded, so a stray click can never wipe
// the session run.
const resetOpen = ref(false)
const resetDialog = ref<HTMLElement | null>(null)
let resetReturnFocus: HTMLElement | null = null

async function requestReset(): Promise<void> {
  if (!store.canReset.value) return
  resetReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  resetOpen.value = true
  await nextTick()
  resetDialog.value?.focus()
}

async function closeReset(): Promise<void> {
  resetOpen.value = false
  await nextTick()
  resetReturnFocus?.focus()
  resetReturnFocus = null
}

function handleResetKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    void closeReset()
    return
  }
  if (event.key !== 'Tab' || !resetDialog.value) return
  const focusable = Array.from(resetDialog.value.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'))
  if (focusable.length === 0) {
    event.preventDefault()
    resetDialog.value.focus()
    return
  }
  const first = focusable[0]!
  const last = focusable[focusable.length - 1]!
  if (document.activeElement === resetDialog.value) {
    event.preventDefault()
    ;(event.shiftKey ? last : first).focus()
  } else if (event.shiftKey && (document.activeElement === first || !resetDialog.value.contains(document.activeElement))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (document.activeElement === last || !resetDialog.value.contains(document.activeElement))) {
    event.preventDefault()
    first.focus()
  }
}

async function confirmReset(): Promise<void> {
  resetOpen.value = false
  resetReturnFocus = null
  await store.reset()
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
          <select
            :value="activeVariant"
            :disabled="snapshot.state.stage !== 'READY'"
            data-scenario-variant
            @change="selectVariant(($event.target as HTMLSelectElement).value as ScenarioVariantId)"
          >
            <option v-for="variant in variants" :key="variant.id" :value="variant.id">{{ variant.label }}</option>
          </select>
        </label>
        <span v-if="snapshot.state.stage !== 'READY'" class="scenario-muted" data-scenario-variant-locked>
          变体在场景开始后锁定；如需切换，请先重开本场景。
        </span>
        <button type="button" class="scenario-button" :disabled="!store.canReset.value" data-scenario-reset @click="requestReset">
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

    <div v-if="resetOpen" class="scenario-reset-confirm" data-scenario-reset-dialog @keydown="handleResetKeydown">
      <button type="button" class="scenario-reset-confirm__backdrop" aria-label="取消重开本场景" tabindex="-1" @click="closeReset"></button>
      <section ref="resetDialog" role="dialog" aria-modal="true" aria-labelledby="scenario-reset-title" tabindex="-1">
        <h2 id="scenario-reset-title">确认重开本场景？</h2>
        <p>将只清理本场景 run：事件、演示工单与简报都会清除，run 序号 +1，业务时钟回到 09:00。</p>
        <p class="scenario-muted">不影响线上工作台、历史日报或其他场景；该操作不可撤销。</p>
        <div class="scenario-actions">
          <button type="button" class="scenario-button" data-scenario-reset-cancel @click="closeReset">取消</button>
          <button type="button" class="scenario-button scenario-button--primary" data-scenario-reset-confirm @click="confirmReset">确认重开本场景</button>
        </div>
      </section>
    </div>
  </div>
</template>
