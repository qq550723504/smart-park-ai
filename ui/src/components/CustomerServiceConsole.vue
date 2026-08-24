<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { askCustomerService, getCustomerConversation, listCustomerTickets, replyCustomerSession, submitFeedback, updateCustomerTicket } from '../services/workflowApi'
import type { CustomerConversationResponse, CustomerServiceResponse, CustomerTicketResponse, DemoRole } from '../types/workflow'
import { customerIntentLabel, customerTicketStatusLabel } from '../utils/labels'

const props = defineProps<{ role: DemoRole }>()
const question = ref('')
const loading = ref(false)
const messages = ref<Array<{ role: 'user' | 'assistant'; text: string; result?: CustomerServiceResponse }>>([])
const tickets = ref<CustomerServiceResponse[]>([])
const sessionId = ref('')
const conversation = ref<CustomerConversationResponse | null>(null)
const suggestions = ['访客停车怎么收费？', '访客如何预约进入园区？', '可以查询公共区域能耗吗？', 'A1 洗手间漏水，需要报修']

async function loadTickets() {
  if (!['CUSTOMER_AGENT', 'ADMIN'].includes(props.role)) {
    tickets.value = []
    return
  }
  try { tickets.value = await listCustomerTickets(props.role) }
  catch { tickets.value = [] }
}

async function rate(sessionId: string, rating: 'HELPFUL' | 'NOT_HELPFUL') {
  try {
    await submitFeedback('CUSTOMER_SESSION', sessionId, rating, props.role)
    ElMessage.success('反馈已记录')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '反馈提交失败') }
}

async function advance(ticket: CustomerTicketResponse) {
  const next: Record<string, string> = { WAITING_AGENT: 'ASSIGNED', ASSIGNED: 'IN_PROGRESS', IN_PROGRESS: 'RESOLVED', RESOLVED: 'CLOSED' }
  if (!next[ticket.status]) return
  try {
    await updateCustomerTicket(ticket.id, next[ticket.status], props.role)
    await loadTickets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单更新失败')
  }
}

async function ask(text = question.value) {
  const normalized = text.trim()
  if (!normalized || loading.value) return
  messages.value.push({ role: 'user', text: normalized })
  question.value = ''
  loading.value = true
  try {
    const result = sessionId.value
      ? await replyCustomerSession(sessionId.value, normalized, crypto.randomUUID())
      : await askCustomerService(normalized, crypto.randomUUID())
    sessionId.value = result.sessionId
    messages.value.push({ role: 'assistant', text: result.answer, result })
    conversation.value = await getCustomerConversation(result.sessionId)
    await loadTickets()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '客服请求失败')
  } finally {
    loading.value = false
  }
}

watch(() => props.role, loadTickets)
onMounted(loadTickets)
</script>

<template>
  <section class="customer-console">
    <aside class="panel customer-sidebar">
      <div class="section-heading"><div><span class="eyebrow">客服工作台</span><h2>园区客服</h2></div><span class="live-indicator"><i></i>在线</span></div>
      <div class="service-metrics"><div><strong>4</strong><span>演示意图</span></div><div><strong>安全</strong><span>知识引用</span></div></div>
      <h3>快捷咨询</h3>
      <button v-for="item in suggestions" :key="item" type="button" class="suggestion" @click="ask(item)">{{ item }}</button>
      <div class="privacy-note"><strong>数据边界</strong><span>对话仅使用本地模拟知识。请勿输入身份证、手机号或其他个人敏感信息。</span></div>
    </aside>

    <section class="panel chat-panel">
      <div class="section-heading compact"><div><span class="eyebrow">客服会话</span><h2>服务会话</h2></div><span class="count-badge">{{ messages.length }} 条消息</span></div>
      <div class="chat-stream">
        <div v-if="messages.length === 0" class="chat-empty"><strong>您好，这里是园区客服</strong><span>可以咨询停车、访客通行、公共区域能耗或提交设施报修。</span></div>
        <article v-for="(message, index) in messages" :key="index" :class="['chat-message', message.role]">
          <span class="message-role">{{ message.role === 'user' ? '访客' : '客服助手' }}</span>
          <p>{{ message.text }}</p>
          <div v-if="message.result" class="answer-meta">
            <span>意图 {{ customerIntentLabel(message.result.intent) }}</span>
            <span v-if="message.result.knowledgeSources.length">知识来源 {{ message.result.knowledgeSources.join(' / ') }}</span>
          </div>
          <div v-if="message.result?.knowledgeCitations?.length" class="knowledge-citations">
            <span class="knowledge-citations-label">检索依据</span>
            <div v-for="citation in message.result.knowledgeCitations" :key="citation.documentId" class="knowledge-citation">
              <strong>{{ citation.title }}</strong>
              <small>{{ citation.documentId }} · {{ Math.round(citation.score * 100) }}%</small>
            </div>
          </div>
          <div v-if="message.result && ['CUSTOMER_AGENT', 'ADMIN'].includes(role)" class="feedback-actions"><button type="button" @click="rate(message.result.sessionId, 'HELPFUL')">有帮助</button><button type="button" @click="rate(message.result.sessionId, 'NOT_HELPFUL')">无帮助</button></div>
          <div v-if="message.result?.ticket" class="ticket-strip">
            <div><span>人工客服工单</span><strong>{{ message.result.ticket.id }}</strong></div>
            <div><span>状态</span><strong>等待客服接入</strong></div>
            <div><span>安全摘要</span><strong>{{ message.result.ticket.safeSummary }}</strong></div>
          </div>
        </article>
        <div v-if="loading" class="chat-message assistant loading-message">正在检索园区知识...</div>
      </div>
      <div v-if="conversation?.retrievals.length" class="retrieval-trace">
        <span>检索轨迹</span>
        <strong>{{ conversation.retrievals.at(-1)?.query || '无匹配意图' }}</strong>
        <small>{{ conversation.retrievals.at(-1)?.documentIds.join(' · ') || '未命中文档' }}</small>
      </div>
      <form class="chat-composer" @submit.prevent="ask()">
        <el-input v-model="question" maxlength="500" :disabled="Boolean(conversation?.humanHandoff)" :placeholder="conversation?.humanHandoff ? '当前会话已转人工' : '输入园区服务问题'" />
        <el-button type="primary" native-type="submit" :loading="loading" :disabled="!question.trim()">发送</el-button>
      </form>
    </section>

    <section v-if="['CUSTOMER_AGENT', 'ADMIN'].includes(role)" class="panel ticket-queue">
      <div class="section-heading compact"><div><span class="eyebrow">客服队列</span><h2>人工客服工单</h2></div><span class="count-badge">{{ tickets.length }} 条</span></div>
      <div class="ticket-table">
        <article v-for="item in tickets" :key="item.ticket!.id">
          <div><strong>{{ item.ticket!.id }}</strong><span>{{ item.ticket!.safeSummary }}</span></div>
          <el-tag size="small">{{ customerTicketStatusLabel(item.ticket!.status) }}</el-tag>
          <el-button v-if="!['CLOSED', 'CANCELLED'].includes(item.ticket!.status)" size="small" @click="advance(item.ticket!)">推进状态</el-button>
        </article>
        <el-empty v-if="tickets.length === 0" description="暂无人工工单" :image-size="54" />
      </div>
    </section>
  </section>
</template>
