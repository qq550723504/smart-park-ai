/**
 * Microphone capture: AudioWorklet-based, resampled to 16 kHz mono int16 and
 * emitted as 20 ms (320-sample) ArrayBuffer chunks. The main thread never
 * touches the deprecated ScriptProcessorNode — the resampling runs inside the
 * worklet processor built from {@link buildCaptureWorkletSource}.
 */

export const CAPTURE_WORKLET_NAME = 'voice-pcm-capture'

/** Linear-interpolation resampler; exported pure so tests can pin the math. */
export function resampleTo16k(input: Float32Array, inputSampleRate: number): Float32Array {
  if (inputSampleRate === 16000) {
    return input
  }
  if (inputSampleRate < 16000 || input.length === 0) {
    return new Float32Array(0)
  }
  const ratio = inputSampleRate / 16000
  const outputLength = Math.floor(input.length / ratio)
  const output = new Float32Array(outputLength)
  for (let i = 0; i < outputLength; i++) {
    const position = i * ratio
    const left = Math.floor(position)
    const right = Math.min(left + 1, input.length - 1)
    const fraction = position - left
    output[i] = input[left] * (1 - fraction) + input[right] * fraction
  }
  return output
}

/** Float32 [-1, 1] to little-endian int16 with clamping. */
export function toInt16Samples(input: Float32Array): Int16Array {
  const output = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) {
    const clamped = Math.max(-1, Math.min(1, input[i]))
    output[i] = Math.round(clamped * 32767)
  }
  return output
}

/** Builds the worklet processor source registered under CAPTURE_WORKLET_NAME. */
export function buildCaptureWorkletSource(): string {
  return `
const TARGET_SAMPLE_RATE = 16000;
const CHUNK_SAMPLES = 320; // 20 ms at 16 kHz

class ${CAPTURE_WORKLET_NAME}Processor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / TARGET_SAMPLE_RATE;
    this.cursor = 0;
    this.pending = new Float32Array(CHUNK_SAMPLES);
    this.pendingFilled = 0;
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0) return true;
    const channel = input[0];
    if (!channel) return true;

    for (let i = 0; i < channel.length; i++) {
      const position = this.cursor + i;
      // linear interpolation between neighbouring source samples
      const leftIndex = Math.floor(position);
      const rightIndex = Math.min(leftIndex + 1, channel.length - 1);
      const fraction = position - leftIndex;
      const value =
        channel[Math.min(leftIndex, channel.length - 1)] * (1 - fraction) +
        channel[rightIndex] * fraction;

      this.pending[this.pendingFilled++] = value;
      if (this.pendingFilled === CHUNK_SAMPLES) {
        const pcm = new Int16Array(CHUNK_SAMPLES);
        for (let j = 0; j < CHUNK_SAMPLES; j++) {
          pcm[j] = Math.max(-32768, Math.min(32767, Math.round(this.pending[j] * 32767)));
        }
        this.port.postMessage(pcm.buffer, [pcm.buffer]);
        this.pendingFilled = 0;
      }
    }

    this.cursor += channel.length;
    return true;
  }
}

registerProcessor('${CAPTURE_WORKLET_NAME}', ${CAPTURE_WORKLET_NAME}Processor);
`
}

export interface WorkletNodeLike extends EventTarget {
  port: { onmessage: ((event: MessageEvent) => void) | null; postMessage(message: unknown): void }
  connect(destination: unknown): void
  disconnect(): void
}

export interface CaptureAudioContextLike {
  sampleRate: number
  createMediaStreamSource(stream: MediaStream): {
    connect(node: WorkletNodeLike): void
    disconnect(): void
  }
  /** Zero-gain node used as a pull sink so the mic never reaches speakers. */
  createGain(): { gain: { value: number }; connect(destination: unknown): void }
  audioWorklet: { addModule(url: string): Promise<void> }
  destination: unknown
  close(): Promise<void>
}

export interface PcmCaptureDeps {
  createContext(sampleRateHint: number): CaptureAudioContextLike
  createWorkletNode(context: CaptureAudioContextLike, name: string, url: string):
    Promise<WorkletNodeLike>
  createMuteSink(context: CaptureAudioContextLike): {
    gain: { value: number }
    connect(destination: unknown): void
    disconnect(): void
  }
  createModuleUrl(source: string): string
  revokeModuleUrl(url: string): void
}

/** Browser default wiring over Blob URLs. */
export function browserPcmCaptureDeps(): PcmCaptureDeps {
  return {
    createContext: (sampleRate) =>
      new AudioContext({ sampleRate }) as unknown as CaptureAudioContextLike,
    createWorkletNode: async (context, name, url) =>
      new AudioWorkletNode(
        context as unknown as BaseAudioContext,
        name,
      ) as unknown as WorkletNodeLike,
    createMuteSink: (context) => context.createGain() as never,
    createModuleUrl: (source) =>
      URL.createObjectURL(new Blob([source], { type: 'application/javascript' })),
    revokeModuleUrl: (url) => URL.revokeObjectURL(url),
  }
}

/**
 * Owns one capture session. start() wires mic → worklet → 20 ms PCM callbacks;
 * stop() tears everything down. No ScriptProcessorNode anywhere.
 */
export class VoicePcmCapture {
  private readonly deps: PcmCaptureDeps
  private context: CaptureAudioContextLike | null = null
  private node: WorkletNodeLike | null = null
  private mute: { disconnect(): void } | null = null
  private source: { disconnect(): void } | null = null
  private moduleUrl: string | null = null

  constructor(deps: PcmCaptureDeps = browserPcmCaptureDeps()) {
    this.deps = deps
  }

  async start(stream: MediaStream, onChunk: (pcm: ArrayBuffer) => void): Promise<void> {
    if (this.context) {
      throw new Error('capture already started')
    }
    const context = this.deps.createContext(16000)
    const source = buildCaptureWorkletSource()
    this.moduleUrl = this.deps.createModuleUrl(source)
    await context.audioWorklet.addModule(this.moduleUrl)

    const node = await this.deps.createWorkletNode(context, CAPTURE_WORKLET_NAME, this.moduleUrl)
    node.port.onmessage = (event) => onChunk(event.data as ArrayBuffer)

    context.createMediaStreamSource(stream).connect(node)
    // Pull sink with zero gain: keeps the worklet processing without ever
    // playing the user's own microphone back through the speakers.
    const mute = this.deps.createMuteSink(context)
    mute.gain.value = 0
    node.connect(mute)
    mute.connect(context.destination)

    this.context = context
    this.node = node
    this.mute = mute
    this.source = context.createMediaStreamSource(stream)
  }

  async stop(): Promise<void> {
    try {
      this.node?.disconnect()
      this.mute?.disconnect()
      this.source?.disconnect()
      await this.context?.close()
    } finally {
      if (this.moduleUrl) {
        this.deps.revokeModuleUrl(this.moduleUrl)
      }
      this.node = null
      this.mute = null
      this.source = null
      this.context = null
      this.moduleUrl = null
    }
  }
}
