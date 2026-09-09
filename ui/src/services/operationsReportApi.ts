import type { DemoRole } from '../types/workflow'
import type { OperationsDailyReport, OperationsReportPage, OperationsReportStatus } from '../types/operationsReport'
import { createRequestId } from '../utils/requestId'

async function readError(response: Response): Promise<Error> {
  const detail = await response.json().catch(() => null) as { message?: string } | null
  return new Error(detail?.message ?? `请求失败（${response.status}）`)
}

export async function startOperationsDailyReport(role: DemoRole, idempotencyKey = createRequestId()): Promise<{ reportId: string; runId: string; statusUrl: string }> {
  const response = await fetch('/api/operations-reports', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Demo-Role': role, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ reportType: 'OPERATIONS_DAILY' }),
  })
  if (!response.ok) throw await readError(response)
  const result = await response.json() as { reportId?: string; runId?: string; statusUrl?: string }
  if (!result.reportId || !result.runId || !result.statusUrl) throw new Error('日报启动失败：响应缺少报告标识')
  return { reportId: result.reportId, runId: result.runId, statusUrl: result.statusUrl }
}

export async function getOperationsDailyReport(reportId: string, role: DemoRole): Promise<OperationsDailyReport> {
  const response = await fetch(`/api/operations-reports/${encodeURIComponent(reportId)}`, { headers: { 'X-Demo-Role': role } })
  if (!response.ok) throw await readError(response)
  return response.json() as Promise<OperationsDailyReport>
}

export async function listOperationsDailyReports(role: DemoRole, options: { page?: number; size?: number; status?: OperationsReportStatus } = {}): Promise<OperationsReportPage> {
  const query = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.size ?? 20), reportType: 'OPERATIONS_DAILY' })
  if (options.status) query.set('status', options.status)
  const response = await fetch(`/api/operations-reports?${query}`, { headers: { 'X-Demo-Role': role } })
  if (!response.ok) throw await readError(response)
  const page = await response.json() as Partial<OperationsReportPage>
  if (!Array.isArray(page.content) || typeof page.page !== 'number' || typeof page.size !== 'number'
      || typeof page.totalElements !== 'number' || typeof page.hasNext !== 'boolean') {
    throw new Error('报告历史响应格式无效')
  }
  return page as OperationsReportPage
}

export async function downloadOperationsDailyReport(reportId: string, role: DemoRole): Promise<void> {
  const response = await fetch(`/api/operations-reports/${encodeURIComponent(reportId)}/download`, { headers: { 'X-Demo-Role': role } })
  if (!response.ok) throw await readError(response)
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  const quoted = /filename="([^"]+)"/i.exec(disposition)?.[1]
  const fileName = encoded ? decodeURIComponent(encoded) : quoted ?? 'smart-park-operations-report.md'
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileName.replace(/[\r\n]/g, '')
    link.click()
  } finally {
    URL.revokeObjectURL(url)
  }
}
