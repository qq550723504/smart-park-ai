import type { AnalysisStatusDto } from '../types/analytics'

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok && response.status !== 202) {
    const detail = await response.json().catch(() => null)
    throw new Error((detail as { message?: string })?.message ?? `请求失败（${response.status}）`)
  }
  return response.json() as Promise<T>
}

export async function startAnalysis(question: string): Promise<{ runId: string }> {
  const result = await postJson<{ runId: string }>('/api/operations-analysis/runs', { question })
  if (!result?.runId) throw new Error('启动分析失败：响应缺少 runId')
  return result
}

export async function submitClarification(
  runId: string,
  selections: Array<{ term: string; metric: string }>,
): Promise<AnalysisStatusDto> {
  return postJson<AnalysisStatusDto>(`/api/operations-analysis/runs/${encodeURIComponent(runId)}/clarifications`, {
    selections,
  })
}

export async function getAnalysisStatus(runId: string): Promise<AnalysisStatusDto> {
  const response = await fetch(`/api/operations-analysis/runs/${encodeURIComponent(runId)}`)
  if (!response.ok) throw new Error(`查询分析状态失败（${response.status}）`)
  return response.json() as Promise<AnalysisStatusDto>
}
