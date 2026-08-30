<script setup lang="ts">
import { computed, watch } from 'vue'
import { Microphone } from '@element-plus/icons-vue'
import './voice-assistant.css'
import { useVoiceSession } from '../../composables/useVoiceSession'
import { useGuidedLaunch } from '../../composables/useGuidedLaunch'
import type { ExecutionTrace } from '../../composables/useExecutionTrace'
import type { GuidedLaunchUpdate, ScenarioLaunchRequest } from '../../types/workbench'

const props = withDefaults(defineProps<{
  trace: ExecutionTrace
  active?: boolean
  launchRequest?: ScenarioLaunchRequest | null
}>(), {
  active: true,
  launchRequest: null,
})
const emit = defineEmits<{ 'launch-status': [update: GuidedLaunchUpdate] }>()

const {
  voicePhase,
  connectionPhase,
  partialTranscript,
  finalTranscript,
  answerText,
  toolEvents,
  errorMessage,
  runId,
  prepare,
  toggleMicrophone,
  close,
} = useVoiceSession()

useGuidedLaunch({
  active: () => props.active,
  request: () => props.launchRequest,
  scenarioId: 'VOICE_ASSISTANT',
  start: async () => {
    await prepare()
    return { state: 'ready', message: '语音链路已就绪，请点击麦克风授权并开始提问' }
  },
  onUpdate: (update) => emit('launch-status', update),
})

// 共享统一轨迹：语音会话创建后订阅自己的 runId。
watch(
  [() => props.active, () => runId.value],
  ([active, id]) => {
    if (active && id) props.trace.subscribe(id)
  },
  { immediate: true },
)

// 离开展台即挂断：发送 CLOSE_SESSION 并释放麦克风轨道。
watch(
  () => props.active,
  (active) => {
    if (!active) close()
  },
)

const phaseLabel = computed(() => {
  switch (voicePhase.value) {
    case 'LISTENING':
      return '正在聆听，说完请再次点击提交'
    case 'ASR_FINALIZED':
    case 'REASONING':
      return '正在理解您的问题…'
    case 'TOOL_CALLING':
      return '正在查询园区只读数据…'
    case 'ANSWER_STREAMING':
      return '回答生成中…'
    case 'SPEAKING':
      return '正在语音播报（点击麦克风可打断）'
    case 'ERROR':
      return '上一轮失败，可点击麦克风重试'
    case 'IDLE':
      return '点击麦克风开始提问'
    default:
      return connectionPhase.value === 'connecting' ? '正在建立语音连接…' : '点击麦克风开始提问'
  }
})

const micActive = computed(
  () => voicePhase.value === 'LISTENING',
)
const interruptible = computed(
  () =>
    voicePhase.value === 'SPEAKING' ||
    voicePhase.value === 'ANSWER_STREAMING' ||
    voicePhase.value === 'REASONING' ||
    voicePhase.value === 'TOOL_CALLING',
)

const ttsActive = computed(() => voicePhase.value === 'SPEAKING')

async function onMicClick(): Promise<void> {
  await toggleMicrophone()
}

function retry(): Promise<void> {
  return onMicClick()
}
</script>

<template>
  <div class="voice-page" aria-label="实时语音助手">
    <section class="hero-row voice-hero">
      <div>
        <span class="eyebrow">实时语音 · 05</span>
        <h2>开口即问<br /><em>答案有据可查</em></h2>
        <p class="hero-copy">
          流式识别、真实只读工具查询与流式播报全程可见；回答必须通过证据校验才会播出。
        </p>
      </div>
      <div class="hero-metrics">
        <div><strong>{{ partialTranscript ? '流式' : finalTranscript ? '已完成' : '—' }}</strong><span>识别状态</span></div>
        <div><strong>{{ toolEvents.length || '—' }}</strong><span>工具调用</span></div>
        <div><strong>{{ answerText.length || '—' }}</strong><span>回答字数</span></div>
      </div>
    </section>

    <section class="voice-layout">
      <div class="voice-primary">
        <section class="panel voice-mic-panel">
          <button
            type="button"
            class="voice-mic-button"
            :class="{ active: micActive, interruptible }"
            :aria-pressed="micActive"
            data-testid="voice-mic"
            @click="onMicClick"
          >
            <span class="voice-mic-icon"><el-icon><Microphone /></el-icon></span>
            <span v-if="interruptible" class="voice-mic-label">点击打断并继续提问</span>
            <span v-else-if="micActive" class="voice-mic-label">点击结束输入</span>
            <span v-else class="voice-mic-label">点击开始提问</span>
          </button>
          <p class="voice-phase-label" data-testid="voice-phase">{{ phaseLabel }}</p>
          <p v-if="ttsActive" class="voice-tts-state" data-testid="voice-tts-state">
            <i></i> TTS 播报中
          </p>
          <div v-if="errorMessage" class="voice-error" data-testid="voice-error" role="alert">
            <span>{{ errorMessage }}</span>
            <button type="button" data-testid="voice-retry" @click="retry">重试</button>
          </div>
        </section>

        <section class="panel voice-transcript-panel">
          <div class="section-heading"><div><span class="eyebrow">实时转写</span><h2>你说的话</h2></div></div>
          <p v-if="partialTranscript" class="voice-partial" data-testid="voice-partial">{{ partialTranscript }}…</p>
          <p v-if="finalTranscript" class="voice-final" data-testid="voice-final">{{ finalTranscript }}</p>
          <p v-if="!partialTranscript && !finalTranscript" class="voice-empty">说话内容会实时显示在这里。</p>
        </section>

        <section class="panel voice-answer-panel">
          <div class="section-heading"><div><span class="eyebrow">回答字幕</span><h2>助手回答</h2></div>
            <span v-if="ttsActive" class="live-indicator"><i></i>播报中</span>
          </div>
          <p v-if="answerText" class="voice-answer-text" data-testid="voice-answer">{{ answerText }}</p>
          <p v-else class="voice-empty">回答按句流式出现，且仅在通过证据校验后播报。</p>
          <div v-if="toolEvents.length" class="voice-tools" data-testid="voice-tools">
            <span>本轮工具调用：</span>
            <div class="evidence-list">
              <span v-for="(event, index) in toolEvents" :key="`${event.toolName}-${index}`" class="voice-tool-card">
                {{ event.toolName }} · {{ event.phase === 'STARTED' ? '查询中' : '完成' }}
              </span>
            </div>
          </div>
        </section>
      </div>

      <aside class="panel voice-side-panel">
        <div class="section-heading"><div><span class="eyebrow">共享轨迹</span><h2>本次会话事件</h2></div>
          <span class="live-indicator"><i></i>实时</span>
        </div>
        <ol v-if="trace.events.value.length" class="voice-trace-list">
          <li v-for="event in trace.events.value.slice(-8)" :key="event.eventId">
            <strong>{{ event.eventType }}</strong>
            <span>{{ event.safeSummary }}</span>
          </li>
        </ol>
        <p v-else class="voice-empty">会话状态与工具调用会同步到统一执行轨迹。</p>
        <p class="voice-privacy-note">原始音频不落盘、不进事件流；本页面仅展示脱敏后的转写与结论。</p>
      </aside>
    </section>
  </div>
</template>
