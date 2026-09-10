<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import CustomerAnalysisHero from '../customer/CustomerAnalysisHero.vue'
import CustomerShell from '../customer/CustomerShell.vue'
import EnergyAnalysis from '../customer/EnergyAnalysis.vue'
import ParkOverview from '../customer/ParkOverview.vue'
import CustomerWorkOrders from '../customer/CustomerWorkOrders.vue'
import CustomerOperationsReports from '../customer/CustomerOperationsReports.vue'
import CustomerAssistantPanel from '../customer/CustomerAssistantPanel.vue'
import { getOperationsCapabilities, type OperationsCapabilities, type ShowcaseScenario } from '../../services/workflowApi'
import type { CustomerAnalysisContext, CustomerPage } from '../../types/customer'
import type { WorkbenchView } from '../../types/workbench'
import '../customer/customer-surface.css'

const props = defineProps<{ active?: boolean }>()

defineEmits<{
  'start-scenario': [id: ShowcaseScenario['id'], launchInput: ShowcaseScenario['launchInput']]
  'enter-workbench': [view?: WorkbenchView]
}>()

const activePage = ref<CustomerPage>('overview')
const latestOverviewContext = ref<CustomerAnalysisContext | null>(null)
const analysisContext = ref<CustomerAnalysisContext | null>(null)
const workOrdersContext = ref<CustomerAnalysisContext | null>(null)
const analyticsAvailable = ref(false)
const capabilities = ref<OperationsCapabilities | null>(null)
const assistantOpen = ref(false)
const restartOpen = ref(false)
const restartNotice = ref('')
const restartDialog = ref<HTMLElement | null>(null)
const assistantPanel = ref<{ canResetForDemo: () => boolean; resetForDemo: () => boolean } | null>(null)
const overviewPanel = ref<{ resetForDemo: () => Promise<void> } | null>(null)
let restartReturnFocus: HTMLElement | null = null
const analysisInstanceKey = computed(() => {
  const context = analysisContext.value
  return context
    ? JSON.stringify({ buildingId: context.buildingId, source: context.source, energyWindow: context.energyWindow })
    : 'no-analysis-context'
})
const assistantContext = computed(() => {
  if (activePage.value === 'analysis') return analysisContext.value
  if (activePage.value === 'work-orders') return workOrdersContext.value
  if (activePage.value === 'overview') return latestOverviewContext.value
  return null
})

async function navigate(page: CustomerPage, requestedContext?: CustomerAnalysisContext): Promise<void> {
  restartNotice.value = ''
  if (page === 'analysis') {
    if (requestedContext) {
      analysisContext.value = requestedContext
    } else if (activePage.value !== 'analysis' && latestOverviewContext.value) {
      const current = analysisContext.value
      const latest = latestOverviewContext.value
      if (!current || current.buildingId !== latest.buildingId || current.source !== latest.source) {
        analysisContext.value = latest
      }
    }
  }
  if (page === 'work-orders') {
    const candidate = requestedContext ?? analysisContext.value ?? latestOverviewContext.value
    if (candidate) workOrdersContext.value = candidate
  }
  activePage.value = page
  await nextTick()
  const mainId = page === 'analysis'
    ? 'customer-analysis-main'
    : page === 'work-orders'
      ? 'customer-work-orders-main'
      : page === 'reports'
        ? 'customer-reports-main'
      : 'customer-overview-main'
  const main = document.getElementById(mainId)
  main?.setAttribute('tabindex', '-1')
  main?.focus()
}

function updateContext(context: CustomerAnalysisContext | null): void {
  latestOverviewContext.value = context
  if (activePage.value === 'analysis' && !analysisContext.value && context) {
    analysisContext.value = context
  }
}

function openAnalysis(context: CustomerAnalysisContext): void {
  void navigate('analysis', context)
}

function openWorkOrders(context: CustomerAnalysisContext): void {
  void navigate('work-orders', context)
}

function openAssistant(): void {
  assistantOpen.value = true
}

async function closeAssistant(): Promise<void> {
  assistantOpen.value = false
  await nextTick()
  document.querySelector<HTMLElement>('[data-customer-nav="assistant"]')?.focus()
}

function openAssistantAnalysis(context: CustomerAnalysisContext): void {
  assistantOpen.value = false
  void navigate('analysis', context)
}

async function requestRestart(): Promise<void> {
  if (assistantPanel.value && !assistantPanel.value.canResetForDemo()) {
    assistantOpen.value = true
    restartNotice.value = '助手仍有正在发送或结果未确认的请求。已保留请求关联，请先等待结果或原样重试，再重开导览。'
    return
  }
  restartReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  restartOpen.value = true
  await nextTick()
  restartDialog.value?.focus()
}

async function closeRestart(): Promise<void> {
  restartOpen.value = false
  await nextTick()
  restartReturnFocus?.focus()
  restartReturnFocus = null
}

function handleRestartKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    void closeRestart()
    return
  }
  if (event.key !== 'Tab' || !restartDialog.value) return
  const focusable = Array.from(restartDialog.value.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'))
  if (focusable.length === 0) {
    event.preventDefault()
    restartDialog.value.focus()
    return
  }
  const first = focusable[0]!
  const last = focusable[focusable.length - 1]!
  if (document.activeElement === restartDialog.value) {
    event.preventDefault()
    const target = event.shiftKey ? last : first
    target.focus()
  } else if (event.shiftKey && (document.activeElement === first || !restartDialog.value.contains(document.activeElement))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (document.activeElement === last || !restartDialog.value.contains(document.activeElement))) {
    event.preventDefault()
    first.focus()
  }
}

async function confirmRestart(): Promise<void> {
  if (assistantPanel.value && !assistantPanel.value.canResetForDemo()) {
    restartOpen.value = false
    restartReturnFocus = null
    assistantOpen.value = true
    restartNotice.value = '助手请求尚未确认，导览没有重置；原问题与请求身份仍保留在助手中。'
    return
  }
  if (assistantPanel.value?.resetForDemo() === false) return
  restartOpen.value = false
  restartReturnFocus = null
  assistantOpen.value = false
  latestOverviewContext.value = null
  analysisContext.value = null
  workOrdersContext.value = null
  activePage.value = 'overview'
  await overviewPanel.value?.resetForDemo()
  restartNotice.value = '客户导览已回到起点；仅清除了本页选择与助手会话，后台工单、报告和进行中的任务均未删除。'
  await nextTick()
  const overviewMain = document.getElementById('customer-overview-main')
  overviewMain?.setAttribute('tabindex', '-1')
  overviewMain?.focus()
}

onMounted(() => {
  void getOperationsCapabilities()
    .then((current) => {
      capabilities.value = current
      analyticsAvailable.value = current.analyticsEnabled
    })
    .catch(() => {
      capabilities.value = null
      analyticsAvailable.value = false
    })
})
</script>

<template>
  <CustomerShell
    :active-page="activePage"
    :assistant-open="assistantOpen"
    @navigate="navigate"
    @open-assistant="openAssistant"
    @restart-demo="requestRestart"
    @enter-workbench="$emit('enter-workbench')"
  >
    <template #analysis-hero>
      <CustomerAnalysisHero :context="analysisContext" @back="navigate('overview')" />
    </template>
    <template #work-orders-hero>
      <div>
        <h1 id="customer-work-orders-title">事件与工单中心</h1>
        <p>围绕异常发现、人工确认、建单与跟进，形成可核验的处理闭环</p>
      </div>
      <span>同一事件<br />真实回执</span>
    </template>
    <template #reports-hero>
      <div>
        <h1 id="customer-reports-title">运营报告中心</h1>
        <p>一键沉淀园区运营亮点、问题与改进建议</p>
      </div>
      <span>数据洞察价值<br />报告驱动成长</span>
    </template>
    <p v-if="restartNotice" class="customer-alert customer-demo-restart__notice" role="status" data-restart-notice>{{ restartNotice }}</p>
    <ParkOverview
      ref="overviewPanel"
      v-show="activePage === 'overview'"
      :active="props.active !== false"
      @context-change="updateContext"
      @view-analysis="openAnalysis"
      @view-reports="navigate('reports')"
    />
    <KeepAlive>
      <EnergyAnalysis
        :key="analysisInstanceKey"
        v-show="activePage === 'analysis'"
        :active="props.active !== false && activePage === 'analysis'"
        :context="analysisContext"
        @back="navigate('overview')"
        @open-work-orders="openWorkOrders"
      />
    </KeepAlive>
    <KeepAlive>
      <CustomerWorkOrders
        :key="workOrdersContext ? `${workOrdersContext.buildingId}:${workOrdersContext.anomalyId ?? 'none'}` : 'no-work-order-context'"
        v-show="activePage === 'work-orders'"
        :active="props.active !== false && activePage === 'work-orders'"
        :context="workOrdersContext"
        @back="navigate('analysis')"
        @open-reports="navigate('reports')"
      />
    </KeepAlive>
    <KeepAlive>
      <CustomerOperationsReports
        v-show="activePage === 'reports'"
        :active="props.active !== false && activePage === 'reports'"
        :available="analyticsAvailable"
      />
    </KeepAlive>
    <CustomerAssistantPanel
      ref="assistantPanel"
      :open="assistantOpen"
      :active-page="activePage"
      :context="assistantContext"
      :answer-mode="capabilities?.customerAnswerMode ?? 'unknown'"
      :knowledge-mode="capabilities?.knowledgeMode ?? 'unknown'"
      @close="closeAssistant"
      @open-analysis="openAssistantAnalysis"
    />
    <div v-if="restartOpen" class="customer-demo-restart" @keydown="handleRestartKeydown">
      <button type="button" class="customer-demo-restart__backdrop" aria-label="取消重开导览" tabindex="-1" @click="closeRestart"></button>
      <section ref="restartDialog" role="dialog" aria-modal="true" aria-labelledby="restart-demo-title" tabindex="-1">
        <h2 id="restart-demo-title">重开客户导览？</h2>
        <p>将返回园区总览，并清除当前页面选择、助手输入和本地会话展示。</p>
        <strong>不会删除或重置后台工单、历史报告及共享演示数据；进行中的任务会继续执行。</strong>
        <footer>
          <button type="button" data-cancel-restart @click="closeRestart">取消</button>
          <button type="button" data-confirm-restart @click="confirmRestart">确认重开导览</button>
        </footer>
      </section>
    </div>
  </CustomerShell>
</template>
