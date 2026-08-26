import { describe, expect, it } from 'vitest'
import {
  buildCaptureWorkletSource,
  CAPTURE_WORKLET_NAME,
  resampleTo16k,
  toInt16Samples,
} from './pcm-capture'

describe('voice capture worklet', () => {
  it('resamples 48 kHz input down to 16 kHz with linear interpolation', () => {
    // 48 个采样 = 3 个 16k 周期；重采样后应恰好 16 个点。
    const input = new Float32Array(48)
    for (let i = 0; i < input.length; i++) {
      input[i] = Math.sin((i / 47) * Math.PI)
    }

    const output = resampleTo16k(input, 48000)

    expect(output).toHaveLength(16)
    // 端点保持单调插值，不产生 NaN 或越界。
    for (const value of output) {
      expect(Number.isNaN(value)).toBe(false)
      expect(Math.abs(value)).toBeLessThanOrEqual(1)
    }
  })

  it('passes through already-16k input and rejects below-16k input', () => {
    const input = new Float32Array([0.1, -0.2])
    expect(resampleTo16k(input, 16000)).toBe(input)
    expect(resampleTo16k(new Float32Array([1]), 8000)).toHaveLength(0)
  })

  it('clamps float samples into int16 range', () => {
    const output = toInt16Samples(new Float32Array([2, -2, 0, 0.5]))
    expect(Array.from(output)).toEqual([32767, -32767, 0, 16384])
  })

  it('worklet source registers the processor and emits 20 ms int16 chunks', () => {
    const source = buildCaptureWorkletSource()

    expect(source).toContain(`registerProcessor('${CAPTURE_WORKLET_NAME}'`)
    expect(source).toContain('16000')
    expect(source).toContain('320')
    expect(source).toContain('postMessage')
    expect(source).toContain('Int16Array')
    // 主线程绝不使用已弃用的 ScriptProcessorNode。
    expect(source.toLowerCase()).not.toContain('scriptprocessor')
  })
})
