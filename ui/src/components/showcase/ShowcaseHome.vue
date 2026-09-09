<script setup lang="ts">
import { nextTick, ref } from 'vue'
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
const analysisContext = ref<CustomerAnalysisContext | null>(null)

async function navigate(page: CustomerPage): Promise<void> {
  activePage.value = page
  await nextTick()
  const main = document.getElementById(page === 'analysis' ? 'customer-analysis-main' : 'customer-overview-main')
  main?.setAttribute('tabindex', '-1')
  main?.focus()
}

function updateContext(context: CustomerAnalysisContext | null): void {
  analysisContext.value = context
}

function openAnalysis(context: CustomerAnalysisContext): void {
  analysisContext.value = context
  void navigate('analysis')
}
</script>

<template>
  <CustomerShell :active-page="activePage" @navigate="navigate" @enter-workbench="$emit('enter-workbench')">
    <ParkOverview
      v-show="activePage === 'overview'"
      :active="props.active !== false && activePage === 'overview'"
      @context-change="updateContext"
      @view-analysis="openAnalysis"
    />
    <EnergyAnalysis
      v-if="activePage === 'analysis'"
      :active="props.active !== false && activePage === 'analysis'"
      :context="analysisContext"
      @back="navigate('overview')"
    />
  </CustomerShell>
</template>
