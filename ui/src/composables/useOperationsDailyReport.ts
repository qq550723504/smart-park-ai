import { onScopeDispose, ref } from 'vue'
import type { DemoRole } from '../types/workflow'
import type { OperationsDailyReport, OperationsReportCreateRequest, OperationsReportSummary } from '../types/operationsReport'
import { downloadOperationsDailyReport, getOperationsDailyReport, listOperationsDailyReports, OperationsReportHttpError, startOperationsDailyReport } from '../services/operationsReportApi'
import { createRequestId } from '../utils/requestId'
import type { ExecutionTraceLike } from './useOperationsAnalysis'

export function useOperationsDailyReport(options: { trace?: ExecutionTraceLike; pollIntervalMs?: number; maxPolls?: number } = {}) {
  const report = ref<OperationsDailyReport | null>(null)
  const reports = ref<OperationsReportSummary[]>([])
  const runId = ref<string | null>(null)
  const busy = ref(false)
  const detailLoading = ref(false)
  const downloadingId = ref<string | null>(null)
  const historyLoading = ref(false)
  const historyLoaded = ref(false)
  const historyPage = ref(-1)
  const historyHasNext = ref(false)
  const historyTotal = ref(0)
  const error = ref('')
  const pollIntervalMs = options.pollIntervalMs ?? 500
  const maxPolls = options.maxPolls ?? 180
  let generation = 0
  let historyGeneration = 0
  let pendingCreation: { role: DemoRole; key: string; request: OperationsReportCreateRequest; fingerprint: string; accepted: boolean } | null = null
  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

  function isDefinitiveCreateRejection(cause: OperationsReportHttpError): boolean {
    if (cause.status === 400 || cause.status === 403) return true
    return cause.status === 409 && /Idempotency-Key/.test(cause.message) && /请求键/.test(cause.message)
  }

  function defaultRequest(): OperationsReportCreateRequest {
    const to = new Date()
    return {
      reportType: 'OPERATIONS_DAILY',
      timeWindow: {
        fromInclusive: new Date(to.getTime() - 5 * 24 * 60 * 60 * 1000).toISOString(),
        toExclusive: to.toISOString(),
      },
      timezone: 'Asia/Shanghai',
    }
  }

  async function loadHistory(role: DemoRole, append = false): Promise<boolean> {
    if (append && (historyLoading.value || !historyHasNext.value)) return false
    const current = generation
    const currentHistory = ++historyGeneration
    const targetPage = append ? historyPage.value + 1 : 0
    historyLoading.value = true
    try {
      const page = await listOperationsDailyReports(role, { page: targetPage, size: 20 })
      if (current === generation && currentHistory === historyGeneration) {
        reports.value = append
          ? [...new Map([...reports.value, ...page.content].map((item) => [item.reportId, item])).values()]
          : page.content
        historyPage.value = page.page
        historyHasNext.value = page.hasNext
        historyTotal.value = page.totalElements
        historyLoaded.value = true
        return true
      }
      return false
    } catch (cause) {
      if (current === generation && currentHistory === historyGeneration) error.value = cause instanceof Error ? cause.message : String(cause)
      return false
    } finally {
      if (current === generation && currentHistory === historyGeneration) historyLoading.value = false
    }
  }

  async function open(reportId: string, role: DemoRole): Promise<void> {
    const current = ++generation
    historyGeneration += 1
    if (pendingCreation?.accepted) pendingCreation = null
    busy.value = false
    historyLoading.value = false
    detailLoading.value = true
    error.value = ''
    try {
      const detail = await getOperationsDailyReport(reportId, role)
      if (current !== generation) return
      report.value = detail
      runId.value = detail.runId
      options.trace?.subscribe(detail.traceId, role)
    } catch (cause) {
      if (current === generation) error.value = cause instanceof Error ? cause.message : String(cause)
    } finally {
      if (current === generation) detailLoading.value = false
    }
  }

  async function start(role: DemoRole, requested?: OperationsReportCreateRequest, requestIdentity?: string): Promise<void> {
    if (busy.value) return
    const current = ++generation
    historyGeneration += 1
    busy.value = true
    detailLoading.value = false
    historyLoading.value = false
    error.value = ''
    let createAccepted = false
    try {
      const request = requested ?? defaultRequest()
      const fingerprint = requestIdentity ?? (requested ? JSON.stringify(request) : 'default:5-day-window')
      if (!pendingCreation || pendingCreation.role !== role || pendingCreation.fingerprint !== fingerprint) {
        pendingCreation = {
          role,
          key: createRequestId(),
          request,
          fingerprint,
          accepted: false,
        }
      }
      const accepted = await startOperationsDailyReport(role, pendingCreation.request, pendingCreation.key)
      pendingCreation.accepted = true
      createAccepted = true
      if (current !== generation) return
      runId.value = accepted.runId
      options.trace?.subscribe(accepted.runId, role)
      for (let attempt = 0; attempt < maxPolls; attempt += 1) {
        const detail = await getOperationsDailyReport(accepted.reportId, role)
        if (current !== generation) return
        report.value = detail
        if (detail.status !== 'REQUESTED' && detail.status !== 'GENERATING') {
          pendingCreation = null
          await loadHistory(role)
          return
        }
        await sleep(pollIntervalMs)
      }
      throw new Error('运营日报超时，可稍后从报告历史查看最终状态')
    } catch (cause) {
      if (current === generation) {
        if (!createAccepted && cause instanceof OperationsReportHttpError && isDefinitiveCreateRejection(cause)) {
          pendingCreation = null
        }
        error.value = cause instanceof Error ? cause.message : String(cause)
      }
    } finally {
      if (current === generation) busy.value = false
    }
  }

  async function download(reportId: string, role: DemoRole): Promise<void> {
    if (downloadingId.value) return
    downloadingId.value = reportId
    error.value = ''
    try { await downloadOperationsDailyReport(reportId, role) }
    catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
    finally { if (downloadingId.value === reportId) downloadingId.value = null }
  }

  async function refresh(role: DemoRole): Promise<void> {
    if (busy.value || detailLoading.value) return
    const reportId = report.value?.reportId ?? null
    error.value = ''
    const historyRefreshed = await loadHistory(role)
    if (historyRefreshed && reportId) await open(reportId, role)
  }

  function reset(): void {
    generation += 1
    historyGeneration += 1
    pendingCreation = null
    report.value = null
    reports.value = []
    runId.value = null
    busy.value = false
    detailLoading.value = false
    downloadingId.value = null
    historyLoading.value = false
    historyLoaded.value = false
    historyPage.value = -1
    historyHasNext.value = false
    historyTotal.value = 0
    error.value = ''
  }

  onScopeDispose(reset)
  return { report, reports, runId, busy, detailLoading, downloadingId, historyLoading, historyLoaded,
    historyHasNext, historyTotal, error, start, open, loadHistory, download, refresh, reset }
}
