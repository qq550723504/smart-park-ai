import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useExpertCollaboration } from './useExpertCollaboration'
import { getCollaborationRun, startCollaboration } from '../services/collaborationApi'

import type { CollaborationRun } from '../types/collaboration'

vi.mock('../services/collaborationApi', () => ({
  getCollaborationRun: vi.fn(),
  startCollaboration: vi.fn(),
}))

const mockedStart = vi.mocked(startCollaboration)
const mockedGet = vi.mocked(getCollaborationRun)

const running: CollaborationRun = {
  runId: 'run-1', question: 'q', status: 'RUNNING',
  plan: { normalizedQuestion: 'q', selectedDomains: ['ENERGY'], assignments: { ENERGY: 'check energy' }, selectionReason: 'energy' },
  findings: [], synthesis: null, error: null, updatedAt: '2026-08-25T00:00:00Z',
}

function completed(): CollaborationRun {
  return { ...running, status: 'COMPLETED' as const, findings: [{
    domain: 'ENERGY' as const, status: 'SUPPORTED' as const, conclusion: 'supported', evidenceRefs: ['energy:1'], confidence: 0.8, nextChecks: [],
  }], synthesis: { status: 'SUPPORTED' as const, conclusion: 'summary', evidenceRefs: ['energy:1'], confidence: 0.8, uncertainties: [] },
}
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  mockedStart.mockResolvedValue({ runId: 'run-1', statusUrl: '/status', eventsUrl: '/events' })
  mockedGet.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useExpertCollaboration', () => {
  it('polls a started run until the backend reaches a terminal status', async () => {
    mockedGet.mockResolvedValueOnce(running).mockResolvedValueOnce(completed())
    const state = useExpertCollaboration(10)

    await expect(state.start(' q ')).resolves.toBe('run-1')
    await vi.runAllTimersAsync()

    expect(mockedStart).toHaveBeenCalledWith('q')
    expect(mockedGet).toHaveBeenCalledTimes(2)
    expect(state.run.value?.status).toBe('COMPLETED')
    expect(state.isRunning.value).toBe(false)
    expect(state.run.value?.synthesis?.conclusion).toBe('summary')
  })

  it('rejects blank questions without calling the backend', async () => {
    const state = useExpertCollaboration()
    await expect(state.start('   ')).rejects.toThrow('请输入需要专家协作分析的问题')
    expect(mockedStart).not.toHaveBeenCalled()
  })

  it('gives a newly started collaboration its own poll retry budget', async () => {
    // A previous run that exhausted all poll retries must not consume the new
    // run's budget: the counter resets on every start.
    mockedGet.mockRejectedValue(new Error('network down'))
    const state = useExpertCollaboration(10)
    await state.start('first')
    await vi.runAllTimersAsync()
    // Retry budget exhausted: exactly MAX_CONSECUTIVE_FAILURES polls, no more.
    expect(mockedGet).toHaveBeenCalledTimes(5)

    mockedGet.mockClear()
    mockedGet.mockRejectedValueOnce(new Error('flaky')).mockResolvedValueOnce({ ...completed(), runId: 'run-2' })
    mockedStart.mockResolvedValueOnce({ runId: 'run-2', statusUrl: '', eventsUrl: '' })
    await state.start('second')
    await vi.runAllTimersAsync()

    expect(mockedGet).toHaveBeenCalledWith('run-2')
    expect(state.run.value?.runId).toBe('run-2')
  })

  it('does not let an older poll overwrite a newer run', async () => {
    let resolveOld!: (value: typeof running) => void
    mockedGet.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
      .mockResolvedValueOnce({ ...completed(), runId: 'run-2' })
    mockedStart.mockResolvedValueOnce({ runId: 'run-1', statusUrl: '', eventsUrl: '' })
      .mockResolvedValueOnce({ runId: 'run-2', statusUrl: '', eventsUrl: '' })
    const state = useExpertCollaboration(10)

    await state.start('first')
    await state.start('second')
    await vi.runAllTimersAsync()
    resolveOld({ ...running, runId: 'run-1' })
    await vi.runAllTimersAsync()

    expect(state.run.value?.runId).toBe('run-2')
  })
})
