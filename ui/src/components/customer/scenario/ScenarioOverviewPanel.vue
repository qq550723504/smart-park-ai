<script setup lang="ts">
import { computed } from 'vue'
import { DataLine, OfficeBuilding, WarningFilled } from '@element-plus/icons-vue'
import { useB2NightEnergyScenario } from '../../../scenario/b2-night-energy/store'
import { formatDisplayNumber } from '../../../scenario/b2-night-energy/calculations'

const store = useB2NightEnergyScenario()
const snapshot = store.snapshot

const park = computed(() => snapshot.value.parkTotals)
const b2 = computed(() => snapshot.value.b2)
const channels = computed(() => snapshot.value.channelTotals)
const stageLabel = computed(() => snapshot.value.stageLabels[snapshot.value.state.stage])

const isReady = computed(() => snapshot.value.state.stage === 'READY')

async function startPatrol(): Promise<void> {
  await store.startPatrol()
}
</script>

<template>
  <section class="scenario-panel" data-scenario-overview aria-labelledby="scenario-overview-title">
    <header class="scenario-panel__head">
      <div>
        <p class="scenario-panel__eyebrow">演示园区 · 模拟场景 · {{ snapshot.dataSource }}</p>
        <h2 id="scenario-overview-title">园区总览 · 研发大厦夜间能耗</h2>
        <p class="scenario-panel__lede">
          {{ snapshot.detailModeLabel }} · 场景版本 {{ snapshot.scenarioVersion }} · 固定虚拟时钟
          {{ snapshot.clock.initialNow.slice(0, 16).replace('T', ' ') }}
        </p>
      </div>
      <span class="scenario-panel__stage" data-scenario-stage>{{ stageLabel }}</span>
    </header>

    <p v-if="snapshot.dataQuality === 'PARTIAL'" class="scenario-alert" role="status" data-scenario-partial>
      关键小时观测缺失：{{ snapshot.missingReadingIds.join('、') }}。本场景按部分可用数据展示，不补零、不计算完整收益。
    </p>

    <div class="scenario-kpis">
      <article class="scenario-kpi">
        <span class="scenario-kpi__label"><OfficeBuilding aria-hidden="true" /> 园区基线</span>
        <strong data-scenario-park-baseline>{{ formatDisplayNumber(park.baselineKwh) }} kWh</strong>
        <small>B1 + B2 + B3 同一观察窗口</small>
      </article>
      <article class="scenario-kpi">
        <span class="scenario-kpi__label"><DataLine aria-hidden="true" /> 园区实测/模拟观测</span>
        <strong data-scenario-park-observed>{{ formatDisplayNumber(park.observedKwh) }} kWh</strong>
        <small>偏差 {{ formatDisplayNumber(park.deviationPct) }}%（背景楼宇保持不变）</small>
      </article>
      <article class="scenario-kpi scenario-kpi--attention">
        <span class="scenario-kpi__label"><WarningFilled aria-hidden="true" /> 研发大厦 B2</span>
        <strong data-scenario-b2-deviation>{{ formatDisplayNumber(b2.deviationPct) }}%</strong>
        <small>基线 {{ formatDisplayNumber(b2.baselineKwh) }} / 观测 {{ formatDisplayNumber(b2.observedKwh) }} kWh</small>
      </article>
    </div>

    <div class="scenario-buildings">
      <article
        v-for="building in snapshot.buildings"
        :key="building.buildingId"
        class="scenario-building"
        :class="{ 'is-primary': building.buildingId === 'B2' }"
        :data-scenario-building="building.buildingId"
      >
        <header>
          <strong>{{ building.name }}</strong>
          <span>{{ building.buildingId }}</span>
        </header>
        <p v-if="building.buildingId === 'B2'">
          主故事楼宇 · 观测 {{ formatDisplayNumber(b2.observedKwh) }} kWh · 偏差 {{ formatDisplayNumber(b2.deviationPct) }}%
        </p>
        <p v-else>
          背景楼宇 · 按 B2 基线 × {{ building.scale }} 生成，不参与本场景设备检查
        </p>
      </article>
    </div>

    <section class="scenario-card">
      <header class="scenario-card__head">
        <h3>四分项用电</h3>
        <span>总电表只汇总，不与分项重复累加</span>
      </header>
      <table class="scenario-table">
        <thead>
          <tr><th>分项</th><th>基线 kWh</th><th>观测 kWh</th></tr>
        </thead>
        <tbody>
          <tr v-for="channel in channels" :key="channel.deviceId" :data-scenario-channel="channel.deviceId">
            <td>{{ channel.deviceId }}</td>
            <td>{{ formatDisplayNumber(channel.baselineKwh) }}</td>
            <td>
              {{ formatDisplayNumber(channel.observedKwh) }}
              <span v-if="channel.observedMissingCount" class="scenario-tag scenario-tag--warn">部分缺失</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <div class="scenario-actions">
      <button
        type="button"
        class="scenario-button"
        data-scenario-start-patrol
        :disabled="!isReady || store.busy.value"
        @click="startPatrol"
      >
        {{ isReady ? '开始巡检' : '巡检已完成' }}
      </button>
      <span v-if="snapshot.state.patrolResult" data-scenario-patrol-summary>
        4 项检查 · {{ snapshot.state.patrolResult.attentionCount }} 项关注 ·
        {{ snapshot.state.patrolResult.passCount }} 项通过 · {{ snapshot.state.patrolResult.uniqueEventCount }} 个事件 ·
        {{ snapshot.state.workOrder ? 1 : 0 }} 个工单
      </span>
    </div>
    <p v-if="store.error.value" class="scenario-alert" role="alert" data-scenario-error>{{ store.error.value }}</p>
  </section>
</template>
