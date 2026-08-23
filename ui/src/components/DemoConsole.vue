<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getAuditEntries, getKnowledge, getOperationsMetrics, getWorkflowObservability, injectDemoFault, setKnowledgeActive } from '../services/workflowApi'
import type { AuditEntry, DemoRole, KnowledgeMetadata, OperationsMetrics, WorkflowObservability, WorkflowResponse } from '../types/workflow'

const props = defineProps<{ workflow: WorkflowResponse | null; role: DemoRole }>()
const observation = ref<WorkflowObservability | null>(null)
const metrics = ref<OperationsMetrics | null>(null)
const audits = ref<AuditEntry[]>([])
const knowledge = ref<KnowledgeMetadata[]>([])

async function refresh() {
  try {
    metrics.value = await getOperationsMetrics()
    audits.value = props.role === 'ADMIN' ? await getAuditEntries(props.role) : []
    knowledge.value = props.role === 'ADMIN' ? await getKnowledge(props.role) : []
    observation.value = props.workflow ? await getWorkflowObservability(props.workflow.workflowId) : null
  } catch { observation.value = null }
}
async function toggleKnowledge(document: KnowledgeMetadata) {
  try {
    const updated = await setKnowledgeActive(document.id, !document.active, props.role)
    knowledge.value = knowledge.value.map(item => item.id === updated.id ? updated : item)
    await refresh()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '知识状态更新失败') }
}
async function inject() {
  try {
    await injectDemoFault('KNOWLEDGE_SEARCH', props.role)
    ElMessage.success('已注入：下一次知识检索将失败')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '故障注入失败') }
}
watch(() => [props.workflow?.eventSequence, props.role], refresh, { immediate: true })
</script>

<template>
  <section class="panel demo-console">
    <div><span class="eyebrow">OPERATIONS</span><strong>{{ metrics?.workflowCount ?? 0 }} / {{ metrics?.customerSessionCount ?? 0 }}</strong><small>工作流 / 客服会话 · 人工工单 {{ metrics?.humanTicketCount ?? 0 }}</small></div>
    <div><span class="eyebrow">KNOWLEDGE</span><strong>{{ metrics?.activeKnowledgeDocumentCount ?? 0 }} / {{ metrics?.knowledgeDocumentCount ?? 0 }}</strong><small>启用 / 全部文档</small></div>
    <div><span class="eyebrow">FEEDBACK</span><strong>{{ metrics?.positiveFeedbackCount ?? 0 }} / {{ metrics?.feedbackCount ?? 0 }}</strong><small>正向 / 全部反馈</small></div>
    <div><span class="eyebrow">AUDIT TRAIL</span><strong>{{ metrics?.auditEntryCount ?? 0 }}</strong><small>{{ audits.at(-1)?.action || (role === 'ADMIN' ? '暂无审计记录' : '管理员可查看明细') }}</small></div>
    <div><span class="eyebrow">TOOL CALLS</span><strong>{{ observation?.toolCalls ?? 0 }}</strong><small>{{ observation?.tools.join(' · ') || '尚无调用' }}</small></div>
    <div><span class="eyebrow">FAILURE DEMO</span><el-button size="small" :disabled="role !== 'ADMIN'" @click="inject">注入知识库故障</el-button><small>仅影响下一次检索</small></div>
    <div v-if="role === 'ADMIN'" class="knowledge-admin">
      <span class="eyebrow">KNOWLEDGE STATUS</span>
      <button v-for="item in knowledge" :key="item.id" type="button" :class="{ inactive: !item.active }" @click="toggleKnowledge(item)"><i></i>{{ item.id }}</button>
    </div>
  </section>
</template>
