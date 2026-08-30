<script setup lang="ts">
import { ref } from 'vue'
import OperationsWorkbench, { type WorkbenchView } from './components/OperationsWorkbench.vue'
import ShowcaseHome from './components/showcase/ShowcaseHome.vue'
import type { ShowcaseScenario } from './services/workflowApi'

const surface = ref<'showcase' | 'workbench'>('showcase')
const requestedView = ref<WorkbenchView>('workflow')
const hasEnteredWorkbench = ref(false)

const scenarioView: Record<ShowcaseScenario['id'], WorkbenchView> = {
  ALERT_WORKFLOW: 'workflow',
  EXPERT_COLLABORATION: 'collaboration',
  OPERATIONS_ANALYSIS: 'analytics',
  VOICE_ASSISTANT: 'voice',
}

function showWorkbench(view: WorkbenchView) {
  requestedView.value = view
  hasEnteredWorkbench.value = true
  surface.value = 'workbench'
}

function startScenario(id: ShowcaseScenario['id']) {
  showWorkbench(scenarioView[id])
}

function enterWorkbench() {
  showWorkbench('workflow')
}

function returnToShowcase() {
  surface.value = 'showcase'
}
</script>

<template>
  <ShowcaseHome
    v-show="surface === 'showcase'"
    data-surface="showcase"
    :active="surface === 'showcase'"
    @start-scenario="startScenario"
    @enter-workbench="enterWorkbench"
  />

  <KeepAlive>
    <OperationsWorkbench
      v-if="hasEnteredWorkbench"
      v-show="surface === 'workbench'"
      data-surface="workbench"
      :active="surface === 'workbench'"
      :initial-view="requestedView"
      @back-to-showcase="returnToShowcase"
    />
  </KeepAlive>
</template>
