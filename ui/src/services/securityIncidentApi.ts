import type { DemoRole } from '../types/workflow'
import type { SecurityIncident, SecurityIncidentPage, SecurityIncidentStatus } from '../types/securityIncident'

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string; error?: string } | null
    throw new Error(body?.message ?? body?.error ?? `安全事件请求失败（${response.status}）`)
  }
  return response.json() as Promise<T>
}

function headers(role: DemoRole): HeadersInit { return { 'X-Demo-Role': role } }

export async function listSecurityIncidents(role: DemoRole, status?: SecurityIncidentStatus): Promise<SecurityIncidentPage> {
  const query = status ? `?status=${encodeURIComponent(status)}&limit=100` : '?limit=100'
  return parse(await fetch(`/api/security/incidents${query}`, { headers: headers(role) }))
}

export async function getSecurityIncident(role: DemoRole, incidentId: string): Promise<SecurityIncident> {
  return parse(await fetch(`/api/security/incidents/${encodeURIComponent(incidentId)}`, { headers: headers(role) }))
}

export async function reviewSecurityIncident(role: DemoRole, incidentId: string): Promise<SecurityIncident> {
  return parse(await fetch(`/api/security/incidents/${encodeURIComponent(incidentId)}/review`, { method: 'POST', headers: headers(role) }))
}

export async function handoffSecurityIncident(role: DemoRole, incidentId: string): Promise<SecurityIncident> {
  return parse(await fetch(`/api/security/incidents/${encodeURIComponent(incidentId)}/handoff`, { method: 'POST', headers: headers(role) }))
}
