import type { CollaborationRun, StartCollaborationResponse } from '../types/collaboration'

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string; error?: string } | null
    throw new Error(body?.message ?? body?.error ?? `专家协作请求失败（${response.status}）`)
  }
  return response.json() as Promise<T>
}

export async function startCollaboration(question: string): Promise<StartCollaborationResponse> {
  return parse(await fetch('/api/expert-collaboration/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }),
  }))
}

export async function getCollaborationRun(runId: string): Promise<CollaborationRun> {
  return parse(await fetch(`/api/expert-collaboration/runs/${encodeURIComponent(runId)}`))
}
