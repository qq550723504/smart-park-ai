/**
 * Ordered PCM playback with immediate cancellation.
 *
 * The server numbers audio chunks per turn starting at 1; this player queues
 * them gaplessly (16 kHz mono), drops anything out of order or arriving after
 * a cancellation, and stops the currently sounding source the instant the
 * microphone is clicked again.
 */

export interface AudioBufferLike {
  getChannelData(channel: number): Float32Array
}

export interface PlaybackSourceLike {
  start(when?: number): void
  stop(): void
}

export interface AudioContextLike {
  readonly currentTime: number
  readonly sampleRate: number
  createBuffer(channels: number, length: number, sampleRate: number): AudioBufferLike
}

export interface PcmPlayerDeps {
  context: AudioContextLike
  createSource(buffer: AudioBufferLike): PlaybackSourceLike
}

export type EnqueueResult = 'accepted' | 'dropped-late' | 'dropped-after-cancel'

const PLAYBACK_GAP_SECONDS = 0.01

interface ScheduledSource {
  source: PlaybackSourceLike
  endTime: number
}

export class PcmPlayer {
  private readonly deps: PcmPlayerDeps
  private readonly scheduled: ScheduledSource[] = []
  private lastEnqueuedSequence = 0
  private nextStartTime = 0
  private cancelled = false

  constructor(deps: PcmPlayerDeps) {
    this.deps = deps
  }

  /** Number of sources still scheduled to sound (pending or playing tail). */
  get scheduledCount(): number {
    this.prune()
    return this.scheduled.length
  }

  /**
   * Queues one 16-bit mono PCM chunk at 16 kHz. Chunks whose sequence is not
   * strictly increasing are dropped — they are duplicates or late arrivals.
   */
  enqueue(sequence: number, data: ArrayBuffer | Int16Array): EnqueueResult {
    if (this.cancelled) {
      return 'dropped-after-cancel'
    }
    if (!Number.isInteger(sequence) || sequence < 1) {
      return 'dropped-late'
    }
    if (sequence <= this.lastEnqueuedSequence) {
      return 'dropped-late'
    }

    const samples = data instanceof Int16Array ? data : new Int16Array(data)
    const buffer = this.deps.context.createBuffer(1, samples.length, 16000)
    const channel = buffer.getChannelData(0)
    for (let i = 0; i < samples.length; i++) {
      channel[i] = samples[i] / 32768
    }

    const source = this.deps.createSource(buffer)
    const startAt = Math.max(
      this.deps.context.currentTime + PLAYBACK_GAP_SECONDS,
      this.nextStartTime,
    )
    source.start(startAt)

    const duration = samples.length / 16000
    this.scheduled.push({ source, endTime: startAt + duration })
    this.nextStartTime = startAt + duration
    this.lastEnqueuedSequence = sequence
    return 'accepted'
  }

  /** Mic-click path: stop everything that sounds right now and clear the queue. */
  cancel(): void {
    for (const entry of this.scheduled) {
      try {
        entry.source.stop()
      } catch {
        // already finished; nothing to stop
      }
    }
    this.scheduled.length = 0
    this.nextStartTime = 0
    this.cancelled = true
  }

  /**
   * A new turn restarts server chunk numbering at 1; clears sequence tracking
   * (and the post-cancel gate) so fresh chunks can be accepted again.
   */
  beginTurnReset(): void {
    this.cancelled = false
    this.lastEnqueuedSequence = 0
    this.nextStartTime = 0
  }

  private prune(): void {
    const now = this.deps.context.currentTime
    while (this.scheduled.length > 0 && this.scheduled[0].endTime <= now) {
      this.scheduled.shift()
    }
  }
}
