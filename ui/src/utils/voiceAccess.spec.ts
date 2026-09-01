import { describe, expect, it } from 'vitest'
import { isVoiceInputAllowed } from './voiceAccess'

describe('isVoiceInputAllowed', () => {
  it('allows voice input on localhost even when the page is HTTP', () => {
    expect(isVoiceInputAllowed({ hostname: 'localhost' }, false)).toBe(true)
    expect(isVoiceInputAllowed({ hostname: '127.0.0.1' }, false)).toBe(true)
    expect(isVoiceInputAllowed({ hostname: '::1' }, false)).toBe(true)
  })

  it('allows voice input from any host in a secure context', () => {
    expect(isVoiceInputAllowed({ hostname: '192.168.6.246' }, true)).toBe(true)
  })

  it('rejects remote HTTP origins so microphone access is not attempted', () => {
    expect(isVoiceInputAllowed({ hostname: '192.168.6.246' }, false)).toBe(false)
  })
})
