import { flushPromises } from '@vue/test-utils'
import { effectScope, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useGuidedLaunch } from './useGuidedLaunch'
import type { GuidedLaunchUpdate, ScenarioLaunchRequest } from '../types/workbench'

describe('useGuidedLaunch', () => {
  it('waits for activation and consumes a matching request once', async () => {
    const active = ref(false)
    const request = ref<ScenarioLaunchRequest | null>({
      requestId: 7,
      mode: 'guided',
      scenarioId: 'OPERATIONS_ANALYSIS',
      view: 'analytics',
    })
    const start = vi.fn(async () => ({ state: 'started' as const, message: '分析已启动' }))
    const updates: GuidedLaunchUpdate[] = []
    const scope = effectScope()
    scope.run(() => useGuidedLaunch({
      active: () => active.value,
      request: () => request.value,
      scenarioId: 'OPERATIONS_ANALYSIS',
      start,
      onUpdate: (update) => updates.push(update),
    }))

    await nextTick()
    expect(start).not.toHaveBeenCalled()
    active.value = true
    await flushPromises()
    expect(start).toHaveBeenCalledTimes(1)
    active.value = false
    await nextTick()
    active.value = true
    await flushPromises()
    expect(start).toHaveBeenCalledTimes(1)
    expect(updates.map((update) => update.state)).toEqual(['preparing', 'started'])
    scope.stop()
  })

  it('ignores a request for another scenario', async () => {
    const start = vi.fn(async () => ({ state: 'started' as const, message: 'started' }))
    const request = ref<ScenarioLaunchRequest | null>({
      requestId: 8, mode: 'guided', scenarioId: 'VOICE_ASSISTANT', view: 'voice',
    })
    const scope = effectScope()
    scope.run(() => useGuidedLaunch({
      active: () => true,
      request: () => request.value,
      scenarioId: 'OPERATIONS_ANALYSIS',
      start,
      onUpdate: vi.fn(),
    }))
    await flushPromises()
    expect(start).not.toHaveBeenCalled()
    scope.stop()
  })

  it('reports a rejected start as failed', async () => {
    const updates: GuidedLaunchUpdate[] = []
    const request = ref<ScenarioLaunchRequest | null>({
      requestId: 9, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow',
    })
    const scope = effectScope()
    scope.run(() => useGuidedLaunch({
      active: () => true,
      request: () => request.value,
      scenarioId: 'ALERT_WORKFLOW',
      start: async () => { throw new Error('后端不可用') },
      onUpdate: (update) => updates.push(update),
    }))
    await flushPromises()
    expect(updates.at(-1)).toEqual({ requestId: 9, state: 'failed', message: '后端不可用' })
    scope.stop()
  })
})
