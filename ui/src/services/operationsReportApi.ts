import type { DemoRole } from '../types/workflow'
import type { OperationsDailyReport } from '../types/operationsReport'

async function readError(response: Response): Promise<Error> {
  const detail = await response.json().catch(() => null) as { message?: string } | null
  return new Error(detail?.message ?? `请求失败（${response.status}）`)
}

export async function startOperationsDailyReport(role: DemoRole): Promise<{ runId: string; statusUrl: string }> {
  const response = await fetch('/api/operations-reports/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Demo-Role': role },
    body: '{}',
  })
  if (!response.ok && response.status !== 202) throw await readError(response)
  const result = await response.json() as { runId?: string; statusUrl?: string }
  if (!result.runId || !result.statusUrl) throw new Error('日报启动失败：响应缺少 runId')
  return { runId: result.runId, statusUrl: result.statusUrl }
}

export async function getOperationsDailyReport(runId: string, role: DemoRole): Promise<OperationsDailyReport> {
  const response = await fetch(`/api/operations-reports/runs/${encodeURIComponent(runId)}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw await readError(response)
  return response.json() as Promise<OperationsDailyReport>
}
