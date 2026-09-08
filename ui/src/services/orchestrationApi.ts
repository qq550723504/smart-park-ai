import type { DemoRole } from '../types/workflow'
import type { OrchestrationInput, OrchestrationRun, StartOrchestrationResponse } from '../types/orchestration'

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message || `编排请求失败（${response.status}）`)
  }
  return response.json() as Promise<T>
}

export function startOrchestration(role: DemoRole, idempotencyKey: string, input: OrchestrationInput) {
  return fetch('/api/orchestrations/runs', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-Demo-Role': role,
    },
    body: JSON.stringify({ definitionId: 'JOINT_ANOMALY_ASSESSMENT', input }),
  }).then((response) => parse<StartOrchestrationResponse>(response))
}

export function getOrchestration(role: DemoRole, runId: string) {
  return fetch(`/api/orchestrations/runs/${encodeURIComponent(runId)}`, {
    headers: { 'X-Demo-Role': role },
  }).then((response) => parse<OrchestrationRun>(response))
}

export function cancelOrchestration(role: DemoRole, runId: string) {
  return fetch(`/api/orchestrations/runs/${encodeURIComponent(runId)}/cancel`, {
    method: 'POST', headers: { 'X-Demo-Role': role },
  }).then((response) => parse<OrchestrationRun>(response))
}
