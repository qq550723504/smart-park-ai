<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Connection,
  DataLine,
  DocumentChecked,
  Checked,
  Loading,
  Lock,
  Monitor,
  User,
  VideoPlay,
  WarningFilled,
} from '@element-plus/icons-vue'
import { getShowcaseScenarios } from '../../services/workflowApi'
import type { ShowcaseScenario, ShowcaseScenarioCatalog } from '../../services/workflowApi'
import './showcase-home.css'

const emit = defineEmits<{
  'start-scenario': [id: ShowcaseScenario['id']]
  'enter-workbench': []
}>()

const priority = ['EXPERT_COLLABORATION', 'ALERT_WORKFLOW', 'OPERATIONS_ANALYSIS', 'VOICE_ASSISTANT'] as const
const catalog = ref<ShowcaseScenarioCatalog | null>(null)
const selectedId = ref<ShowcaseScenario['id'] | null>(null)
const loading = ref(true)
const failed = ref(false)

const isSelectable = (scenario: ShowcaseScenario) => scenario.status === 'READY' && scenario.live

const orderedScenarios = computed(() => {
  if (!catalog.value) {
    return []
  }
  return [...catalog.value.scenarios]
    .sort((a, b) => Number(isSelectable(b)) - Number(isSelectable(a))
      || priority.indexOf(a.id) - priority.indexOf(b.id))
    .slice(0, 3)
})

const selectedScenario = computed(() => {
  if (!selectedId.value) {
    return null
  }
  return orderedScenarios.value.find((scenario) => scenario.id === selectedId.value) ?? null
})

const statusMessage = computed(() => {
  if (loading.value) {
    return '正在检查演示链路'
  }
  if (failed.value) {
    return '当前无法确认演示链路'
  }
  if (!selectedScenario.value) {
    return '暂无已验证场景'
  }
  return `已验证场景：${selectedScenario.value.title}`
})

const catalogStamp = computed(() => {
  if (loading.value) {
    return { state: 'loading', label: '正在检查', icon: Loading } as const
  }
  if (failed.value) {
    return { state: 'failed', label: '无法确认演示链路', icon: WarningFilled } as const
  }
  return {
    state: 'verified',
    label: catalog.value?.capturedAt ?? '目录已返回',
    icon: DocumentChecked,
  } as const
})

function safeUnavailableReason(scenario: ShowcaseScenario) {
  return scenario.unavailableReason ?? '当前链路未通过在线验证'
}

function scenarioIcon(id: ShowcaseScenario['id']) {
  return {
    ALERT_WORKFLOW: Checked,
    EXPERT_COLLABORATION: Connection,
    OPERATIONS_ANALYSIS: DataLine,
    VOICE_ASSISTANT: Monitor,
  }[id]
}

function selectScenario(scenario: ShowcaseScenario) {
  if (isSelectable(scenario)) {
    selectedId.value = scenario.id
  }
}

function startScenario() {
  if (selectedScenario.value) {
    emit('start-scenario', selectedScenario.value.id)
  }
}

onMounted(async () => {
  loading.value = true
  failed.value = false
  try {
    catalog.value = await getShowcaseScenarios()
    selectedId.value = orderedScenarios.value.find(isSelectable)?.id ?? null
  } catch {
    catalog.value = null
    selectedId.value = null
    failed.value = true
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <main class="showcase-home" data-showcase-surface="home" aria-labelledby="showcase-home-title">
    <section class="showcase-home__stage" aria-describedby="showcase-home-subtitle">
      <header class="showcase-home__lockup">
        <h1 id="showcase-home-title" class="showcase-home__brand">
          <Monitor aria-hidden="true" />
          <span>智慧园区 Agent 体验中心</span>
        </h1>
        <p id="showcase-home-subtitle" class="showcase-home__subtitle">
          从真实问题开始，看见 Agent 如何形成可信结论
        </p>
        <p class="showcase-home__chain">
          <DocumentChecked aria-hidden="true" />
          <span>真实只读数据 · 执行证据可追溯 · 高风险动作由人工确认</span>
        </p>
        <p class="showcase-home__promise">
          本页只呈现服务端目录确认的可演示任务；不可用能力会保留安全原因，不用前端动画或静态结果伪装在线运行。
        </p>
      </header>
    </section>

    <aside class="showcase-home__panel" aria-label="选择现场演示任务">
      <div class="showcase-home__panel-head">
        <p class="showcase-home__panel-kicker">现场任务</p>
        <span
          class="showcase-home__verified-at"
          :class="`is-${catalogStamp.state}`"
          data-catalog-stamp
          :data-catalog-state="catalogStamp.state"
        >
          <component :is="catalogStamp.icon" aria-hidden="true" />
          <span>{{ catalogStamp.label }}</span>
        </span>
      </div>

      <section
        v-if="selectedScenario"
        class="showcase-home__selected"
        data-selected-scenario
        aria-label="当前选中场景"
      >
        <span class="showcase-home__selected-icon">
          <component :is="scenarioIcon(selectedScenario.id)" aria-hidden="true" />
        </span>
        <p class="showcase-home__selected-label">推荐任务 · {{ selectedScenario.title }}</p>
        <h2>{{ selectedScenario.businessQuestion }}</h2>
        <dl class="showcase-home__facts">
          <div>
            <dt>预计时长</dt>
            <dd>约 {{ selectedScenario.expectedDurationSeconds }} 秒</dd>
          </div>
          <div>
            <dt>验证时间</dt>
            <dd>{{ selectedScenario.lastVerifiedAt }}</dd>
          </div>
          <div>
            <dt>人工边界</dt>
            <dd>{{ selectedScenario.humanBoundary }}</dd>
          </div>
        </dl>
        <div class="showcase-home__proofs" aria-label="可验证证据">
          <span v-for="proof in selectedScenario.proofTypes" :key="proof">
            <DocumentChecked aria-hidden="true" />
            <span>{{ proof }}</span>
          </span>
        </div>
      </section>

      <section v-else-if="!loading" class="showcase-home__selected is-empty" data-selected-scenario>
        <VideoPlay aria-hidden="true" />
        <h2>选择一个已验证场景</h2>
        <p>{{ statusMessage }}</p>
      </section>

      <section v-else class="showcase-home__selected is-empty" role="status">
        <VideoPlay aria-hidden="true" />
        <h2>选择一个已验证场景</h2>
        <p>正在读取服务端演示目录…</p>
      </section>

      <div class="showcase-home__actions">
        <button
          type="button"
          class="showcase-home__start"
          data-start-showcase
          :disabled="!selectedScenario"
          @click="startScenario"
        >
          <VideoPlay aria-hidden="true" />
          <span>开始现场演示</span>
        </button>

        <button
          type="button"
          class="showcase-home__workbench"
          data-enter-workbench
          @click="emit('enter-workbench')"
        >
          <User aria-hidden="true" />
          <span>进入运营工作台</span>
        </button>
      </div>

      <p class="showcase-home__status" data-showcase-status aria-live="polite">
        {{ statusMessage }}
      </p>

      <div v-if="!loading" class="showcase-home__rows" aria-label="演示场景列表">
        <p class="showcase-home__more">可体验任务</p>
        <button
          v-for="scenario in orderedScenarios"
          :key="scenario.id"
          type="button"
          class="showcase-home__row"
          :class="{ 'is-selected': selectedId === scenario.id, 'is-unavailable': !isSelectable(scenario) }"
          data-showcase-scenario-row
          :data-scenario-id="scenario.id"
          :aria-pressed="selectedId === scenario.id"
          :disabled="!isSelectable(scenario)"
          @click="selectScenario(scenario)"
        >
          <span class="showcase-home__row-icon">
            <component :is="scenarioIcon(scenario.id)" aria-hidden="true" />
          </span>
          <span class="showcase-home__row-copy">
            <span class="showcase-home__row-title">{{ scenario.title }}</span>
            <span class="showcase-home__row-question">{{ scenario.businessQuestion }}</span>
            <span v-if="isSelectable(scenario)" class="showcase-home__row-state">READY · live</span>
            <span v-else class="showcase-home__row-state" data-unavailable-reason>
              {{ scenario.status }} · {{ safeUnavailableReason(scenario) }}
            </span>
          </span>
        </button>
      </div>
    </aside>

    <section class="showcase-home__evidence" aria-label="能力账本">
      <div class="showcase-home__ribbon-status">
        <Checked aria-hidden="true" />
        <span>证据链路</span>
        <small>流程说明</small>
      </div>
      <article>
        <Monitor aria-hidden="true" />
        <div>
          <h3>观察</h3>
          <p>多源数据只读接入，事件与状态采集。</p>
        </div>
      </article>
      <article>
        <DataLine aria-hidden="true" />
        <div>
          <h3>分析</h3>
          <p>跨域关联分析，形成假设与证据范围。</p>
        </div>
      </article>
      <article>
        <DocumentChecked aria-hidden="true" />
        <div>
          <h3>建议</h3>
          <p>生成可行建议，附证据与影响说明。</p>
        </div>
      </article>
      <article>
        <Lock aria-hidden="true" />
        <div>
          <h3>人工确认</h3>
          <p>观点与建议由人工确认，高风险动作不自动执行。</p>
        </div>
      </article>
    </section>
  </main>
</template>
