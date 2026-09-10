<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import CustomerAnalysisHero from '../customer/CustomerAnalysisHero.vue'
import CustomerShell from '../customer/CustomerShell.vue'
import EnergyAnalysis from '../customer/EnergyAnalysis.vue'
import ParkOverview from '../customer/ParkOverview.vue'
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
    } else if (activePage.value !== 'analysis' || !analysisContext.value) {
      analysisContext.value = latestOverviewContext.value
    }
  }
  activePage.value = page
  await nextTick()
  const main = document.getElementById(page === 'analysis' ? 'customer-analysis-main' : 'customer-overview-main')
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
</script>

<template>
  <CustomerShell :active-page="activePage" @navigate="navigate" @enter-workbench="$emit('enter-workbench')">
    <template #analysis-hero>
      <CustomerAnalysisHero :context="analysisContext" @back="navigate('overview')" />
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
      />
    </KeepAlive>
  </CustomerShell>
</template>
