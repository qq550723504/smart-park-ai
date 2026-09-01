<script setup lang="ts">
import { nextTick, ref } from 'vue'
import OperationsWorkbench from './components/OperationsWorkbench.vue'
import ShowcaseHome from './components/showcase/ShowcaseHome.vue'
import type { GuidedWorkbenchView, ScenarioLaunchRequest, ShowcaseLaunchInput, ShowcaseScenarioId, WorkbenchView } from './types/workbench'

const surface = ref<'showcase' | 'workbench'>('showcase')
const requestedView = ref<WorkbenchView>('workflow')
const requestedLaunch = ref<ScenarioLaunchRequest | null>(null)
let nextLaunchRequestId = 0
const hasEnteredWorkbench = ref(false)
const showcaseSurface = ref<InstanceType<typeof ShowcaseHome> | null>(null)
const workbenchSurface = ref<InstanceType<typeof OperationsWorkbench> | null>(null)

const scenarioView: Record<ShowcaseScenarioId, GuidedWorkbenchView> = {
  ALERT_WORKFLOW: 'workflow',
  EXPERT_COLLABORATION: 'collaboration',
  OPERATIONS_ANALYSIS: 'analytics',
  VOICE_ASSISTANT: 'voice',
  CUSTOMER_SERVICE: 'customer',
}

function focusComponentRoot(component: InstanceType<typeof ShowcaseHome> | InstanceType<typeof OperationsWorkbench> | null) {
  const root = component?.$el
  if (root instanceof HTMLElement) {
    root.focus()
  }
}

async function showWorkbench(view: WorkbenchView) {
  requestedView.value = view
  hasEnteredWorkbench.value = true
  surface.value = 'workbench'
  await nextTick()
  if (surface.value === 'workbench') {
    focusComponentRoot(workbenchSurface.value)
  }
}

function startScenario(id: ShowcaseScenarioId, launchInput?: ShowcaseLaunchInput) {
  const view = scenarioView[id]
  const previousInput = requestedLaunch.value?.scenarioId === id
    ? requestedLaunch.value.launchInput
    : undefined
  requestedLaunch.value = {
    requestId: ++nextLaunchRequestId,
    mode: 'guided',
    scenarioId: id,
    view,
    launchInput: launchInput ?? previousInput ?? { alertId: null, question: null },
  }
  void showWorkbench(view)
}

function enterWorkbench() {
  requestedLaunch.value = null
  // Keep the long-lived workbench instance: an analytics run may still be
  // polling or waiting for clarification while the showcase is visible.
  void showWorkbench('workflow')
}

async function returnToShowcase() {
  surface.value = 'showcase'
  await nextTick()
  if (surface.value === 'showcase') {
    focusComponentRoot(showcaseSurface.value)
  }
}
</script>

<template>
  <ShowcaseHome
    ref="showcaseSurface"
    v-show="surface === 'showcase'"
    data-surface="showcase"
    tabindex="-1"
    :active="surface === 'showcase'"
    @start-scenario="startScenario"
    @enter-workbench="enterWorkbench"
  />

  <OperationsWorkbench
    v-if="hasEnteredWorkbench"
    ref="workbenchSurface"
    v-show="surface === 'workbench'"
    data-surface="workbench"
    tabindex="-1"
    :active="surface === 'workbench'"
    :initial-view="requestedView"
    :launch-request="requestedLaunch"
    @back-to-showcase="returnToShowcase"
    @retry-guided-launch="startScenario"
  />
</template>
