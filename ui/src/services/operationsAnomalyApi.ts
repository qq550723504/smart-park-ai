import type { DemoRole } from '../types/workflow'
import type { AnomalyEvidence, AnomalyFilters, AnomalyOverview } from '../types/operationsAnomaly'

async function readError(response: Response): Promise<Error> {
  const detail = await response.json().catch(() => null) as { message?: string } | null
  return new Error(detail?.message ?? `请求失败（${response.status}）`)
}

function queryString(filters: AnomalyFilters = {}): string {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  const encoded = params.toString()
  return encoded ? `?${encoded}` : ''
}

export async function getAnomalyOverview(role: DemoRole, filters: AnomalyFilters = {}): Promise<AnomalyOverview> {
  const response = await fetch(`/api/operations/anomaly-overview${queryString(filters)}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw await readError(response)
  return response.json() as Promise<AnomalyOverview>
}

export async function getAnomalyEvidence(role: DemoRole, buildingId: string, filters: AnomalyFilters = {}): Promise<AnomalyEvidence> {
  const response = await fetch(`/api/operations/anomaly-evidence/${encodeURIComponent(buildingId)}${queryString(filters)}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw await readError(response)
  return response.json() as Promise<AnomalyEvidence>
}
