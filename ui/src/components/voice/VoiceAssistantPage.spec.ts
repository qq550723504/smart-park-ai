import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import VoiceAssistantPage from './VoiceAssistantPage.vue'
import type { VoiceSessionBinding } from '../../composables/useVoiceSession'
import type { ExecutionTrace } from '../../composables/useExecutionTrace'
import type { ScenarioLaunchRequest } from '../../types/workbench'

const toggleMicrophone = vi.fn(async () => undefined)
const closeSession = vi.fn()
const subscribe = vi.fn()

function makeTrace(): ExecutionTrace {
  return {
    events: ref([]),
    status: ref('streaming'),
    error: ref(''),
    lastSequence: ref(0),
    isTerminal: ref(false),
    subscribe,
    reset: () => undefined,
  }
}

let binding: Record<string, unknown>

function mountPage(active = true, launchRequest: ScenarioLaunchRequest | null = null) {
  const trace = makeTrace()
  const wrapper = mount(VoiceAssistantPage, {
    props: { trace, active, launchRequest },
    global: {
      stubs: { teleport: true },
    },
  })
  return { wrapper, trace }
}

vi.mock('../../composables/useVoiceSession', async () => {
  return {
    useVoiceSession: vi.fn(() => binding as unknown as VoiceSessionBinding),
  }
})

beforeEach(() => {
  vi.clearAllMocks()
  binding = {
    voicePhase: ref<string | null>(null),
    connectionPhase: ref<'idle' | 'connecting' | 'connected' | 'failed'>('idle'),
    partialTranscript: ref(''),
    finalTranscript: ref(''),
    answerText: ref(''),
    toolEvents: ref([]),
    errorMessage: ref(''),
    sessionId: ref(null),
    runId: ref(null),
    prepare: vi.fn(async () => undefined),
    toggleMicrophone,
    close: closeSession,
  }
})

function text(wrapper: { find(selector: string): { text(): string } }, selector: string): string {
  return wrapper.find(selector).text().trim()
}

describe('VoiceAssistantPage', () => {
  it('renders idle copy and mic control before any interaction', () => {
    const { wrapper } = mountPage()
    expect(text(wrapper, '[data-testid="voice-mic"]')).toContain('点击开始提问')
    expect(text(wrapper, '[data-testid="voice-phase"]')).toContain('点击麦克风开始提问')
  })

  it('clicking the mic delegates to the composable without faking phase', async () => {
    const { wrapper } = mountPage()
    await wrapper.find('[data-testid="voice-mic"]').trigger('click')
    await nextTick()
    expect(toggleMicrophone).toHaveBeenCalledTimes(1)
  })

  it('shows live transcript driven by server frames', () => {
    ;(binding.partialTranscript as ReturnType<typeof ref>).value = '现在用了'
    ;(binding.finalTranscript as ReturnType<typeof ref>).value = '现在用了多少电'
    const { wrapper } = mountPage()

    expect(text(wrapper, '[data-testid="voice-partial"]')).toContain('现在用了')
    expect(text(wrapper, '[data-testid="voice-final"]')).toBe('现在用了多少电')
  })

  it('streams the answer text as subtitles', () => {
    ;(binding.answerText as ReturnType<typeof ref>).value = 'A2 表计当前用电 138 千瓦时。'
    const { wrapper } = mountPage()

    expect(text(wrapper, '[data-testid="voice-answer"]')).toContain('138')
  })

  it('renders tool cards with status only — never arguments or raw payloads', () => {
    ;(binding.toolEvents as ReturnType<typeof ref>).value = [
      { toolName: 'lookupEnergyConsumption', phase: 'STARTED' },
    ]
    const { wrapper } = mountPage()

    const html = wrapper.find('[data-testid="voice-tools"]').html()
    expect(html).toContain('lookupEnergyConsumption')
    expect(html).not.toContain('meterId=')
    expect(wrapper.text()).not.toContain('prompt')
  })

  it('marks speaking state with TTS indicator and interruptible mic label', () => {
    ;(binding.voicePhase as ReturnType<typeof ref>).value = 'SPEAKING'
    const { wrapper } = mountPage()

    expect(text(wrapper, '[data-testid="voice-phase"]')).toContain('点击麦克风可打断')
    expect(wrapper.find('[data-testid="voice-tts-state"]').exists()).toBe(true)
    expect(text(wrapper, '[data-testid="voice-mic"]')).toContain('点击打断并继续提问')
  })

  it('clicking during speaking triggers another toggle (backend interrupts output)', async () => {
    ;(binding.voicePhase as ReturnType<typeof ref>).value = 'SPEAKING'
    const { wrapper } = mountPage()

    await wrapper.find('[data-testid="voice-mic"]').trigger('click')

    expect(toggleMicrophone).toHaveBeenCalledTimes(1)
  })

  it('shows explicit errors with a retry action', async () => {
    ;(binding.errorMessage as ReturnType<typeof ref>).value = '回答校验未通过，本轮结束'
    const { wrapper } = mountPage()

    const errorBox = wrapper.find('[data-testid="voice-error"]')
    expect(errorBox.text()).toContain('回答校验未通过，本轮结束')

    await wrapper.find('[data-testid="voice-retry"]').trigger('click')
    expect(toggleMicrophone).toHaveBeenCalledTimes(1)
  })

  it('subscribes the shared trace rail to the voice run id while active', () => {
    ;(binding.runId as ReturnType<typeof ref>).value =
      '00000000-0000-0000-0000-00000000aaaa'
    mountPage(true)

    expect(subscribe).toHaveBeenCalledWith('00000000-0000-0000-0000-00000000aaaa')
  })

  it('closing the page sends CLOSE via the composable teardown', async () => {
    const { wrapper } = mountPage(true)

    await wrapper.setProps({ active: false })
    await nextTick()

    expect(closeSession).toHaveBeenCalled()
  })

  it('prepares guided voice without toggling the microphone', async () => {
    const prepare = vi.fn(async () => undefined)
    binding.prepare = prepare
    const { wrapper } = mountPage(true, {
      requestId: 23,
      mode: 'guided',
      scenarioId: 'VOICE_ASSISTANT',
      view: 'voice',
    })
    await flushPromises()
    expect(prepare).toHaveBeenCalledTimes(1)
    expect(toggleMicrophone).not.toHaveBeenCalled()
    expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({ state: 'ready' })
    wrapper.unmount()
  })
})
