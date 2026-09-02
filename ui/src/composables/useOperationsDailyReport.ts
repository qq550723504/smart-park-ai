import { onScopeDispose, ref } from 'vue'
import type { DemoRole } from '../types/workflow'
import type { OperationsDailyReport } from '../types/operationsReport'
import { getOperationsDailyReport, startOperationsDailyReport } from '../services/operationsReportApi'
import type { ExecutionTraceLike } from './useOperationsAnalysis'

export function useOperationsDailyReport(options: {
  trace?: ExecutionTraceLike
  pollIntervalMs?: number
  maxPolls?: number
} = {}) {
  const report = ref<OperationsDailyReport | null>(null)
  const runId = ref<string | null>(null)
  const busy = ref(false)
  const error = ref('')
  const pollIntervalMs = options.pollIntervalMs ?? 500
  const maxPolls = options.maxPolls ?? 180
  let generation = 0

  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

  function reset(): void {
    generation += 1
    report.value = null
    runId.value = null
    busy.value = false
    error.value = ''
  }

  async function start(role: DemoRole): Promise<void> {
    if (busy.value) return
    const currentGeneration = ++generation
    busy.value = true
    error.value = ''
    try {
      const accepted = await startOperationsDailyReport(role)
      if (currentGeneration !== generation) return
      runId.value = accepted.runId
      options.trace?.subscribe(accepted.runId)
      for (let attempt = 0; attempt < maxPolls; attempt += 1) {
        const current = await getOperationsDailyReport(accepted.runId, role)
        if (currentGeneration !== generation) return
        report.value = current
        if (current.status !== 'RUNNING') return
        await sleep(pollIntervalMs)
      }
      throw new Error('运营日报超时，未在预期时间内完成')
    } catch (cause) {
      if (currentGeneration !== generation) return
      error.value = cause instanceof Error ? cause.message : String(cause)
    } finally {
      if (currentGeneration === generation) busy.value = false
    }
  }

  onScopeDispose(() => { generation += 1 })
  return { report, runId, busy, error, start, reset }
}
