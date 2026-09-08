<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Checked, Connection, DataLine, DocumentChecked, Loading, Lock, Monitor, User, VideoPlay, WarningFilled } from '@element-plus/icons-vue'
import { getGovernanceOverview, getShowcaseScenarios } from '../../services/workflowApi'
import type { GovernanceOverview, ShowcaseScenario, ShowcaseScenarioCatalog } from '../../services/workflowApi'
import type { WorkbenchView } from '../../types/workbench'
import './showcase-home.css'

const emit = defineEmits<{
  'start-scenario': [id: ShowcaseScenario['id'], launchInput: ShowcaseScenario['launchInput']]
  'enter-workbench': [view?: WorkbenchView]
}>()
const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })

const priority = ['CUSTOMER_SERVICE', 'EXPERT_COLLABORATION', 'ALERT_WORKFLOW', 'OPERATIONS_ANALYSIS', 'VOICE_ASSISTANT'] as const
const catalog = ref<ShowcaseScenarioCatalog | null>(null)
const selectedId = ref<ShowcaseScenario['id'] | null>(null)
const loading = ref(true)
const failed = ref(false)
let catalogRequestGeneration = 0
const governanceOverview = ref<GovernanceOverview | null>(null)
const governanceLoading = ref(false)
const governanceFailed = ref(false)
let governanceRequestGeneration = 0

type CockpitCapability = {
  label: string
  mapping: 'DIRECT_REUSE' | 'ADAPTED' | 'NOT_READY'
  scenarioId?: ShowcaseScenario['id']
  view?: WorkbenchView
  unavailableReason?: string
}

const cockpitCapabilities: CockpitCapability[] = [
  { label: '运维剧本', mapping: 'DIRECT_REUSE', scenarioId: 'ALERT_WORKFLOW' },
  { label: '节能优化', mapping: 'DIRECT_REUSE', scenarioId: 'OPERATIONS_ANALYSIS' },
  { label: '异常诊断', mapping: 'DIRECT_REUSE', scenarioId: 'EXPERT_COLLABORATION' },
  { label: '预测性维护', mapping: 'ADAPTED', view: 'operations' },
  { label: '完整演示', mapping: 'NOT_READY', unavailableReason: '缺少多场景编排 API' },
  { label: '安防剧本', mapping: 'ADAPTED', view: 'security-incidents' },
  { label: '误报过滤', mapping: 'NOT_READY', unavailableReason: '缺少误报判定模型' },
  { label: '烟火事件', mapping: 'NOT_READY', unavailableReason: '事件数据源未接入' },
  { label: '越界入侵', mapping: 'NOT_READY', unavailableReason: '事件数据源未接入' },
  { label: '人员聚集', mapping: 'NOT_READY', unavailableReason: '事件数据源未接入' },
  { label: '离岗监测', mapping: 'NOT_READY', unavailableReason: '事件数据源未接入' },
  { label: '安防完整', mapping: 'ADAPTED', view: 'security-incidents' },
]

const isSelectable = (scenario: ShowcaseScenario) => scenario.status === 'READY' && scenario.live
const hasConfirmedCatalog = computed(() => !loading.value && !failed.value && catalog.value !== null)
const readyScenarioCount = computed(() => catalog.value?.scenarios.filter(isSelectable).length ?? 0)
const scenarioCount = computed(() => catalog.value?.scenarios.length ?? 0)
const unavailableScenarioCount = computed(() => Math.max(0, scenarioCount.value - readyScenarioCount.value))
const orderedScenarios = computed(() => {
  if (!catalog.value) return []
  return [...catalog.value.scenarios]
    .sort((a, b) => Number(isSelectable(b)) - Number(isSelectable(a)) || priority.indexOf(a.id) - priority.indexOf(b.id))
})
const selectedScenario = computed(() => selectedId.value
  ? orderedScenarios.value.find((scenario) => scenario.id === selectedId.value) ?? null
  : null)
const statusMessage = computed(() => {
  if (loading.value) return '正在检查演示链路'
  if (failed.value) return '当前无法确认演示链路'
  if (!selectedScenario.value) return '暂无已验证场景'
  return `已验证场景：${selectedScenario.value.title}`
})
const catalogStamp = computed(() => {
  if (loading.value) return { state: 'loading', label: '正在检查', icon: Loading } as const
  if (failed.value) return { state: 'failed', label: '无法确认演示链路', icon: WarningFilled } as const
  return { state: 'verified', label: catalog.value?.capturedAt ?? '目录已返回', icon: DocumentChecked } as const
})

function safeUnavailableReason(scenario: ShowcaseScenario) {
  return scenario.unavailableReason ?? '当前链路未通过在线验证'
}

function capabilityState(capability: CockpitCapability): string {
  if (capability.mapping === 'NOT_READY') return 'NOT_READY'
  if (capability.scenarioId) {
    const scenario = catalog.value?.scenarios.find((item) => item.id === capability.scenarioId)
    return scenario && isSelectable(scenario) ? 'READY' : scenario?.status ?? 'CHECKING'
  }
  if (capability.view === 'security-incidents') {
    if (governanceLoading.value) return 'CHECKING'
    if (governanceFailed.value || !governanceOverview.value) return 'UNAVAILABLE'
    return governanceOverview.value?.capabilities.securityIncidentEnabled ? 'AVAILABLE' : 'NOT_READY'
  }
  if (governanceLoading.value) return 'CHECKING'
  if (governanceFailed.value || !governanceOverview.value) return 'UNAVAILABLE'
  return governanceOverview.value?.capabilities.analyticsEnabled ? 'AVAILABLE' : 'NOT_READY'
}

function capabilityReason(capability: CockpitCapability): string {
  if (capability.unavailableReason) return capability.unavailableReason
  if (capability.scenarioId) {
    const scenario = catalog.value?.scenarios.find((item) => item.id === capability.scenarioId)
    return scenario && !isSelectable(scenario) ? safeUnavailableReason(scenario) : capability.mapping
  }
  const state = capabilityState(capability)
  if (state === 'CHECKING') return '正在检查对应能力'
  if (state === 'UNAVAILABLE') return '当前无法确认对应能力'
  return state === 'NOT_READY' ? '当前部署未启用对应能力' : capability.mapping
}

function scenarioIcon(id: ShowcaseScenario['id']) {
  return { ALERT_WORKFLOW: Checked, EXPERT_COLLABORATION: Connection, OPERATIONS_ANALYSIS: DataLine, CUSTOMER_SERVICE: User, VOICE_ASSISTANT: Monitor }[id]
}

function selectScenario(scenario: ShowcaseScenario) {
  if (isSelectable(scenario)) selectedId.value = scenario.id
}

function activateCapability(capability: CockpitCapability): void {
  if (capability.mapping === 'NOT_READY') return
  if (capability.scenarioId) {
    const scenario = catalog.value?.scenarios.find((item) => item.id === capability.scenarioId)
    if (scenario) selectScenario(scenario)
    return
  }
  if (capabilityState(capability) === 'AVAILABLE' && capability.view) emit('enter-workbench', capability.view)
}

async function refreshCatalog() {
  const requestGeneration = ++catalogRequestGeneration
  const previouslySelectedId = selectedId.value
  loading.value = true
  failed.value = false
  try {
    const nextCatalog = await getShowcaseScenarios()
    if (requestGeneration !== catalogRequestGeneration) return null
    catalog.value = nextCatalog
    const previousSelection = orderedScenarios.value.find((scenario) => scenario.id === previouslySelectedId && isSelectable(scenario))
    selectedId.value = previousSelection?.id ?? orderedScenarios.value.find(isSelectable)?.id ?? null
    return nextCatalog
  } catch {
    if (requestGeneration !== catalogRequestGeneration) return null
    catalog.value = null
    selectedId.value = null
    failed.value = true
    return null
  } finally {
    if (requestGeneration === catalogRequestGeneration) loading.value = false
  }
}

async function refreshGovernance(): Promise<void> {
  const requestGeneration = ++governanceRequestGeneration
  governanceLoading.value = true
  governanceFailed.value = false
  try {
    const nextOverview = await getGovernanceOverview()
    if (requestGeneration !== governanceRequestGeneration) return
    governanceOverview.value = nextOverview
  } catch {
    if (requestGeneration !== governanceRequestGeneration) return
    governanceOverview.value = null
    governanceFailed.value = true
  } finally {
    if (requestGeneration === governanceRequestGeneration) governanceLoading.value = false
  }
}

async function startScenario() {
  const intendedScenarioId = selectedScenario.value?.id
  if (!intendedScenarioId || loading.value) return
  const verifiedCatalog = await refreshCatalog()
  const verifiedScenario = verifiedCatalog?.scenarios.find((scenario) => scenario.id === intendedScenarioId)
  if (props.active && verifiedScenario && isSelectable(verifiedScenario)) emit('start-scenario', intendedScenarioId, verifiedScenario.launchInput)
}

watch(() => props.active, (active) => {
  if (active) {
    void refreshCatalog()
    void refreshGovernance()
  } else {
    catalogRequestGeneration++
    loading.value = false
    governanceRequestGeneration++
    governanceLoading.value = false
  }
}, { immediate: true })
</script>

<template>
  <main class="showcase-home" data-showcase-surface="home" aria-labelledby="showcase-home-title">
    <header class="showcase-home__topbar">
      <div class="showcase-home__lockup">
        <h1 id="showcase-home-title" class="showcase-home__brand"><Monitor aria-hidden="true" /><span>智慧园区智能运营中心</span></h1>
        <p id="showcase-home-subtitle" class="showcase-home__subtitle">AI SMART PARK OPERATIONS CENTER</p>
      </div>
      <div class="showcase-home__top-actions">
        <span class="showcase-home__verified-at" :class="`is-${catalogStamp.state}`" data-catalog-stamp :data-catalog-state="catalogStamp.state"><component :is="catalogStamp.icon" aria-hidden="true" /><span>{{ catalogStamp.label }}</span></span>
        <button type="button" data-enter-workbench @click="emit('enter-workbench')">进入运营工作台</button>
      </div>
    </header>

    <nav class="showcase-home__capabilities" aria-label="驾驶舱能力映射">
      <button v-for="capability in cockpitCapabilities" :key="capability.label" type="button" :disabled="['NOT_READY', 'DISABLED', 'CHECKING', 'UNAVAILABLE'].includes(capabilityState(capability))" :data-cockpit-capability="capability.label" :data-mapping="capability.mapping" :data-capability-state="capabilityState(capability)" :title="capabilityReason(capability)" @click="activateCapability(capability)"><span>{{ capability.label }}</span><small>{{ capabilityState(capability) }}</small></button>
    </nav>

    <section class="showcase-home__overview" aria-label="管理者总览">
      <section class="showcase-home__stage" aria-describedby="showcase-home-subtitle">
        <div class="showcase-home__hero-copy">
          <p class="showcase-home__eyebrow">PARK OPERATIONS / VERIFIED AI</p>
          <h2>AI 驱动园区运营，<br /><em>从发现到处置都有证据</em></h2>
          <p class="showcase-home__tagline" data-showcase-tagline>让园区会思考、能协同、可执行，以 AI 驱动运营升级，让每一份数据都创造价值</p>
          <p class="showcase-home__promise">真实只读数据、执行证据可追溯；不可用能力明确标记，高风险动作由人工确认。</p>
        </div>
        <dl class="showcase-home__pulse" aria-label="园区 AI 运行状态">
          <div data-showcase-metric="verified"><dt>已验证能力</dt><dd>{{ hasConfirmedCatalog ? readyScenarioCount : '—' }}</dd><small>/ {{ hasConfirmedCatalog ? scenarioCount : '—' }} 个场景</small></div>
          <div data-showcase-metric="risk"><dt>能力风险</dt><dd>{{ hasConfirmedCatalog ? unavailableScenarioCount : '—' }}</dd><small>NOT_READY / DISABLED</small></div>
          <div><dt>人工治理边界</dt><dd>{{ governanceOverview?.boundaries.length ?? '—' }}</dd><small>{{ governanceFailed ? '治理状态暂不可用' : '服务端治理摘要' }}</small></div>
        </dl>
      </section>

      <aside class="showcase-home__panel" aria-label="选择现场演示任务">
        <div class="showcase-home__panel-head"><div><p class="showcase-home__panel-kicker">AI 今日关注</p><h2>推荐分析任务</h2></div><span class="showcase-home__live-key"><i></i>真实能力目录</span></div>
        <section v-if="selectedScenario" class="showcase-home__selected" data-selected-scenario aria-label="当前选中场景">
          <span class="showcase-home__selected-icon"><component :is="scenarioIcon(selectedScenario.id)" aria-hidden="true" /></span>
          <p class="showcase-home__selected-label">推荐任务 · {{ selectedScenario.title }}</p>
          <h2>{{ selectedScenario.businessQuestion }}</h2>
          <dl class="showcase-home__facts">
            <div><dt>预计时长</dt><dd>约 {{ selectedScenario.expectedDurationSeconds }} 秒</dd></div>
            <div><dt>验证时间</dt><dd>{{ selectedScenario.lastVerifiedAt }}</dd></div>
            <div><dt>人工边界</dt><dd>{{ selectedScenario.humanBoundary }}</dd></div>
          </dl>
          <div class="showcase-home__proofs" aria-label="可验证证据"><span v-for="proof in selectedScenario.proofTypes" :key="proof"><DocumentChecked aria-hidden="true" /><span>{{ proof }}</span></span></div>
        </section>
        <section v-else-if="!loading" class="showcase-home__selected is-empty" data-selected-scenario><VideoPlay aria-hidden="true" /><h2>选择一个已验证场景</h2><p>{{ statusMessage }}</p></section>
        <section v-else class="showcase-home__selected is-empty" role="status"><VideoPlay aria-hidden="true" /><h2>选择一个已验证场景</h2><p>正在读取服务端演示目录…</p></section>
        <div class="showcase-home__actions"><button type="button" class="showcase-home__start" data-start-showcase :disabled="loading || !selectedScenario" @click="startScenario"><VideoPlay aria-hidden="true" /><span>开始 AI 分析</span></button></div>
        <p class="showcase-home__status" data-showcase-status aria-live="polite"><span>{{ statusMessage }}</span><button v-if="failed" type="button" class="showcase-home__retry" data-retry-catalog @click="refreshCatalog">重试验证</button></p>
        <div v-if="!loading" class="showcase-home__rows" aria-label="演示场景列表">
          <p class="showcase-home__more">已登记 Agent 场景</p>
          <button v-for="scenario in orderedScenarios" :key="scenario.id" type="button" class="showcase-home__row" :class="{ 'is-selected': selectedId === scenario.id, 'is-unavailable': !isSelectable(scenario) }" data-showcase-scenario-row :data-scenario-id="scenario.id" :aria-pressed="selectedId === scenario.id" :disabled="!isSelectable(scenario)" @click="selectScenario(scenario)">
            <span class="showcase-home__row-icon"><component :is="scenarioIcon(scenario.id)" aria-hidden="true" /></span>
            <span class="showcase-home__row-copy"><span class="showcase-home__row-title">{{ scenario.title }}</span><span class="showcase-home__row-question">{{ scenario.businessQuestion }}</span><span v-if="isSelectable(scenario)" class="showcase-home__row-state">READY · live</span><span v-else class="showcase-home__row-state" data-unavailable-reason>{{ scenario.status }} · {{ safeUnavailableReason(scenario) }}</span></span>
          </button>
        </div>
      </aside>
    </section>

    <section class="showcase-home__evidence" aria-label="能力账本">
      <div class="showcase-home__ribbon-status"><Checked aria-hidden="true" /><span>证据链路</span><small>流程说明</small></div>
      <article><Monitor aria-hidden="true" /><div><h3>发现异常</h3><p>读取真实园区上下文与状态。</p></div></article>
      <article><DataLine aria-hidden="true" /><div><h3>验证证据</h3><p>分析基线并关联告警、设备与安防。</p></div></article>
      <article><DocumentChecked aria-hidden="true" /><div><h3>形成建议</h3><p>结论绑定来源、指标口径与轨迹。</p></div></article>
      <article><Lock aria-hidden="true" /><div><h3>人工确认</h3><p>高风险动作不自动执行。</p></div></article>
      <article class="showcase-home__governance-summary">
        <Lock aria-hidden="true" /><div><h3>治理摘要</h3><p v-if="governanceLoading">正在读取治理状态…</p><p v-else-if="governanceFailed" data-governance-status>治理状态暂不可用，详细审计与能力状态请在工作台治理中心查看。</p><template v-else-if="governanceOverview"><p data-governance-status>已验证场景 {{ governanceOverview.scenarios.ready }}/{{ governanceOverview.scenarios.total }} · 聚合指标 · 人工可控。</p><ul><li v-for="boundary in governanceOverview.boundaries" :key="boundary">{{ boundary }}</li></ul></template><p v-else data-governance-status>治理状态暂不可用，详细审计与能力状态请在工作台治理中心查看。</p></div>
      </article>
    </section>
  </main>
</template>
