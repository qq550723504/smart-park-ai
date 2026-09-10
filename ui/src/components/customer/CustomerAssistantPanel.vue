<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ChatDotRound, Close, Connection, Document, OfficeBuilding, Promotion, WarningFilled } from '@element-plus/icons-vue'
import { askCustomerService, getCustomerConversation, replyCustomerSession } from '../../services/workflowApi'
import type { OperationsCapabilities } from '../../services/workflowApi'
import type { CustomerAnalysisContext, CustomerPage } from '../../types/customer'
import type { CustomerConversationResponse, CustomerServiceResponse } from '../../types/workflow'
import { customerIntentLabel, customerTicketStatusLabel } from '../../utils/labels'
import { createRequestId } from '../../utils/requestId'
import './customer-assistant.css'

const props = withDefaults(defineProps<{
  open: boolean
  activePage: CustomerPage
  context: CustomerAnalysisContext | null
  answerMode?: OperationsCapabilities['customerAnswerMode'] | 'unknown'
  knowledgeMode?: OperationsCapabilities['knowledgeMode'] | 'unknown'
}>(), { answerMode: 'unknown', knowledgeMode: 'unknown' })

const emit = defineEmits<{
  close: []
  'open-analysis': [context: CustomerAnalysisContext]
}>()

type AssistantMessage = {
  role: 'user' | 'assistant'
  text: string
  result?: CustomerServiceResponse
}

const question = ref('')
const loading = ref(false)
const error = ref('')
const conversationWarning = ref('')
const messages = ref<AssistantMessage[]>([])
const sessionId = ref('')
const conversation = ref<CustomerConversationResponse | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
let requestGeneration = 0

const suggestions = [
  '访客停车怎么收费？',
  '访客如何预约进入园区？',
  '可以查询公共区域能耗吗？',
  '洗手间漏水，需要报修',
]

const pageLabel = computed(() => ({
  overview: '园区总览',
  analysis: '运营分析',
  'work-orders': '事件与工单',
  reports: '运营报告',
})[props.activePage])

const serviceMode = computed(() => {
  if (props.answerMode === 'dashscope') return '在线 AI 模式已配置'
  if (props.answerMode === 'mock') return '演示知识模式'
  return '服务能力待确认'
})

function reasonLabel(reason: CustomerServiceResponse['reason']): string {
  return ({
    SUPPORTED: '园区知识已支持',
    INSUFFICIENT_EVIDENCE: '知识不足，已按现有规则转人工',
    POLICY_LIMIT: '超出当前助手支持范围',
    RETRIEVAL_UNAVAILABLE: '园区知识暂不可用',
  })[reason]
}

function citationTitle(title: string): string {
  return ({
    'Visitor parking guide': '访客停车指南',
    'Visitor access guide': '访客入园指南',
    'Tenant energy service guide': '园区能耗服务指南',
    'Facility repair intake guide': '设施报修受理指南',
  } as Record<string, string>)[title] ?? title
}

function applySuggestion(text: string): void {
  if (loading.value) return
  question.value = text
  error.value = ''
  void nextTick(() => composer.value?.focus())
}

function applyContextDraft(): void {
  if (!props.context || loading.value) return
  question.value = `关于${props.context.buildingName}（${props.context.buildingId}），我想咨询园区服务支持范围。`
  error.value = ''
  void nextTick(() => composer.value?.focus())
}

async function send(): Promise<void> {
  const normalized = question.value.trim()
  if (!normalized || loading.value) return
  const generation = ++requestGeneration
  const previousSessionId = sessionId.value
  messages.value.push({ role: 'user', text: normalized })
  question.value = ''
  error.value = ''
  conversationWarning.value = ''
  loading.value = true
  try {
    const idempotencyKey = createRequestId()
    const result = previousSessionId
      ? await replyCustomerSession(previousSessionId, normalized, idempotencyKey)
      : await askCustomerService(normalized, idempotencyKey)
    if (generation !== requestGeneration) return
    sessionId.value = result.sessionId
    const answer = result.answer.trim()
    if (!answer) {
      question.value = normalized
      error.value = '服务未返回可展示内容，请保留问题后重试。'
      return
    }
    messages.value.push({ role: 'assistant', text: answer, result })
    try {
      const nextConversation = await getCustomerConversation(result.sessionId)
      if (generation === requestGeneration) conversation.value = nextConversation
    } catch {
      if (generation === requestGeneration) conversationWarning.value = '回答已收到，会话详情暂未同步；不会重复发送本次问题。'
    }
  } catch {
    if (generation !== requestGeneration) return
    question.value = normalized
    error.value = /报修|漏水|故障/.test(normalized)
      ? '报修请求未完成，请保留问题后重试；页面不会显示虚假工单。'
      : 'AI 服务或园区知识暂不可用，请保留问题后重试。'
  } finally {
    if (generation === requestGeneration) loading.value = false
  }
}

function close(): void {
  emit('close')
}

function resetForDemo(): void {
  requestGeneration += 1
  question.value = ''
  loading.value = false
  error.value = ''
  conversationWarning.value = ''
  messages.value = []
  sessionId.value = ''
  conversation.value = null
}

watch(() => props.open, (open) => {
  if (open) void nextTick(() => composer.value?.focus())
})

defineExpose({ resetForDemo })
</script>

<template>
  <div v-show="open" class="customer-assistant" data-customer-assistant @keydown.esc="close">
    <button type="button" class="customer-assistant__backdrop" aria-label="关闭 AI 助手" @click="close"></button>
    <section role="dialog" aria-modal="true" aria-labelledby="customer-assistant-title" class="customer-assistant__panel">
      <header class="customer-assistant__header">
        <span><ChatDotRound aria-hidden="true" /></span>
        <div>
          <p>园区客户服务</p>
          <h2 id="customer-assistant-title">AI 助手</h2>
        </div>
        <small>{{ serviceMode }}</small>
        <button type="button" data-close-assistant aria-label="关闭 AI 助手" @click="close"><Close aria-hidden="true" /></button>
      </header>

      <div class="customer-assistant__scope" role="status">
        <Connection aria-hidden="true" />
        <span>当前页面：<strong>{{ pageLabel }}</strong></span>
        <span v-if="context"><OfficeBuilding aria-hidden="true" />{{ context.buildingId }} · {{ context.buildingName }}</span>
      </div>

      <div class="customer-assistant__boundary">
        <strong>可以咨询停车、访客、公共区域能耗与设施报修</strong>
        <span>只有点击发送后才调用现有客服接口；页面上下文不会被助手自动读取或执行。</span>
        <button v-if="context" type="button" data-use-assistant-context @click="applyContextDraft">带入当前楼宇到问题草稿</button>
        <button v-if="context" type="button" data-assistant-open-analysis @click="emit('open-analysis', context)">前往该楼宇运营分析</button>
      </div>

      <div class="customer-assistant__suggestions" aria-label="推荐问题">
        <span>推荐问题</span>
        <button v-for="item in suggestions" :key="item" type="button" :disabled="loading" @click="applySuggestion(item)">{{ item }}</button>
      </div>

      <div class="customer-assistant__messages" aria-live="polite">
        <div v-if="messages.length === 0" class="customer-assistant__empty">
          <ChatDotRound aria-hidden="true" />
          <strong>输入问题后开始服务</strong>
          <span>不会自动发送，也不会把演示文案当作本次 AI 回答。</span>
        </div>
        <article v-for="(message, index) in messages" :key="`${message.role}:${index}`" :class="message.role">
          <span>{{ message.role === 'user' ? '你' : '园区助手' }}</span>
          <p>{{ message.text }}</p>
          <template v-if="message.result">
            <small>{{ customerIntentLabel(message.result.intent) }} · {{ reasonLabel(message.result.reason) }}</small>
            <div v-if="message.result.knowledgeCitations?.length" class="customer-assistant__citations">
              <strong><Document aria-hidden="true" />回答依据</strong>
              <span v-for="citation in message.result.knowledgeCitations" :key="citation.documentId">{{ citationTitle(citation.title) }}</span>
            </div>
            <div v-if="message.result.ticket" class="customer-assistant__ticket" data-assistant-ticket>
              <strong>报修工单 {{ message.result.ticket.id }}</strong>
              <span>{{ customerTicketStatusLabel(message.result.ticket.status) }} · {{ message.result.ticket.safeSummary }}</span>
              <small>工单已创建并等待人工处理，不表示问题已经解决。</small>
            </div>
            <p v-else-if="message.result.needsHuman" class="customer-assistant__handoff">已请求人工协助，但当前响应没有提供工单回执。</p>
          </template>
        </article>
        <div v-if="loading" class="customer-assistant__loading" role="status">正在调用现有园区服务，请稍候…</div>
      </div>

      <p v-if="error" class="customer-assistant__error" role="alert"><WarningFilled aria-hidden="true" />{{ error }}</p>
      <p v-if="conversationWarning" class="customer-assistant__warning" role="status">{{ conversationWarning }}</p>

      <form class="customer-assistant__composer" @submit.prevent="send">
        <label for="customer-assistant-question">你的问题</label>
        <textarea
          id="customer-assistant-question"
          ref="composer"
          v-model="question"
          maxlength="500"
          :disabled="loading || Boolean(conversation?.humanHandoff)"
          :placeholder="conversation?.humanHandoff ? '当前会话已转人工，请等待处理' : '输入园区咨询或报修问题'"
        ></textarea>
        <footer>
          <span>{{ question.length }} / 500 · 请勿输入个人敏感信息</span>
          <button type="submit" data-send-assistant :disabled="loading || !question.trim() || Boolean(conversation?.humanHandoff)">
            <Promotion aria-hidden="true" />{{ loading ? '发送中…' : '发送' }}
          </button>
        </footer>
      </form>
    </section>
  </div>
</template>
