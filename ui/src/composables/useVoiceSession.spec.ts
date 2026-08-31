import { beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useVoiceSession } from './useVoiceSession'
import { useGuidedLaunch } from './useGuidedLaunch'
import type { UseVoiceSessionDeps, WebSocketLike } from './useVoiceSession'
import type { VoiceServerFrame } from '../types/voice'
import type { GuidedLaunchUpdate, ScenarioLaunchRequest } from '../types/workbench'

class FakeWebSocket implements WebSocketLike {
  sent: Array<string | ArrayBuffer> = []
  closed = false
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null

  constructor(public url: string) {}

  send(data: string | ArrayBuffer): void {
    this.sent.push(data)
  }

  close(): void {
    this.closed = true
    this.onclose?.()
  }

  /** Simulates the transport finishing the handshake. */
  open(): void {
    this.onopen?.()
  }

  fail(): void {
    this.onerror?.()
  }

  emitText(payload: unknown): void {
    const raw = typeof payload === 'string' ? payload : JSON.stringify(payload)
    this.onmessage?.({ data: raw })
  }

  emitBinary(sequence: number, samples: number[]): void {
    const buffer = new ArrayBuffer(4 + samples.length * 2)
    new DataView(buffer).setInt32(0, sequence, false)
    new Int16Array(buffer, 4).set(samples)
    this.onmessage?.({ data: buffer })
  }
}

class FakePlayer {
  acceptedChunks: number[] = []
  cancelCount = 0
  resetCount = 0

  enqueue(sequence: number): 'accepted' | 'dropped-late' | 'dropped-after-cancel' {
    this.acceptedChunks.push(sequence)
    return 'accepted'
  }

  cancel(): void {
    this.cancelCount++
  }

  beginTurnReset(): void {
    this.resetCount++
  }
}

class FakeCapture {
  started = 0
  stoppedCount = 0
  onChunk: ((pcm: ArrayBuffer) => void) | null = null

  async start(_stream: MediaStream, onChunk: (pcm: ArrayBuffer) => void): Promise<void> {
    this.started++
    this.onChunk = onChunk
  }

  async stop(): Promise<void> {
    this.stoppedCount++
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function makeHarness(frameScript: VoiceServerFrame[] = []) {
  const fakeWs = new FakeWebSocket('')
  const fakePlayer = new FakePlayer()
  const fakeCapture = new FakeCapture()
  const fakeTrack = { stopped: 0, stop() { this.stopped++ } }
  let microphoneRequests = 0

  const deps: UseVoiceSessionDeps = {
    api: {
      createSession: async () => ({
        sessionId: 'vs-test',
        runId: '00000000-0000-0000-0000-00000000aaaa',
        wsPath: '/ws/voice/sessions/vs-test',
      }),
    },
    // 握手在工厂内立即完成，避免测试死等连接。
    openWebSocket: (url) => {
      Object.assign(fakeWs, new FakeWebSocket(url))
      queueMicrotask(() => fakeWs.open())
      return fakeWs as WebSocketLike
    },
    requestMicrophone: async () => {
      microphoneRequests++
      return { getTracks: () => [fakeTrack] } as unknown as MediaStream
    },
    createPlayer: () => fakePlayer,
    createCapture: () => fakeCapture,
  }

  const binding = useVoiceSession(deps)

  function serverSendsState(state: string): void {
    fakeWs.emitText({
      type: 'SESSION_STATE',
      sessionId: 'vs-test',
      messageId: `m-${frameScript.length}-${Math.random()}`,
      sequence: frameScript.length + 10,
      state,
      turnId: `turn-${frameScript.length}`,
    })
  }

  function lastSentControl(type?: string): Record<string, unknown> | undefined {
    for (let i = fakeWs.sent.length - 1; i >= 0; i--) {
      const sent = fakeWs.sent[i]
      if (typeof sent === 'string') {
        const parsed = JSON.parse(sent) as Record<string, unknown>
        if (!type || parsed['type'] === type) return parsed
      }
    }
    return undefined
  }

  return {
    binding, fakeWs, fakePlayer, fakeCapture, fakeTrack, microphoneRequests: () => microphoneRequests,
    serverSendsState, lastSentControl,
  }
}

describe('useVoiceSession', () => {
  beforeEach(() => {
    // 每个用例独立的模块级状态由组合式函数闭包保证；这里仅作占位对齐。
  })

  it('clicking the mic starts listening only after the server confirms LISTENING', async () => {
    const h = makeHarness()

    await h.binding.toggleMicrophone()

    expect(h.binding.sessionId.value).toBe('vs-test')
    expect(h.lastSentControl('START_INPUT')).toBeDefined()
    expect(h.binding.connectionPhase.value).toBe('connected')
    // 本地点击不伪设状态：服务器确认前 phase 保持 null。
    expect(h.binding.voicePhase.value).toBeNull()

    h.serverSendsState('LISTENING')
    expect(h.binding.voicePhase.value).toBe('LISTENING')

    // LISTENING 之后采集到的音频才会上行。
    h.fakeCapture.onChunk?.(new Int16Array([1]).buffer as ArrayBuffer)
    const binarySends = h.fakeWs.sent.filter((sent) => sent instanceof ArrayBuffer)
    expect(binarySends).toHaveLength(1)
  })

  it('prepares a backend session without requesting microphone access', async () => {
    const harness = makeHarness()
    await harness.binding.prepare()

    expect(harness.fakeCapture.started).toBe(0)
    expect(harness.microphoneRequests()).toBe(0)
    expect(harness.binding.connectionPhase.value).toBe('connected')
  })

  it('creates a fresh connection when preparation follows a transport failure', async () => {
    const sockets: FakeWebSocket[] = []
    let sessionsCreated = 0
    const binding = useVoiceSession({
      api: {
        createSession: async () => {
          sessionsCreated++
          return {
            sessionId: `vs-retry-${sessionsCreated}`,
            runId: `00000000-0000-0000-0000-${String(sessionsCreated).padStart(12, '0')}`,
            wsPath: `/ws/voice/sessions/vs-retry-${sessionsCreated}`,
          }
        },
      },
      openWebSocket: (url) => {
        const socket = new FakeWebSocket(url)
        sockets.push(socket)
        queueMicrotask(() => socket.open())
        return socket
      },
    })

    await binding.prepare()
    sockets[0]?.fail()
    expect(binding.connectionPhase.value).toBe('failed')

    await binding.prepare()

    expect(sessionsCreated).toBe(2)
    expect(sockets).toHaveLength(2)
    expect(sockets[0]?.closed).toBe(true)
    expect(binding.connectionPhase.value).toBe('connected')
    expect(binding.errorMessage.value).toBe('')
  })

  it('reuses a connected transport after microphone permission fails', async () => {
    const sockets: FakeWebSocket[] = []
    let sessionsCreated = 0
    let microphoneAttempts = 0
    const binding = useVoiceSession({
      api: {
        createSession: async () => {
          sessionsCreated++
          return {
            sessionId: 'vs-microphone-retry',
            runId: '00000000-0000-0000-0000-000000000222',
            wsPath: '/ws/voice/sessions/vs-microphone-retry',
          }
        },
      },
      openWebSocket: (url) => {
        const socket = new FakeWebSocket(url)
        sockets.push(socket)
        queueMicrotask(() => socket.open())
        return socket
      },
      requestMicrophone: async () => {
        microphoneAttempts++
        if (microphoneAttempts === 1) throw new Error('microphone permission denied')
        return { getTracks: () => [] } as unknown as MediaStream
      },
      createCapture: () => new FakeCapture(),
    })

    await binding.toggleMicrophone()
    await binding.toggleMicrophone()

    expect(sessionsCreated).toBe(1)
    expect(sockets).toHaveLength(1)
    expect(binding.connectionPhase.value).toBe('connected')
    expect(binding.errorMessage.value).toBe('')
  })

  it('cancels the server turn when capture startup fails before retrying', async () => {
    const h = makeHarness()
    let captureAttempts = 0
    h.fakeCapture.start = async (_stream, onChunk) => {
      captureAttempts++
      if (captureAttempts === 1) throw new Error('capture startup failed')
      h.fakeCapture.started++
      h.fakeCapture.onChunk = onChunk
    }

    await h.binding.toggleMicrophone()
    h.serverSendsState('LISTENING')
    h.serverSendsState('IDLE')
    await h.binding.toggleMicrophone()

    const controlTypes = h.fakeWs.sent
      .filter((sent): sent is string => typeof sent === 'string')
      .map((sent) => (JSON.parse(sent) as { type: string }).type)
    expect(controlTypes).toEqual(['START_INPUT', 'INTERRUPT_OUTPUT', 'START_INPUT'])
    expect(h.binding.connectionPhase.value).toBe('connected')
    expect(captureAttempts).toBe(2)
  })

  it('shares one pending handshake between prepare and an immediate microphone toggle', async () => {
    const pendingSession = deferred<{ sessionId: string; runId: string; wsPath: string }>()
    const sockets: FakeWebSocket[] = []
    const capture = new FakeCapture()
    let sessionsCreated = 0
    let microphoneRequests = 0
    const binding = useVoiceSession({
      api: {
        createSession: () => {
          sessionsCreated++
          return pendingSession.promise
        },
      },
      openWebSocket: (url) => {
        const socket = new FakeWebSocket(url)
        sockets.push(socket)
        return socket
      },
      requestMicrophone: async () => {
        microphoneRequests++
        return { getTracks: () => [] } as unknown as MediaStream
      },
      createCapture: () => capture,
    })

    const preparing = binding.prepare()
    const toggling = binding.toggleMicrophone()
    pendingSession.resolve({
      sessionId: 'vs-single-flight',
      runId: '00000000-0000-0000-0000-000000000111',
      wsPath: '/ws/voice/sessions/vs-single-flight',
    })
    await Promise.resolve()
    await Promise.resolve()

    expect(microphoneRequests).toBe(0)
    sockets.forEach((socket) => socket.open())
    await Promise.all([preparing, toggling])

    expect(sessionsCreated).toBe(1)
    expect(sockets).toHaveLength(1)
    expect(microphoneRequests).toBe(1)
    expect(capture.started).toBe(1)
    expect(binding.connectionPhase.value).toBe('connected')
  })

  it('retires a timed-out handshake before late open and creates one fresh retry connection', async () => {
    vi.useFakeTimers()
    const sockets: FakeWebSocket[] = []
    let sessionsCreated = 0
    const binding = useVoiceSession({
      api: {
        createSession: async () => {
          sessionsCreated++
          return {
            sessionId: `vs-timeout-${sessionsCreated}`,
            runId: `00000000-0000-0000-0000-${String(sessionsCreated).padStart(12, '0')}`,
            wsPath: `/ws/voice/sessions/vs-timeout-${sessionsCreated}`,
          }
        },
      },
      openWebSocket: (url) => {
        const socket = new FakeWebSocket(url)
        sockets.push(socket)
        return socket
      },
    })

    try {
      let firstFailure = ''
      void binding.prepare().catch((error: unknown) => {
        firstFailure = error instanceof Error ? error.message : String(error)
      })
      await vi.advanceTimersByTimeAsync(0)
      expect(sockets).toHaveLength(1)

      await vi.advanceTimersByTimeAsync(5000)

      expect(firstFailure).toBe('等待语音连接超时')
      expect(sockets[0]?.closed).toBe(true)
      expect(binding.connectionPhase.value).toBe('failed')

      sockets[0]?.open()
      expect(binding.connectionPhase.value).toBe('failed')

      const retryA = binding.prepare()
      const retryB = binding.prepare()
      await vi.advanceTimersByTimeAsync(0)
      expect(sessionsCreated).toBe(2)
      expect(sockets).toHaveLength(2)

      sockets[1]?.open()
      await vi.advanceTimersByTimeAsync(10)
      await Promise.all([retryA, retryB])
      expect(binding.connectionPhase.value).toBe('connected')
    } finally {
      binding.close()
      vi.useRealTimers()
    }
  })

  it('commit sends COMMIT_INPUT and partial/final frames feed the transcript', async () => {
    const h = makeHarness()
    await h.binding.toggleMicrophone()
    h.serverSendsState('LISTENING')

    h.fakeWs.emitText({
      type: 'ASR_PARTIAL', sessionId: 'vs-test', messageId: 'm2', sequence: 2, text: '现在用',
    })
    expect(h.binding.partialTranscript.value).toBe('现在用')

    h.fakeWs.emitText({
      type: 'ASR_FINAL', sessionId: 'vs-test', messageId: 'm3', sequence: 3, text: '现在用了多少电',
    })
    expect(h.binding.finalTranscript.value).toBe('现在用了多少电')

    await h.binding.toggleMicrophone() // 第二次点击：提交输入
    expect(h.lastSentControl('COMMIT_INPUT')).toBeDefined()
    expect(h.fakeCapture.stoppedCount).toBe(1)
    expect(h.fakeTrack.stopped).toBe(1)
  })

  it('answer deltas append in order and tool events come from backend facts', async () => {
    const h = makeHarness()
    await h.binding.toggleMicrophone()

    h.fakeWs.emitText({
      type: 'SESSION_STATE', sessionId: 'vs-test', messageId: 'm1', sequence: 1,
      state: 'TOOL_CALLING', turnId: 'turn-1',
    })
    h.fakeWs.emitText({
      type: 'TOOL_EVENT', sessionId: 'vs-test', messageId: 'm2', sequence: 2,
      toolName: 'lookupEnergyConsumption', phase: 'STARTED', argumentSummary: '',
    })
    h.fakeWs.emitText({
      type: 'TOOL_EVENT', sessionId: 'vs-test', messageId: 'm3', sequence: 3,
      toolName: 'lookupEnergyConsumption', phase: 'COMPLETED', argumentSummary: 'ok',
    })
    for (const delta of ['A2 表计', '当前用电 138 千瓦时。']) {
      h.fakeWs.emitText({
        type: 'ANSWER_DELTA', sessionId: 'vs-test', messageId: `d-${delta}`, sequence: 4, delta,
      })
    }

    // 同一工具调用在展台上是一行状态流转：STARTED → COMPLETED。
    expect(h.binding.toolEvents.value).toEqual([
      { toolName: 'lookupEnergyConsumption', phase: 'COMPLETED' },
    ])
    expect(h.binding.answerText.value).toBe('A2 表计当前用电 138 千瓦时。')
  })

  it('mic click during speaking interrupts playback then restarts listening', async () => {
    const h = makeHarness()
    await h.binding.toggleMicrophone()
    h.serverSendsState('LISTENING')
    await h.binding.toggleMicrophone() // commit
    h.serverSendsState('ANSWER_STREAMING')
    h.serverSendsState('SPEAKING')

    // 播放中的音频块先入队。
    h.fakeWs.emitBinary(1, [10, 20])
    expect(h.fakePlayer.acceptedChunks).toEqual([1])

    await h.binding.toggleMicrophone() // 点击麦克风 → 先中断再听

    expect(h.fakePlayer.cancelCount).toBeGreaterThanOrEqual(1)
    expect(h.lastSentControl('START_INPUT')).toBeDefined()
    // 中断确认前到达的晚到块被丢弃。
    h.fakeWs.emitBinary(2, [30])
    expect(h.fakePlayer.acceptedChunks).toEqual([1])

    h.serverSendsState('LISTENING') // 服务器确认新一轮监听后恢复接收
    h.fakeWs.emitBinary(1, [40]) // 新一轮从 1 开始编号
    expect(h.fakePlayer.acceptedChunks).toEqual([1, 1])
  })

  it('connection errors surface verbatim instead of faking success', async () => {
    const deps: UseVoiceSessionDeps = {
      api: {
        createSession: async () => {
          throw new Error('语音会话请求失败（500）')
        },
      },
    }
    const binding = useVoiceSession(deps)

    await binding.toggleMicrophone()

    expect(binding.errorMessage.value).toContain('500')
    expect(binding.voicePhase.value).toBeNull()
  })

  it('close sends CLOSE_SESSION and releases capture tracks', async () => {
    const h = makeHarness()
    await h.binding.toggleMicrophone()
    h.serverSendsState('LISTENING')

    h.binding.close()

    expect(h.lastSentControl('CLOSE_SESSION')).toBeDefined()
    expect(h.fakeWs.closed).toBe(true)
    expect(h.fakeCapture.stoppedCount).toBe(1)
    expect(h.fakeTrack.stopped).toBe(1)
  })

  it('does not open a socket when close wins an in-flight session creation', async () => {
    let resolveSession!: (value: { sessionId: string; runId: string; wsPath: string }) => void
    const sessionPromise = new Promise<{ sessionId: string; runId: string; wsPath: string }>((resolve) => {
      resolveSession = resolve
    })
    let openedSockets = 0
    const binding = useVoiceSession({
      api: { createSession: () => sessionPromise },
      openWebSocket: () => {
        openedSockets++
        return new FakeWebSocket('ws://late')
      },
      requestMicrophone: async () => ({ getTracks: () => [] }) as unknown as MediaStream,
    })

    const connecting = binding.toggleMicrophone()
    binding.close()
    resolveSession({
      sessionId: 'vs-late',
      runId: '00000000-0000-0000-0000-00000000bbbb',
      wsPath: '/ws/voice/sessions/vs-late',
    })
    await connecting

    expect(openedSockets).toBe(0)
    expect(binding.connectionPhase.value).toBe('idle')
    expect(binding.errorMessage.value).toBe('')
  })

  it('does not report guided voice ready when close wins the pending socket handshake', async () => {
    const socket = new FakeWebSocket('')
    const active = ref(true)
    const request = ref<ScenarioLaunchRequest | null>({
      requestId: 61, mode: 'guided', scenarioId: 'VOICE_ASSISTANT', view: 'voice',
    })
    const updates: GuidedLaunchUpdate[] = []
    const scope = effectScope()
    let binding!: ReturnType<typeof useVoiceSession>
    scope.run(() => {
      binding = useVoiceSession({
        api: {
          createSession: async () => ({
            sessionId: 'vs-pending',
            runId: '00000000-0000-0000-0000-00000000dddd',
            wsPath: '/ws/voice/sessions/vs-pending',
          }),
        },
        openWebSocket: () => socket,
      })
      useGuidedLaunch({
        active: () => active.value,
        request: () => request.value,
        scenarioId: 'VOICE_ASSISTANT',
        start: async () => {
          await binding.prepare()
          return { state: 'ready', message: '语音链路已就绪' }
        },
        onUpdate: (update) => updates.push(update),
      })
    })

    await Promise.resolve()
    active.value = false
    binding.close()
    socket.open()
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(updates.some((update) => update.state === 'ready')).toBe(false)
    expect(updates.some((update) => update.state === 'failed')).toBe(true)
    scope.stop()
  })

  it('releases a microphone stream that resolves after close', async () => {
    let resolveMicrophone!: (stream: MediaStream) => void
    let microphoneRequested!: () => void
    const requested = new Promise<void>((resolve) => { microphoneRequested = resolve })
    const microphonePromise = new Promise<MediaStream>((resolve) => { resolveMicrophone = resolve })
    const fakeWs = new FakeWebSocket('')
    const fakeCapture = new FakeCapture()
    const fakeTrack = { stopped: 0, stop() { this.stopped++ } }
    const binding = useVoiceSession({
      api: {
        createSession: async () => ({
          sessionId: 'vs-mic-late',
          runId: '00000000-0000-0000-0000-00000000cccc',
          wsPath: '/ws/voice/sessions/vs-mic-late',
        }),
      },
      openWebSocket: (url) => {
        Object.assign(fakeWs, new FakeWebSocket(url))
        queueMicrotask(() => fakeWs.open())
        return fakeWs
      },
      requestMicrophone: () => {
        microphoneRequested()
        return microphonePromise
      },
      createCapture: () => fakeCapture,
    })

    const starting = binding.toggleMicrophone()
    await requested
    binding.close()
    resolveMicrophone({ getTracks: () => [fakeTrack] } as unknown as MediaStream)
    await starting

    expect(fakeTrack.stopped).toBe(1)
    expect(fakeCapture.started).toBe(0)
    expect(binding.connectionPhase.value).toBe('idle')
  })

  it('isolates a pending capture startup from immediate close and reentry', async () => {
    const firstStartEntered = deferred<void>()
    const releaseFirstStart = deferred<void>()
    const captures: FakeCapture[] = []
    const sockets: FakeWebSocket[] = []
    let sessionNumber = 0

    const binding = useVoiceSession({
      api: {
        createSession: async () => {
          sessionNumber++
          return {
            sessionId: `vs-race-${sessionNumber}`,
            runId: `00000000-0000-0000-0000-${String(sessionNumber).padStart(12, '0')}`,
            wsPath: `/ws/voice/sessions/vs-race-${sessionNumber}`,
          }
        },
      },
      openWebSocket: (url) => {
        const socket = new FakeWebSocket(url)
        sockets.push(socket)
        queueMicrotask(() => socket.open())
        return socket
      },
      requestMicrophone: async () => ({ getTracks: () => [{ stop() {} }] }) as unknown as MediaStream,
      createCapture: () => {
        const capture = new FakeCapture()
        const captureIndex = captures.length
        const originalStart = capture.start.bind(capture)
        capture.start = async (stream, onChunk) => {
          await originalStart(stream, onChunk)
          if (captureIndex === 0 && capture.started === 1) {
            firstStartEntered.resolve()
            await releaseFirstStart.promise
          }
        }
        captures.push(capture)
        return capture
      },
    })

    const firstToggle = binding.toggleMicrophone()
    await firstStartEntered.promise
    binding.close()

    const secondToggle = binding.toggleMicrophone()
    await secondToggle
    releaseFirstStart.resolve()
    await firstToggle

    expect(sockets).toHaveLength(2)
    expect(captures).toHaveLength(2)
    expect(captures[0]?.stoppedCount).toBeGreaterThanOrEqual(1)
    expect(captures[1]?.started).toBe(1)
    expect(captures[1]?.stoppedCount).toBe(0)
  })
})
