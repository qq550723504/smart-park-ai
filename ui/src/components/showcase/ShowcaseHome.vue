<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import CustomerAnalysisHero from '../customer/CustomerAnalysisHero.vue'
import CustomerShell from '../customer/CustomerShell.vue'
import EnergyAnalysis from '../customer/EnergyAnalysis.vue'
import ParkOverview from '../customer/ParkOverview.vue'
import CustomerWorkOrders from '../customer/CustomerWorkOrders.vue'
import type { ShowcaseScenario } from '../../services/workflowApi'
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
const analysisInstanceKey = computed(() => {
  const context = analysisContext.value
  return context
    ? JSON.stringify({ buildingId: context.buildingId, source: context.source, energyWindow: context.energyWindow })
    : 'no-analysis-context'
})

async function navigate(page: CustomerPage, requestedContext?: CustomerAnalysisContext): Promise<void> {
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
</script>

<template>
  <CustomerShell :active-page="activePage" @navigate="navigate" @enter-workbench="$emit('enter-workbench')">
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
    <ParkOverview
      v-show="activePage === 'overview'"
      :active="props.active !== false"
      @context-change="updateContext"
      @view-analysis="openAnalysis"
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
      />
    </KeepAlive>
  </CustomerShell>
</template>
