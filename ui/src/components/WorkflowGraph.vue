<script setup lang="ts">
import { computed } from 'vue'
import { VueFlow, Position, type Edge, type Node } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import type { WorkflowEvent, WorkflowResponse } from '../types/workflow'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

const props = defineProps<{ workflow: WorkflowResponse | null; events: WorkflowEvent[] }>()

const definitions = [
  ['classifyAlert', '告警分诊', 30, 130],
  ['collectParkContext', '收集园区上下文', 230, 130],
  ['retrieveKnowledge', '检索知识库', 450, 130],
  ['diagnoseAlert', 'AI 告警诊断', 650, 130],
  ['riskGate', '风险判断', 850, 130],
  ['humanApproval', '人工审批', 850, 280],
  ['createWorkOrder', '创建工单', 1070, 130],
  ['summarizeResult', '汇总结果', 1270, 130],
] as const

function nodeStatus(id: string) {
  const related = props.events.filter((event) => event.node === id)
  if (related.some((event) => event.type === 'FAILED')) return 'failed'
  if (related.some((event) => event.type === 'PAUSED')) {
    return props.workflow?.status === 'WAITING_APPROVAL' ? 'waiting' : 'completed'
  }
  if (related.some((event) => event.type === 'NODE_COMPLETED')) return 'completed'
  if (related.some((event) => event.type === 'NODE_STARTED')) return 'running'
  return 'pending'
}

const nodes = computed<Node[]>(() => definitions.map(([id, label, x, y]) => ({
  id,
  position: { x, y },
  sourcePosition: Position.Right,
  targetPosition: Position.Left,
  data: { label, status: nodeStatus(id) },
  class: `workflow-node is-${nodeStatus(id)}`,
  draggable: false,
})))

const edges: Edge[] = [
  ['classifyAlert', 'collectParkContext'],
  ['collectParkContext', 'retrieveKnowledge'],
  ['retrieveKnowledge', 'diagnoseAlert'],
  ['diagnoseAlert', 'riskGate'],
  ['riskGate', 'createWorkOrder'],
  ['riskGate', 'humanApproval'],
  ['humanApproval', 'createWorkOrder'],
  ['humanApproval', 'summarizeResult'],
  ['createWorkOrder', 'summarizeResult'],
].map(([source, target], index) => ({ id: `e${index}`, source, target, animated: true }))
</script>

<template>
  <section class="panel graph-panel">
    <div class="section-heading compact">
      <div>
        <span class="eyebrow">LIVE ORCHESTRATION</span>
        <h2>工作流执行图</h2>
      </div>
      <div class="graph-legend">
        <span><i class="dot pending"></i>未执行</span>
        <span><i class="dot running"></i>执行中</span>
        <span><i class="dot completed"></i>已完成</span>
        <span><i class="dot waiting"></i>待审批</span>
      </div>
    </div>
    <div class="flow-canvas">
      <VueFlow :nodes="nodes" :edges="edges" :fit-view-on-init="true" :min-zoom="0.45" :max-zoom="1.3">
        <Background pattern-color="#d6e4e8" :gap="22" />
        <Controls :show-interactive="false" />
        <template #node-default="{ data }">
          <div class="node-inner">
            <span class="node-indicator"></span>
            <div>
              <strong>{{ data.label }}</strong>
              <small>{{ ({ pending: '等待执行', running: '正在执行', completed: '执行完成', waiting: '等待操作员', failed: '执行失败' } as Record<string, string>)[data.status] }}</small>
            </div>
          </div>
        </template>
      </VueFlow>
    </div>
  </section>
</template>
