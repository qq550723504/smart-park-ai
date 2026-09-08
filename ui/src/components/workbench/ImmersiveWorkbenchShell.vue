<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Monitor } from '@element-plus/icons-vue'
import type { DemoRole } from '../../types/workflow'
import type { GuidedLaunchUpdate, WorkbenchEvidenceItem, WorkbenchNavItem, WorkbenchView } from '../../types/workbench'
import WorkbenchEvidenceRibbon from './WorkbenchEvidenceRibbon.vue'

const props = withDefaults(defineProps<{
  activeView: WorkbenchView
  role: DemoRole
  navItems: WorkbenchNavItem[]
  evidenceItems: WorkbenchEvidenceItem[]
  guidedLaunch?: GuidedLaunchUpdate | null
  railPriority?: boolean
}>(), { guidedLaunch: null, railPriority: false })

const emit = defineEmits<{
  'switch-view': [view: WorkbenchView]
  'update:role': [role: DemoRole]
  'back-to-showcase': []
  'retry-guided-launch': []
}>()

const availableNavItems = computed(() => props.navItems.filter((item) => item.available))
const railMediaQuery = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(min-width: 768px)')
  : null
const wideRailViewport = ref(railMediaQuery?.matches ?? false)
const railOpen = computed(() => wideRailViewport.value || props.railPriority)
const navLayer: Record<WorkbenchView, 'L2' | 'L3' | 'L4'> = {
  operations: 'L2',
  workflow: 'L3',
  customer: 'L3',
  voice: 'L3',
  collaboration: 'L3',
  'security-incidents': 'L3',
  analytics: 'L3',
  'collaboration-center': 'L3',
  governance: 'L4',
}

function handleRailViewportChange(event: MediaQueryListEvent): void {
  wideRailViewport.value = event.matches
}

onMounted(() => railMediaQuery?.addEventListener('change', handleRailViewportChange))
onBeforeUnmount(() => railMediaQuery?.removeEventListener('change', handleRailViewportChange))

function updateRole(role: DemoRole): void { emit('update:role', role) }
</script>

<template>
  <div class="immersive-workbench" data-testid="immersive-workbench-shell">
    <header class="immersive-workbench__topbar">
      <div class="immersive-workbench__brand"><Monitor aria-hidden="true" /><div><span>AI AGENT · WORKFLOW · EVIDENCE</span><strong>智慧园区智能运营中心</strong></div></div>
      <nav class="immersive-workbench__nav" aria-label="场景导航">
        <button v-for="item in availableNavItems" :key="item.value" type="button" :class="{ active: item.value === activeView }" :aria-current="item.value === activeView ? 'page' : undefined" :data-workbench-view="item.value" :data-workbench-layer="navLayer[item.value]" @click="emit('switch-view', item.value)">{{ item.label }}</button>
      </nav>
      <div class="immersive-workbench__actions">
        <el-select :model-value="role" :teleported="false" aria-label="演示角色" @update:model-value="updateRole">
          <el-option label="查看者" value="VIEWER" /><el-option label="操作员" value="OPERATOR" /><el-option label="审批人" value="APPROVER" /><el-option label="客服坐席" value="CUSTOMER_AGENT" /><el-option label="管理员" value="ADMIN" />
        </el-select>
        <button type="button" data-workbench-action="back-to-showcase" @click="emit('back-to-showcase')">返回展示首页</button>
      </div>
      <div v-if="guidedLaunch" class="guided-launch-status" :data-state="guidedLaunch.state" role="status">
        <span>{{ guidedLaunch.message }}</span><button v-if="guidedLaunch.state === 'failed'" type="button" data-workbench-action="retry-guided-launch" @click="emit('retry-guided-launch')">重新开始</button>
      </div>
    </header>
    <div class="immersive-workbench__workspace">
      <section class="immersive-workbench__stage" data-workbench-stage><slot /></section>
      <details class="immersive-workbench__rail" data-workbench-rail :open="railOpen"><summary><span>Execution Trace</span><small>真实后端事件</small></summary><div class="immersive-workbench__rail-content"><slot name="rail" /></div></details>
    </div>
    <WorkbenchEvidenceRibbon :items="evidenceItems" />
  </div>
</template>
