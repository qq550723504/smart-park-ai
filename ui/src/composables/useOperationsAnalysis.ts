import { computed, onScopeDispose, ref, watch } from 'vue'
import type { Ref } from 'vue'
import type { AnalysisStatusDto } from '../types/analytics'
import { isTerminalAnalysisStatus } from '../types/analytics'
import type { DisplayPayload, ExecutionEvent } from '../types/execution'
import { isTerminalEvent } from '../types/execution'
import { getAnalysisStatus, startAnalysis, submitClarification } from '../services/analyticsApi'

export interface ExecutionTraceLike {
  events: Ref<ExecutionEvent[]>
  subscribe(runId: string): void
}

export type AnalysisPhase = 'idle' | 'running' | 'completed' | 'clarification' | 'failed'

export interface OperationsAnalysis {
  phase: Ref<AnalysisPhase>
  dto: Ref<AnalysisStatusDto | null>
  error: Ref<string>
  runId: Ref<string | null>
  chart: Ref<DisplayPayload | null>
  selections: Ref<Array<{ term: string; metric: string }>>
  submit(question: string, callbacks?: AnalysisStartCallbacks): Promise<void>
  clarify(): Promise<void>
}

export interface AnalysisStartCallbacks {
  onAccepted?(runId: string): void
  onFailed?(cause: Error): void
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Drives one natural-language analysis run over real REST + unified SSE:
 * start → subscribe trace → poll status to a terminal state. The chart spec is
 * only ever taken from the backend CHART_SPECIFIED event — never invented.
 */
export function useOperationsAnalysis(
  options: { trace?: ExecutionTraceLike; pollIntervalMs?: number; maxPolls?: number } = {},
): OperationsAnalysis {
  const pollIntervalMs = options.pollIntervalMs ?? 500
  const maxPolls = options.maxPolls ?? 180

  const phase = ref<AnalysisPhase>('idle')
  const dto = ref<AnalysisStatusDto | null>(null)
  const error = ref('')
  const runId = ref<string | null>(null)
  const chart = ref<DisplayPayload | null>(null)
  const selections = ref<Array<{ term: string; metric: string }>>([])
  let clarificationPollTimer: ReturnType<typeof setTimeout> | undefined
  let clarificationPollGeneration = 0
  let operationGeneration = 0

  function stopClarificationPolling(): void {
    clarificationPollGeneration += 1
    if (clarificationPollTimer !== undefined) {
      clearTimeout(clarificationPollTimer)
      clarificationPollTimer = undefined
    }
  }

  function pollClarificationExpiry(): void {
    const generation = clarificationPollGeneration
    const operation = operationGeneration
    const scheduleNext = () => {
      if (generation === clarificationPollGeneration) {
        clarificationPollTimer = setTimeout(pollClarificationExpiry, pollIntervalMs)
      }
    }

    clarificationPollTimer = undefined
    void getAnalysisStatus(runId.value!).then((current) => {
      if (generation !== clarificationPollGeneration || operation !== operationGeneration) return
      dto.value = current
      if (isTerminalAnalysisStatus(current.status)) {
        applyTerminal(current)
        return
      }
      scheduleNext()
    }).catch(() => {
      // A transient status failure must not disable expiry detection.
      scheduleNext()
    })
  }

  function startClarificationPolling(): void {
    stopClarificationPolling()
    clarificationPollTimer = setTimeout(pollClarificationExpiry, pollIntervalMs)
  }

  if (options.trace) {
    watch(options.trace.events, (events) => {
      const currentRunId = runId.value
      if (!currentRunId) return
      for (let i = events.length - 1; i >= 0; i--) {
        if (events[i].eventType === 'CHART_SPECIFIED' && events[i].runId === currentRunId) {
          chart.value = events[i].displayPayload
          return
        }
      }
    })
  }

  async function pollToTerminal(targetRunId: string, generation: number): Promise<AnalysisStatusDto | null> {
    let lastError = ''
    for (let attempt = 0; attempt < maxPolls; attempt++) {
      if (generation !== operationGeneration) return null
      try {
        const current = await getAnalysisStatus(targetRunId)
        if (generation !== operationGeneration) return null
        dto.value = current
        // A clarification pause also stops polling: the run waits for operator input.
        if (isTerminalAnalysisStatus(current.status) || current.status === 'NEEDS_CLARIFICATION') {
          return current
        }
      } catch (cause) {
        if (generation !== operationGeneration) return null
        lastError = cause instanceof Error ? cause.message : String(cause)
      }
      await sleep(pollIntervalMs)
    }
    if (generation !== operationGeneration) return null
    throw new Error(lastError || '分析超时，未在预期时间内完成')
  }

  function applyTerminal(current: AnalysisStatusDto): void {
    dto.value = current
    if (current.status === 'COMPLETED') {
      phase.value = 'completed'
    } else if (current.status === 'NEEDS_CLARIFICATION') {
      phase.value = 'clarification'
      selections.value = []
      startClarificationPolling()
    } else {
      error.value = `分析失败：${current.failureStage ?? '未知阶段'}`
      phase.value = 'failed'
    }
  }

  function subscribeTraceBestEffort(targetRunId: string): void {
    try {
      options.trace?.subscribe(targetRunId)
    } catch {
      // REST acceptance owns the run lifecycle. Trace transport is optional,
      // so setup failures must not turn an accepted run into a retryable one.
    }
  }

  async function submit(question: string, callbacks?: AnalysisStartCallbacks): Promise<void> {
    if (!question.trim()) {
      error.value = '请输入分析问题'
      callbacks?.onFailed?.(new Error(error.value))
      return
    }
    const generation = ++operationGeneration
    error.value = ''
    stopClarificationPolling()
    // A failed POST must not leave the previous run addressable to the page;
    // otherwise the page can emit that old ID as if this attempt had started.
    runId.value = null
    chart.value = null
    dto.value = null
    phase.value = 'running'
    let accepted = false
    try {
      const { runId: startedRunId } = await startAnalysis(question.trim())
      if (generation !== operationGeneration) return
      runId.value = startedRunId
      accepted = true
      callbacks?.onAccepted?.(startedRunId)
      subscribeTraceBestEffort(startedRunId)
      const terminal = await pollToTerminal(startedRunId, generation)
      if (generation !== operationGeneration || !terminal) return
      applyTerminal(terminal)
    } catch (cause) {
      if (generation !== operationGeneration) return
      const failure = cause instanceof Error ? cause : new Error(String(cause))
      error.value = failure.message
      phase.value = 'failed'
      if (!accepted) callbacks?.onFailed?.(failure)
    }
  }

  async function clarify(): Promise<void> {
    if (!runId.value) return
    if (selections.value.length === 0) {
      error.value = '请至少选择一个指标口径'
      return
    }
    const generation = ++operationGeneration
    const targetRunId = runId.value
    error.value = ''
    stopClarificationPolling()
    chart.value = null
    phase.value = 'running'
    try {
      await submitClarification(targetRunId, selections.value)
      if (generation !== operationGeneration) return
      subscribeTraceBestEffort(targetRunId)
      const terminal = await pollToTerminal(targetRunId, generation)
      if (generation !== operationGeneration || !terminal) return
      applyTerminal(terminal)
    } catch (cause) {
      if (generation !== operationGeneration) return
      error.value = cause instanceof Error ? cause.message : String(cause)
      phase.value = 'failed'
    }
  }

  const isBusy = computed(() => phase.value === 'running')
  void isBusy
  onScopeDispose(() => {
    operationGeneration++
    stopClarificationPolling()
  })
  return { phase, dto, error, runId, chart, selections, submit, clarify }
}
