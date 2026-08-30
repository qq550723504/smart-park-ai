<script setup lang="ts">
import { ref } from 'vue'
import OperationsWorkbench, { type WorkbenchView } from './components/OperationsWorkbench.vue'

const surface = ref<'showcase' | 'workbench'>('showcase')
const requestedView = ref<WorkbenchView>('workflow')

const workbenchEntries: Array<{ view: WorkbenchView; label: string; description: string }> = [
  { view: 'workflow', label: '告警工作流', description: '查看告警分诊、AI 诊断、风险闸门和人工审批。' },
  { view: 'customer', label: '园区客服', description: '查看园区服务问答与转人工工单。' },
  { view: 'voice', label: '实时语音', description: '查看实时语音助手工作台。' },
  { view: 'collaboration', label: '专家协作', description: '查看专家协作处置工作台。' },
  { view: 'analytics', label: '运营分析', description: '查看自然语言运营分析工作台。' },
]

function openWorkbench(view: WorkbenchView) {
  requestedView.value = view
  surface.value = 'workbench'
}

function returnToShowcase() {
  surface.value = 'showcase'
}
</script>

<template>
  <main
    v-if="surface === 'showcase'"
    data-showcase-surface="placeholder"
    aria-labelledby="showcase-placeholder-title"
  >
    <header>
      <p>智慧园区客户展示</p>
      <h1 id="showcase-placeholder-title">客户展示首页占位</h1>
      <p>展示首页将在后续任务中实现。本任务仅保留语义占位，并协调进入现有运营工作台。</p>
    </header>

    <nav aria-label="进入运营工作台">
      <ul>
        <li v-for="entry in workbenchEntries" :key="entry.view">
          <article>
            <h2>{{ entry.label }}</h2>
            <p>{{ entry.description }}</p>
            <button
              type="button"
              :data-showcase-open-workbench="entry.view"
              @click="openWorkbench(entry.view)"
            >
              打开{{ entry.label }}
            </button>
          </article>
        </li>
      </ul>
    </nav>
  </main>

  <OperationsWorkbench
    v-else
    :initial-view="requestedView"
    @back-to-showcase="returnToShowcase"
  />
</template>
