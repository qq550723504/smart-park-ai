import { describe, expect, it } from 'vitest'
import { PcmPlayer, type AudioBufferLike, type AudioContextLike, type PlaybackSourceLike } from './pcm-player'

interface RecordedSource {
  channel: Float32Array
  startedAt: number | null
  stopped: boolean
}

class FakeBuffer implements AudioBufferLike {
  readonly data: Float32Array

  constructor(length: number) {
    this.data = new Float32Array(length)
  }

  getChannelData(): Float32Array {
    return this.data
  }
}

function makeHarness() {
  const sources: RecordedSource[] = []
  let now = 100

  const context: AudioContextLike = {
    get currentTime() {
      return now
    },
    sampleRate: 16000,
    createBuffer(_channels: number, length: number) {
      return new FakeBuffer(length)
    },
  }

  const deps = {
    context,
    createSource(buffer: AudioBufferLike): PlaybackSourceLike {
      const recorded: RecordedSource = {
        channel: buffer.getChannelData(0),
        startedAt: null,
        stopped: false,
      }
      sources.push(recorded)
      return {
        start(when?: number) {
          recorded.startedAt = when ?? -1
        },
        stop() {
          recorded.stopped = true
        },
      }
    },
  }

  function pcmOf(samples: number[]): ArrayBuffer {
    return new Int16Array(samples).buffer as ArrayBuffer
  }

  return {
    player: new PcmPlayer(deps),
    sources,
    advance(seconds: number): void {
      now += seconds
    },
    pcmOf,
  }
}

describe('voice PCM player', () => {
  it('accepts ordered chunks and schedules gapless playback from sequence 1', () => {
    const h = makeHarness()

    expect(h.player.enqueue(1, h.pcmOf([0, 100]))).toBe('accepted')
    expect(h.player.enqueue(2, h.pcmOf([-200, 300]))).toBe('accepted')

    expect(h.sources).toHaveLength(2)
    // 第一块在当前时间 + 小间隔起播；第二块紧接第一块结尾，无缝衔接。
    expect(h.sources[0].startedAt).toBeCloseTo(100.01, 5)
    expect(h.sources[1].startedAt).toBeCloseTo(100.01 + 2 / 16000, 6)
    expect(h.player.scheduledCount).toBe(2)
  })

  it('converts int16 samples into float32 range in the scheduled buffer', () => {
    const h = makeHarness()

    h.player.enqueue(1, h.pcmOf([32767, -32768, 0]))

        expect(h.sources[0].channel[0]).toBeCloseTo(1, 4)
    expect(h.sources[0].channel[1]).toBe(-1)
    expect(h.sources[0].channel[2]).toBe(0)
  })

  it('drops duplicate and out-of-order chunks', () => {
    const h = makeHarness()
    expect(h.player.enqueue(2, h.pcmOf([1]))).toBe('accepted')

    expect(h.player.enqueue(1, h.pcmOf([1]))).toBe('dropped-late')
    expect(h.player.enqueue(2, h.pcmOf([1]))).toBe('dropped-late')
    expect(h.sources).toHaveLength(1)
  })

  it('cancel stops sounding sources immediately and drops later chunks', () => {
    const h = makeHarness()
    h.player.enqueue(1, h.pcmOf([10]))
    h.player.enqueue(2, h.pcmOf([20]))

    h.player.cancel()

    expect(h.sources.every((source) => source.stopped)).toBe(true)
    expect(h.player.scheduledCount).toBe(0)
    expect(h.player.enqueue(3, h.pcmOf([30]))).toBe('dropped-after-cancel')
    expect(h.sources).toHaveLength(2)
  })

  it('beginTurnReset re-accepts server numbering restarting at one', () => {
    const h = makeHarness()
    h.player.enqueue(1, h.pcmOf([1]))
    h.player.enqueue(2, h.pcmOf([2]))
    h.player.cancel()

    h.player.beginTurnReset()

    expect(h.player.enqueue(1, h.pcmOf([5]))).toBe('accepted')
    expect(h.sources).toHaveLength(3)
    expect(h.sources[2].startedAt).not.toBeNull()
  })

  it('finished chunks leave the schedule so count reflects only sounding audio', () => {
    const h = makeHarness()
    h.player.enqueue(1, h.pcmOf(new Array(320).fill(0))) // 20 ms

    h.advance(0.05) // 播放完毕

    expect(h.player.scheduledCount).toBe(0)
  })
})
