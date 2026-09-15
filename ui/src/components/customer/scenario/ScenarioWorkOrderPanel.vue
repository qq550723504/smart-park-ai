<script setup lang="ts">
import { computed } from 'vue'
import { Bell, Document, Promotion, Select } from '@element-plus/icons-vue'
import { formatDisplayNumber } from '../../../scenario/b2-night-energy/calculations'
import { useB2NightEnergyScenario } from '../../../scenario/b2-night-energy/store'

const store = useB2NightEnergyScenario()
const snapshot = store.snapshot

const stage = computed(() => snapshot.value.state.stage)
const event = computed(() => snapshot.value.state.patrolResult?.events[0] ?? null)
const order = computed(() => snapshot.value.state.workOrder)
const confirmed = computed(() => snapshot.value.state.confirmedPlan)
const followup = computed(() => snapshot.value.state.followupResult)

const eventStatusLabels: Record<string, string> = {
  NOT_SURFACED: '未上报',
  OPEN: '待处理',
  HANDLING: '处理中',
  MONITORING: '持续观察',
  CLOSED: '已关闭',
}

const canTake = computed(() => stage.value === 'ORDER_CREATED')
const canApply = computed(() => stage.value === 'PROCESSING')
const canVerify = computed(() => stage.value === 'APPLIED_AWAITING_VERIFICATION')

const processingRecords = computed(() => snapshot.value.state.commandLog.filter((entry) =>
  ['CONFIRM_AND_CREATE_ORDER', 'TAKE_ORDER', 'APPLY_SIMULATED_PLAN', 'VERIFY_NEXT_CYCLE', 'KEEP_OBSERVING'].includes(entry.action),
))
</script>

<template>
  <section class="scenario-panel" data-scenario-work-orders aria-labelledby="scenario-work-orders-title">
    <header class="scenario-panel__head">
      <div>
        <p class="scenario-panel__eyebrow">同一 B2 · 本轮 run · 同一事件</p>
        <h2 id="scenario-work-orders-title">事件与工单 · 模拟处理</h2>
        <p class="scenario-panel__lede">任务完成不等于整体偏差消除，事件按余下偏差置为持续观察。</p>
      </div>
      <span class="scenario-panel__stage">{{ snapshot.stageLabels[stage] }}</span>
    </header>

    <section class="scenario-card" data-scenario-event>
      <header class="scenario-card__head"><h3><Bell aria-hidden="true" /> 业务事件</h3><span>{{ eventStatusLabels[snapshot.state.eventStatus] ?? snapshot.state.eventStatus }}</span></header>
      <template v-if="event">
        <p>
          <strong>{{ event.title }}</strong>
          <span class="scenario-tag">优先级 {{ event.priority }}</span>
        </p>
        <dl class="scenario-meta">
          <div><dt>事件号</dt><dd data-scenario-event-id>{{ event.anomalyId }}</dd></div>
          <div><dt>受影响设备</dt><dd>{{ event.affectedDeviceIds.join('、') }}</dd></div>
        </dl>
      </template>
      <p v-else class="scenario-muted" data-scenario-event-none>尚未巡检，事件未上报。</p>
    </section>

    <section class="scenario-card" data-scenario-order>
      <header class="scenario-card__head"><h3><Document aria-hidden="true" /> 演示任务</h3><span>幂等键：{{ snapshot.state.scenarioRunId }} + 事件 + 方案修订</span></header>
      <template v-if="order">
        <dl class="scenario-meta">
          <div><dt>工单号</dt><dd data-scenario-order-id>{{ order.id }}</dd></div>
          <div><dt>工作流</dt><dd>{{ order.workflowId }}</dd></div>
          <div><dt>状态</dt><dd data-scenario-order-status>{{ order.statusLabel }}</dd></div>
          <div><dt>方案</dt><dd>{{ order.selectedPlanId }}</dd></div>
          <div><dt>目标设备</dt><dd>{{ order.targetDeviceIds.join('、') || '无' }}</dd></div>
          <div><dt>保护设备</dt><dd>{{ order.protectedDeviceIds.join('、') }}</dd></div>
          <div><dt>参数快照</dt><dd>{{ order.parameterSnapshot.savedHours }}h / {{ order.parameterSnapshot.tariffCnyPerKwh }}元 / {{ order.parameterSnapshot.applicableDaysPerMonth }}日</dd></div>
          <div><dt>预计减少</dt><dd>{{ formatDisplayNumber(order.estimateSnapshot.estimatedSavedKwhPerDay) }} kWh/日</dd></div>
          <div><dt>负责人</dt><dd>{{ order.assigneeActorId }}</dd></div>
        </dl>
        <div class="scenario-actions">
          <button type="button" class="scenario-button" :disabled="!canTake || store.busy.value" data-scenario-take-order @click="store.takeOrder()">
            <Promotion aria-hidden="true" /> 接单
          </button>
          <button type="button" class="scenario-button" :disabled="!canApply || store.busy.value" data-scenario-apply-plan @click="store.applySimulatedPlan()">
            模拟应用方案
          </button>
          <button type="button" class="scenario-button scenario-button--primary" :disabled="!canVerify || store.busy.value" data-scenario-verify @click="store.verifyNextCycle()">
            <Select aria-hidden="true" /> 查看下一周期模拟结果
          </button>
        </div>
      </template>
      <p v-else-if="stage === 'CLOSED_NO_ACTION'" class="scenario-muted" data-scenario-order-none>本次选择保持观察，未创建任务。</p>
      <p v-else class="scenario-muted" data-scenario-order-none>尚未创建任务，请先在分析页选择方案并人工确认。</p>
    </section>

    <section v-if="confirmed" class="scenario-card" data-scenario-confirmed-plan>
      <header class="scenario-card__head"><h3>已冻结方案</h3><span>审批后不再原地修改参数</span></header>
      <p>
        {{ confirmed.planId }} · 参数 {{ confirmed.parameters.savedHours }}h /
        {{ confirmed.parameters.tariffCnyPerKwh }}元 / {{ confirmed.parameters.applicableDaysPerMonth }}日 ·
        预计 {{ formatDisplayNumber(confirmed.estimate.estimatedSavedKwhPerDay) }} kWh/日，
        月度 {{ formatDisplayNumber(confirmed.estimate.estimatedMonthlySavingsCny) }} 元
      </p>
    </section>

    <section class="scenario-card" data-scenario-processing>
      <header class="scenario-card__head"><h3>处理记录</h3><span>全部来自同一共享 run</span></header>
      <ul class="scenario-timeline">
        <li v-for="record in processingRecords" :key="`${record.action}-${record.stateRevision}`">
          <strong>{{ record.action }}</strong>
          <span>修订 {{ record.stateRevision }}</span>
          <time>{{ record.at.slice(0, 16).replace('T', ' ') }}</time>
        </li>
        <li v-if="!processingRecords.length" class="scenario-muted">暂无处理记录。</li>
      </ul>
      <p v-if="followup" data-scenario-verified>
        已验证：模拟减少 {{ formatDisplayNumber(followup.savedKwh) }} kWh，仍高于基线
        {{ formatDisplayNumber(followup.remainingDeviationPct) }}%（事件 {{ eventStatusLabels[snapshot.state.eventStatus] }}）。
      </p>
    </section>

    <p v-if="store.error.value" class="scenario-alert" role="alert" data-scenario-error>{{ store.error.value }}</p>
  </section>
</template>
