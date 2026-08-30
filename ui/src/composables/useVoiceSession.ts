import { onScopeDispose, ref } from 'vue'
import type { Ref } from 'vue'
import {
  buildControlFrame,
  parseServerFrame,
  type ClientControlFrame,
  type VoiceSessionState,
} from '../types/voice'
import { createVoiceSession } from '../services/voiceApi'
import { PcmPlayer, type AudioBufferLike, type AudioContextLike, type PlaybackSourceLike } from '../audio/pcm-player'
import { VoicePcmCapture, browserPcmCaptureDeps, type PcmCaptureDeps } from '../audio/pcm-capture'

export interface WebSocketLike {
  send(data: string | ArrayBuffer): void
  close(): void
  onopen: (() => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
  onerror: (() => void) | null
  onclose: (() => void) | null
}

export interface PlayerLike {
  enqueue(sequence: number, data: ArrayBuffer): 'accepted' | 'dropped-late' | 'dropped-after-cancel'
  cancel(): void
  beginTurnReset(): void
}

export interface CaptureLike {
  start(stream: MediaStream, onChunk: (pcm: ArrayBuffer) => void): Promise<void>
  stop(): Promise<void>
}

export interface UseVoiceSessionDeps {
  api?: { createSession(): Promise<{ sessionId: string; runId: string; wsPath: string }> }
  openWebSocket?(url: string): WebSocketLike
  requestMicrophone?(): Promise<MediaStream>
  createPlayer?(): PlayerLike
  createCapture?(): CaptureLike
}

export interface ToolEventView {
  toolName: string
  phase: 'STARTED' | 'COMPLETED'
}

export interface VoiceSessionBinding {
  /** Server-driven only; local clicks never fake this. */
  voicePhase: Ref<VoiceSessionState | null>
  connectionPhase: Ref<'idle' | 'connecting' | 'connected' | 'failed'>
  partialTranscript: Ref<string>
  finalTranscript: Ref<string>
  answerText: Ref<string>
  toolEvents: Ref<ToolEventView[]>
  errorMessage: Ref<string>
  sessionId: Ref<string | null>
  /** Backend run id; feeds the shared unified execution trace rail. */
  runId: Ref<string | null>
  toggleMicrophone(): Promise<void>
  close(): void
}

function defaultOpenWebSocket(url: string): WebSocketLike {
  return new WebSocket(url) as unknown as WebSocketLike
}

async function defaultRequestMicrophone(): Promise<MediaStream> {
  return navigator.mediaDevices.getUserMedia({ audio: true })
}

/**
 * Realtime voice session composable. UI state is driven exclusively by server
 * SESSION_STATE frames — a local click only sends a control and waits for the
 * backend to confirm; failures surface verbatim instead of faking success.
 */
export function useVoiceSession(deps: UseVoiceSessionDeps = {}): VoiceSessionBinding {
  const api = deps.api ?? { createSession: () => createVoiceSession() }
  const openWebSocket = deps.openWebSocket ?? defaultOpenWebSocket
  const requestMicrophone = deps.requestMicrophone ?? defaultRequestMicrophone

  // 测试环境没有 AudioContext：浏览器实现按需惰性创建。
  let player: PlayerLike | null = null
  let capture: CaptureLike | null = null

  function ensurePlayer(): PlayerLike {
    if (!player) {
      player = deps.createPlayer ? deps.createPlayer() : new PcmPlayer(browserPlayerDeps())
    }
    return player
  }

  function ensureCapture(): CaptureLike {
    if (!capture) {
      capture = deps.createCapture
        ? deps.createCapture()
        : (new VoicePcmCapture(browserPcmCaptureDeps() as PcmCaptureDeps) as CaptureLike)
    }
    return capture
  }

  const voicePhase = ref<VoiceSessionState | null>(null)
  const connectionPhase = ref<'idle' | 'connecting' | 'connected' | 'failed'>('idle')
  const partialTranscript = ref('')
  const finalTranscript = ref('')
  const answerText = ref('')
  const toolEvents = ref<ToolEventView[]>([])
  const errorMessage = ref('')
  const sessionId = ref<string | null>(null)
  const runId = ref<string | null>(null)

  let socket: WebSocketLike | null = null
  let controlSequence = 0
  let stream: MediaStream | null = null
  let suppressAudio = false
  let intentionalClose = false
  let lifecycleGeneration = 0

  function messageId(prefix: string): string {
    return `${prefix}-${Date.now()}-${++controlSequence}`
  }

  function sendControl(type: Parameters<typeof buildControlFrame>[3]): void {
    if (!socket || !sessionId.value) return
    const frame = buildControlFrame(sessionId.value, messageId('ctl'), controlSequence, type)
    socket.send(JSON.stringify(frame))
  }

  function resetTurnDisplay(): void {
    partialTranscript.value = ''
    finalTranscript.value = ''
    answerText.value = ''
    toolEvents.value = []
  }

  async function ensureConnected(generation: number): Promise<boolean> {
    if (socket) return true
    connectionPhase.value = 'connecting'
    const created = await api.createSession()
    if (generation !== lifecycleGeneration) return false
    sessionId.value = created.sessionId
    runId.value = created.runId
    intentionalClose = false
    const currentSocket = openWebSocket(
      `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${created.wsPath}`,
    )
    socket = currentSocket
    currentSocket.onopen = () => {
      if (generation !== lifecycleGeneration || socket !== currentSocket) {
        currentSocket.close()
        return
      }
      connectionPhase.value = 'connected'
    }
    currentSocket.onerror = () => {
      if (generation !== lifecycleGeneration || socket !== currentSocket) return
      connectionPhase.value = 'failed'
      errorMessage.value = '语音连接失败，请重试'
    }
    currentSocket.onmessage = (event) => {
      if (generation !== lifecycleGeneration || socket !== currentSocket) return
      handleSocketMessage(event.data)
    }
    currentSocket.onclose = () => {
      if (socket === currentSocket) socket = null
      // Unexpected server-side drop surfaces as a failure; intentional
      // local close() must not overwrite a clean shutdown state.
      if (generation === lifecycleGeneration
        && !intentionalClose
        && connectionPhase.value === 'connected') {
        connectionPhase.value = 'failed'
        errorMessage.value = '语音连接已断开'
      }
    }
    await waitUntil(() => generation !== lifecycleGeneration
      || String(connectionPhase.value) !== 'connecting')
    if (generation !== lifecycleGeneration) return false
    if (String(connectionPhase.value) !== 'connected') {
      throw new Error(errorMessage.value || '语音连接未就绪')
    }
    return true
  }

  function waitUntil(condition: () => boolean): Promise<void> {
    if (condition()) return Promise.resolve()
    return new Promise((resolve, reject) => {
      const deadline = Date.now() + 5000
      const timer = setInterval(() => {
        if (condition()) {
          clearInterval(timer)
          resolve()
        } else if (Date.now() > deadline) {
          clearInterval(timer)
          reject(new Error('等待语音连接超时'))
        }
      }, 10)
      // Timeout must not leak a half-open socket.
      setTimeout(() => {
        clearInterval(timer)
      }, deadline - Date.now() + 100)
    })
  }

  function handleSocketMessage(data: unknown): void {
    if (typeof data === 'string') {
      applyServerFrame(parseServerFrame(data))
      return
    }
    handleBinaryAudio(data)
  }

  function applyServerFrame(frame: ReturnType<typeof parseServerFrame>): void {
    switch (frame.type) {
      case 'SESSION_STATE':
        voicePhase.value = frame.state
        if (frame.state === 'LISTENING') {
          suppressAudio = false
          ensurePlayer().beginTurnReset()
          resetTurnDisplay()
        }
        break
      case 'ASR_PARTIAL':
        partialTranscript.value = frame.text
        break
      case 'ASR_FINAL':
        finalTranscript.value = frame.text
        break
      case 'TOOL_EVENT':
        if (frame.phase === 'STARTED') {
          toolEvents.value = [...toolEvents.value, { toolName: frame.toolName, phase: 'STARTED' }]
        } else {
          toolEvents.value = toolEvents.value.map((entry, index) =>
            index === toolEvents.value.length - 1
              ? { ...entry, phase: 'COMPLETED' as const }
              : entry,
          )
        }
        break
      case 'ANSWER_DELTA':
        answerText.value += frame.delta
        break
      case 'AUDIO_CHUNK':
        // metadata only; raw PCM follows as binary
        break
      case 'ERROR':
        errorMessage.value = `${frame.userMessage}`
        // A rejected START_INPUT must not leave the post-cancel audio gate
        // closed forever — otherwise every later chunk would be dropped.
        suppressAudio = false
        break
    }
  }

  function handleBinaryAudio(data: unknown): void {
    if (suppressAudio) return // late chunk after mic-click interruption
    const buffer = data instanceof ArrayBuffer ? data : null
    if (!buffer || buffer.byteLength <= 4) return
    const view = new DataView(buffer)
    const sequence = view.getInt32(0, false)
    ensurePlayer().enqueue(sequence, buffer.slice(4))
  }

  async function startListening(generation: number): Promise<void> {
    // Request the microphone BEFORE telling the backend to start the turn:
    // a first-use permission prompt must not eat into the 10 s input budget.
    const requestedStream = await requestMicrophone()
    if (generation !== lifecycleGeneration) {
      requestedStream.getTracks?.().forEach((track) => track.stop())
      return
    }
    stream = requestedStream
    sendControl('START_INPUT')
    const currentCapture = ensureCapture()
    await currentCapture.start(requestedStream, (pcm) => {
      // 服务器确认 LISTENING 之前不发音频，避免中断窗口期被拒。
      if (generation === lifecycleGeneration && voicePhase.value === 'LISTENING') {
        socket?.send(pcm)
      }
    })
    if (generation !== lifecycleGeneration) {
      await currentCapture.stop().catch(() => undefined)
      requestedStream.getTracks?.().forEach((track) => track.stop())
      if (stream === requestedStream) stream = null
    }
  }

  async function stopInput(): Promise<void> {
    await capture?.stop().catch(() => undefined)
    stream?.getTracks?.().forEach((track) => track.stop())
    stream = null
  }

  /**
   * Mic click semantics per backend contract:
   * IDLE/ERROR → begin listening; LISTENING → commit;
   * any output stage → interrupt current output first, then listen again.
   */
  async function toggleMicrophone(): Promise<void> {
    const generation = lifecycleGeneration
    try {
      const connected = await ensureConnected(generation)
      if (!connected || generation !== lifecycleGeneration) return
      const phase = voicePhase.value as string | null
      switch (phase as VoiceSessionState | null) {
        case 'LISTENING':
          await stopInput()
          sendControl('COMMIT_INPUT')
          break
        case 'SPEAKING':
        case 'ANSWER_STREAMING':
        case 'REASONING':
        case 'TOOL_CALLING':
          suppressAudio = true
          ensurePlayer().cancel()
          await startListening(generation)
          break
        case 'IDLE':
        case 'ERROR':
        case 'ASR_FINALIZED':
        case null:
          errorMessage.value = ''
          await startListening(generation)
          break
      }
    } catch (ex) {
      if (generation !== lifecycleGeneration) return
      errorMessage.value = ex instanceof Error ? ex.message : String(ex)
      connectionPhase.value = 'failed'
    }
  }

  function close(): void {
    lifecycleGeneration++
    intentionalClose = true
    if (socket && sessionId.value) {
      const frame = buildControlFrame(
        sessionId.value,
        messageId('ctl'),
        ++controlSequence,
        'CLOSE_SESSION',
      )
      try {
        socket.send(JSON.stringify(frame))
      } catch {
        // socket already gone
      }
    }
    capture?.stop().catch(() => undefined)
    stream?.getTracks?.().forEach((track) => track.stop())
    stream = null
    socket?.close()
    socket = null
    sessionId.value = null
    runId.value = null
    connectionPhase.value = 'idle'
    voicePhase.value = null
    suppressAudio = false
    player?.cancel()
    player?.beginTurnReset()
  }

  onScopeDispose(close)

  return {
    voicePhase,
    connectionPhase,
    partialTranscript,
    finalTranscript,
    answerText,
    toolEvents,
    errorMessage,
    sessionId,
    runId,
    toggleMicrophone,
    close,
  }
}

/** Browser wiring for the ordered PCM player at 16 kHz. */
function browserPlayerDeps() {
  const context = new AudioContext({ sampleRate: 16000 })
  return {
    context,
    createSource(buffer: AudioBufferLike): PlaybackSourceLike {
      const source = context.createBufferSource()
      source.buffer = buffer as AudioBuffer
      return source
    },
  }
}
