import { onScopeDispose, ref } from 'vue'
import type { DemoRole } from '../types/workflow'
import type { OperationsDailyReport, OperationsReportSummary } from '../types/operationsReport'
import { downloadOperationsDailyReport, getOperationsDailyReport, listOperationsDailyReports, startOperationsDailyReport } from '../services/operationsReportApi'
import type { ExecutionTraceLike } from './useOperationsAnalysis'

export function useOperationsDailyReport(options: { trace?: ExecutionTraceLike; pollIntervalMs?: number; maxPolls?: number } = {}) {
  const report = ref<OperationsDailyReport | null>(null)
  const reports = ref<OperationsReportSummary[]>([])
  const runId = ref<string | null>(null)
  const busy = ref(false)
  const historyLoading = ref(false)
  const error = ref('')
  const pollIntervalMs = options.pollIntervalMs ?? 500
  const maxPolls = options.maxPolls ?? 180
  let generation = 0
  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

  async function loadHistory(role: DemoRole): Promise<void> {
    const current = generation
    historyLoading.value = true
    try {
      const page = await listOperationsDailyReports(role)
      if (current === generation) reports.value = page.content
    } catch (cause) {
      if (current === generation) error.value = cause instanceof Error ? cause.message : String(cause)
    } finally {
      if (current === generation) historyLoading.value = false
    }
  }

  async function open(reportId: string, role: DemoRole): Promise<void> {
    const current = ++generation
    error.value = ''
    try {
      const detail = await getOperationsDailyReport(reportId, role)
      if (current !== generation) return
      report.value = detail
      runId.value = detail.runId
      options.trace?.subscribe(detail.traceId)
    } catch (cause) {
      if (current === generation) error.value = cause instanceof Error ? cause.message : String(cause)
    }
  }

  async function start(role: DemoRole): Promise<void> {
    if (busy.value) return
    const current = ++generation
    busy.value = true
    error.value = ''
    try {
      const accepted = await startOperationsDailyReport(role)
      if (current !== generation) return
      runId.value = accepted.runId
      options.trace?.subscribe(accepted.runId)
      for (let attempt = 0; attempt < maxPolls; attempt += 1) {
        const detail = await getOperationsDailyReport(accepted.reportId, role)
        if (current !== generation) return
        report.value = detail
        if (detail.status !== 'REQUESTED' && detail.status !== 'GENERATING') {
          await loadHistory(role)
          return
        }
        await sleep(pollIntervalMs)
      }
      throw new Error('运营日报超时，可稍后从报告历史查看最终状态')
    } catch (cause) {
      if (current === generation) error.value = cause instanceof Error ? cause.message : String(cause)
    } finally {
      if (current === generation) busy.value = false
    }
  }

  async function download(reportId: string, role: DemoRole): Promise<void> {
    error.value = ''
    try { await downloadOperationsDailyReport(reportId, role) }
    catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  }

  onScopeDispose(() => { generation += 1 })
  return { report, reports, runId, busy, historyLoading, error, start, open, loadHistory, download }
}
