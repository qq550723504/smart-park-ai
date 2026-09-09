<script setup lang="ts">
import CustomerShell from '../customer/CustomerShell.vue'
import ParkOverview from '../customer/ParkOverview.vue'
import type { ShowcaseScenario } from '../../services/workflowApi'
import type { WorkbenchView } from '../../types/workbench'
import '../customer/customer-surface.css'

defineProps<{ active?: boolean }>()

defineEmits<{
  'start-scenario': [id: ShowcaseScenario['id'], launchInput: ShowcaseScenario['launchInput']]
  'enter-workbench': [view?: WorkbenchView]
}>()
</script>

<template>
  <CustomerShell @enter-workbench="$emit('enter-workbench')">
    <ParkOverview :active="active !== false" />
  </CustomerShell>
</template>
