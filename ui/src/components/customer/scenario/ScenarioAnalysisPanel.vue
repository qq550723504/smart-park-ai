<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { CircleCheck, InfoFilled, WarningFilled } from '@element-plus/icons-vue'
import { B2_SCENARIO_FIXTURE } from '../../../scenario/b2-night-energy/fixture'
import { b2Totals, formatDisplayNumber, planEstimate, validateParameters } from '../../../scenario/b2-night-energy/calculations'
import { useB2NightEnergyScenario } from '../../../scenario/b2-night-energy/store'
import type { ScenarioParameters } from '../../../types/scenarioEnergy'

const store = useB2NightEnergyScenario()
const snapshot = store.snapshot
const fixture = B2_SCENARIO_FIXTURE

const stage = computed(() => snapshot.value.state.stage)
const assessment = computed(() => snapshot.value.state.assessment)
const patrol = computed(() => snapshot.value.state.patrolResult)
const confirmed = computed(() => snapshot.value.state.confirmedPlan)
const followup = computed(() => snapshot.value.state.followupResult)

const canSelect = computed(() => stage.value === 'ASSESSED' || stage.value === 'PLAN_SELECTED')
const selectedPlan = computed(() => fixture.plans.find((plan) => plan.planId === snapshot.value.state.selectedPlanId) ?? null)
const canKeepObserving = computed(() => stage.value === 'PLAN_SELECTED'
  && selectedPlan.value?.planId === 'SCN-PLAN-NONE')
const pinnedPlanId = computed(() => snapshot.value.pinnedPlanId)
const pinnedPlanLabel = computed(() => fixture.plans.find((plan) => plan.planId === pinnedPlanId.value)?.label ?? '')
// Input bounds come from the fixture contract, not literals, so the page and
// `validateParameters` can never drift apart on min/max/step.
const bounds = computed(() => snapshot.value.optimization.parameterBounds)
const defaultParameters = computed(() => snapshot.value.defaultParameters)
const canSelectPlan = (planId: string): boolean => canSelect.value
  && (pinnedPlanId.value == null || planId === pinnedPlanId.value)

const form = reactive<ScenarioParameters>({ ...snapshot.value.defaultParameters })
// The form belongs to the current run: a new run (or a cleared draft) must fall
// back to the fixture defaults, otherwise a fresh run would keep the previous
// run's edited hours/tariff/days and render plan estimates from stale values.
const parameterSignature = computed(() => {
  const draft = snapshot.value.state.planDraft
  return draft
    ? `${draft.planId}:${draft.parameters.savedHours}:${draft.parameters.tariffCnyPerKwh}:${draft.parameters.applicableDaysPerMonth}`
    : 'default'
})
watch(
  [() => snapshot.value.state.scenarioRunId, parameterSignature],
  () => {
    const draft = snapshot.value.state.planDraft
    Object.assign(form, draft ? draft.parameters : snapshot.value.defaultParameters)
  },
  { immediate: true },
)
const validation = computed(() => validateParameters(fixture, form))

// Parameters are only editable while the plan is still awaiting confirmation.
// Once approved, the frozen receipt/work order decide the run, so the inputs
// lock and the plan cards must derive from the frozen snapshot rather than a
// stale local form that would disagree with the confirmed result.
const parametersLocked = computed(() => stage.value !== 'PLAN_SELECTED')

const parameterSource = computed<ScenarioParameters>(() => {
  if (parametersLocked.value) {
    return confirmed.value?.parameters
      ?? snapshot.value.state.planDraft?.parameters
      ?? snapshot.value.defaultParameters
  }
  // While still editable, preview the valid form; if the form is invalid, fall
  // back to the applied draft so cards never render un-applied or default
  // values that the user never reviewed.
  return validation.value.value
    ?? snapshot.value.state.planDraft?.parameters
    ?? snapshot.value.defaultParameters
})

// Confirmation freezes whichever parameters the user reviewed. The visible
// form may hold unapplied edits (or be invalid); the confirm action validates
// and persists them atomically, and is disabled while they are invalid, so a
// receipt can never silently use a different set of values.
const appliedParameters = computed(() => snapshot.value.state.planDraft?.parameters ?? null)
const formDirty = computed(() => {
  const applied = appliedParameters.value
  if (!applied) return true
  return form.savedHours !== applied.savedHours
    || form.tariffCnyPerKwh !== applied.tariffCnyPerKwh
    || form.applicableDaysPerMonth !== applied.applicableDaysPerMonth
})
const canConfirm = computed(() => stage.value === 'PLAN_SELECTED'
  && snapshot.value.dataQuality === 'COMPLETE'
  && validation.value.ok
  && Boolean(selectedPlan.value?.createsOrder))

const planCards = computed(() => fixture.plans.map((plan) => ({
  plan,
  estimate: planEstimate(fixture, snapshot.value.effectiveLedger, parameterSource.value, plan.planId),
})))

const hourlyRows = computed(() => {
  const buckets = new Map<string, { baseline: number; observed: number; missing: boolean }>()
  for (const row of snapshot.value.effectiveLedger) {
    if (row.deviceId === fixture.energyLedger.meterAggregation.parentDeviceId) continue
    const current = buckets.get(row.bucketStart) ?? { baseline: 0, observed: 0, missing: false }
    current.baseline += row.baselineKwh
    current.observed += row.observedMissing ? 0 : row.observedKwh
    current.missing = current.missing || Boolean(row.observedMissing)
    buckets.set(row.bucketStart, current)
  }
  const windowStart = Date.parse(fixture.optimization.adjustmentWindowInObservation.from)
  const windowMax = Date.parse(fixture.optimization.adjustmentWindowInObservation.maxTo)
  return [...buckets.entries()]
    .sort(([a], [b]) => Date.parse(a) - Date.parse(b))
    .map(([bucketStart, value]) => {
      const time = Date.parse(bucketStart)
      return {
        bucketStart,
        label: new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(bucketStart)),
        baseline: value.baseline,
        observed: value.observed,
        missing: value.missing,
        inWindow: time >= windowStart && time < windowMax,
        excess: Math.max(0, value.observed - value.baseline),
      }
    })
})

const maxExcess = computed(() => Math.max(1, ...hourlyRows.value.map((row) => row.excess)))

async function choose(planId: string): Promise<void> {
  if (!canSelect.value) return
  await store.selectPlan(planId)
}

async function applyParameters(): Promise<void> {
  if (!validation.value.ok) return
  await store.updateParameters({ ...form })
}

async function confirm(): Promise<void> {
  if (!canConfirm.value) return
  // Persist the reviewed form first if it diverges from the applied draft, so
  // the frozen receipt matches exactly what the confirmation screen showed.
  if (formDirty.value && !(await store.updateParameters({ ...form }))) return
  await store.confirmAndCreateOrder()
}

async function retryOrder(): Promise<void> {
  // Retry must replay the same identity only; never touch parameters meanwhile.
  await store.confirmAndCreateOrder()
}

const selectedPlanId = computed(() => snapshot.value.state.selectedPlanId)
</script>

<template>
  <section id="customer-analysis-main" class="scenario-panel" tabindex="-1" data-scenario-analysis aria-labelledby="scenario-analysis-title">
    <header class="scenario-panel__head">
      <div>
        <p class="scenario-panel__eyebrow">预设场景研判与模拟执行</p>
        <h2 id="scenario-analysis-title">运营分析 · 研发大厦 B2</h2>
        <p class="scenario-panel__lede">
          观察窗口 {{ fixture.clock.observationWindow.from.slice(0, 16).replace('T', ' ') }} 至
          {{ fixture.clock.observationWindow.to.slice(0, 16).replace('T', ' ') }}（[from,to)）
        </p>
      </div>
      <span class="scenario-panel__stage">{{ snapshot.stageLabels[stage] }}</span>
    </header>

    <section class="scenario-card" data-scenario-ledger>
      <header class="scenario-card__head">
        <h3>小时账本（四分项合计）</h3>
        <span>22:00—02:00 为可调整窗口，每小时减量不超过该小时观测增量</span>
      </header>
      <div class="scenario-ledger" role="img" aria-label="研发大厦每小时基线与观测对比">
        <div
          v-for="row in hourlyRows"
          :key="row.bucketStart"
          class="scenario-ledger__bar"
          :class="{ 'is-window': row.inWindow }"
          :title="`${row.label} 基线 ${row.baseline} / 观测 ${row.observed}`"
        >
          <span class="scenario-ledger__excess" :style="{ height: `${(row.excess / maxExcess) * 100}%` }"></span>
          <em>{{ row.label.slice(0, 2) }}</em>
          <span v-if="row.missing" class="scenario-tag scenario-tag--warn">缺</span>
        </div>
      </div>
    </section>

    <div class="scenario-grid">
      <section class="scenario-card" data-scenario-patrol>
        <header class="scenario-card__head"><h3>巡检结果</h3><span>同一次巡检只聚合一个业务事件</span></header>
        <ul class="scenario-list">
          <li v-for="check in patrol?.checks ?? []" :key="check.checkId" :data-scenario-check="check.checkId">
            <WarningFilled v-if="check.result === 'ATTENTION'" class="is-attention" aria-hidden="true" />
            <CircleCheck v-else class="is-pass" aria-hidden="true" />
            <span>{{ check.label }}</span>
            <em>{{ check.result === 'ATTENTION' ? '关注' : '通过' }}</em>
          </li>
          <li v-if="!patrol" class="scenario-muted">尚未巡检。</li>
        </ul>
      </section>

      <section class="scenario-card" data-scenario-assessment>
        <header class="scenario-card__head"><h3>综合研判</h3><span>{{ assessment?.mode ?? 'PRESET_SCENARIO_EXPLANATION' }}</span></header>
        <p v-if="assessment" data-scenario-assessment-summary>{{ assessment.summary }}</p>
        <ul v-if="assessment" class="scenario-list scenario-list--plain">
          <li v-for="fact in assessment.facts" :key="fact.factId"><InfoFilled aria-hidden="true" /> {{ fact.text }}</li>
        </ul>
        <ul v-if="assessment?.unknowns.length" class="scenario-unknowns">
          <li v-for="unknown in assessment.unknowns" :key="unknown">{{ unknown }}</li>
        </ul>
        <div v-if="stage === 'PATROL_DONE'" class="scenario-actions">
          <button type="button" class="scenario-button scenario-button--primary" :disabled="store.busy.value" data-scenario-run-assessment @click="store.runAssessment()">
            形成场景研判
          </button>
        </div>
        <p v-if="!assessment && stage === 'READY'" class="scenario-muted">请先在总览页开始巡检。</p>
      </section>
    </div>

    <section class="scenario-card" data-scenario-plans>
      <header class="scenario-card__head"><h3>方案比较</h3><span>选择方案后按共享参数重算，审批前不修改已保存值</span></header>
      <p v-if="pinnedPlanId" class="scenario-muted" data-scenario-variant-plan-locked>
        当前演示变体固定为“{{ pinnedPlanLabel }}”，不能改选其他方案。
      </p>
      <div class="scenario-plans">
        <article
          v-for="card in planCards"
          :key="card.plan.planId"
          class="scenario-plan"
          :class="{ 'is-selected': selectedPlanId === card.plan.planId, 'is-recommended': card.plan.recommended }"
          :data-scenario-plan="card.plan.planId"
        >
          <header>
            <strong>{{ card.plan.label }}</strong>
            <span v-if="card.plan.recommended" class="scenario-tag">推荐</span>
          </header>
          <p>{{ card.plan.description }}</p>
          <dl v-if="card.estimate">
            <div><dt>每日减少</dt><dd>{{ formatDisplayNumber(card.estimate.estimatedSavedKwhPerDay) }} kWh</dd></div>
            <div><dt>调整后日总量</dt><dd>{{ formatDisplayNumber(card.estimate.estimatedAfterKwhPerDay) }} kWh</dd></div>
            <div><dt>月度估算</dt><dd>{{ formatDisplayNumber(card.estimate.estimatedMonthlySavingsCny) }} 元</dd></div>
          </dl>
          <p v-else class="scenario-alert" role="status" data-scenario-plan-unavailable>
            关键小时观测缺失，暂停该方案的完整周期估算。
          </p>
          <button type="button" class="scenario-button" :disabled="!canSelectPlan(card.plan.planId)" @click="choose(card.plan.planId)">
            {{ selectedPlanId === card.plan.planId ? '已选择' : '选择此方案' }}
          </button>
        </article>
      </div>
    </section>

    <section class="scenario-card" data-scenario-parameters>
      <header class="scenario-card__head"><h3>参数调整</h3><span>默认 {{ defaultParameters.savedHours.toFixed(1) }} 小时 / {{ defaultParameters.tariffCnyPerKwh.toFixed(2) }} 元每 kWh / 每月 {{ defaultParameters.applicableDaysPerMonth }} 个适用日</span></header>
      <form class="scenario-form" @submit.prevent="applyParameters">
        <label>减少时长（小时）
          <input v-model.number="form.savedHours" type="number" :min="bounds.savedHours.min" :max="bounds.savedHours.max" :step="bounds.savedHours.step" :disabled="parametersLocked" data-scenario-input-hours />
        </label>
        <label>演示电价（元/kWh）
          <input v-model.number="form.tariffCnyPerKwh" type="number" :min="bounds.tariffCnyPerKwh.min" :max="bounds.tariffCnyPerKwh.max" :step="bounds.tariffCnyPerKwh.step" :disabled="parametersLocked" data-scenario-input-tariff />
        </label>
        <label>月度适用日
          <input v-model.number="form.applicableDaysPerMonth" type="number" :min="bounds.applicableDaysPerMonth.min" :max="bounds.applicableDaysPerMonth.max" :step="bounds.applicableDaysPerMonth.step" :disabled="parametersLocked" data-scenario-input-days />
        </label>
        <button type="submit" class="scenario-button" :disabled="!validation.ok || parametersLocked || store.busy.value" data-scenario-apply-parameters>
          应用参数
        </button>
      </form>
      <p v-if="parametersLocked" class="scenario-muted" data-scenario-parameters-locked>
        方案已确认，参数快照已冻结；如需重新调整，请先重开本场景。
      </p>
      <p v-else-if="formDirty" class="scenario-muted" data-scenario-parameters-dirty>
        参数已修改但尚未应用；点击“确认并创建演示任务”会先按当前参数保存。
      </p>
      <p v-if="!validation.ok" class="scenario-alert" role="alert" data-scenario-param-error>{{ validation.errors.join(' ') }}</p>
    </section>

    <section class="scenario-card" data-scenario-confirm>
      <header class="scenario-card__head"><h3>人工确认</h3><span>明确确认后原子保存方案并取得 1 个模拟回执</span></header>
      <p v-if="snapshot.dataQuality === 'PARTIAL'" class="scenario-alert" role="status">
        关键小时数据不完整，已暂停完整节能估算与提交。
      </p>
      <button type="button" class="scenario-button scenario-button--primary" :disabled="!canConfirm || store.busy.value" data-scenario-confirm-order @click="confirm">
        确认并创建演示任务
      </button>
      <button type="button" class="scenario-button" :disabled="!canKeepObserving || store.busy.value" data-scenario-keep-observing @click="store.keepObserving()">
        保持现状
      </button>
      <p v-if="stage === 'PLAN_SELECTED' && !canKeepObserving && selectedPlan?.createsOrder" class="scenario-muted" data-scenario-keep-observing-hint>
        保持现状仅适用于“仅保持观察”方案。
      </p>
      <p v-if="confirmed" class="scenario-receipt" data-scenario-confirmed>
        已冻结方案 {{ confirmed.planId }} · 参数 {{ confirmed.parameters.savedHours }}h/{{ confirmed.parameters.tariffCnyPerKwh }}元/{{ confirmed.parameters.applicableDaysPerMonth }}日
        · 预计 {{ formatDisplayNumber(confirmed.estimate.estimatedSavedKwhPerDay) }} kWh/日
      </p>
      <p v-if="store.pending.value" class="scenario-alert" role="alert" data-scenario-pending>
        模拟命令待确认：{{ store.pending.value.command }}（{{ store.pending.value.status }}）
      </p>
      <button v-if="store.pending.value" type="button" class="scenario-button" :disabled="store.busy.value" data-scenario-retry-order @click="retryOrder">
        按同一身份重试建单（不会重复建单）
      </button>
      <p v-if="store.error.value" class="scenario-alert" role="alert" data-scenario-error>{{ store.error.value }}</p>
    </section>

    <section class="scenario-card" data-scenario-followup>
      <header class="scenario-card__head"><h3>下一周期模拟结果</h3><span>仅在显式验证后显示，验证前不展示未来读数</span></header>
      <template v-if="followup">
        <p data-scenario-followup-summary>
          模拟减少 {{ formatDisplayNumber(followup.savedKwh) }} kWh · 下一周期总用电
          {{ formatDisplayNumber(followup.followupKwh) }} kWh · 仍高于基线 {{ formatDisplayNumber(followup.remainingDeviationPct) }}%
        </p>
        <p class="scenario-muted">
          90% 为固定剧情参数，不是模型准确率；完成模拟任务不等于整体偏差归零。
          <span v-if="snapshot.parkFollowupKwh != null">园区下一周期模拟 {{ formatDisplayNumber(snapshot.parkFollowupKwh) }} kWh。</span>
        </p>
      </template>
      <p v-else class="scenario-muted" data-scenario-followup-hidden>尚未验证下一周期，不展示未来读数。</p>
    </section>

    <p class="scenario-muted">B2 当前观测 {{ formatDisplayNumber(b2Totals(fixture, snapshot.effectiveLedger).observedKwh) }} kWh（由账本求值，页面不另编数字）。</p>
  </section>
</template>
