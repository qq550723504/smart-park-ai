import type { DemoRole } from './workflow'

export type SecurityIncidentRisk = 'LOW' | 'MEDIUM' | 'HIGH'
export type SecurityIncidentStatus = 'OPEN' | 'REVIEWED' | 'HANDOFF'

export interface SecurityIncidentSummary {
  incidentId: string
  parkId: string
  buildingId: string
  eventType: string
  riskLevel: SecurityIncidentRisk
  status: SecurityIncidentStatus
  openedAt: string
  lastOccurredAt: string
  eventCount: number
  alertCount: number
  summary: string
}

export interface SecurityIncidentEvidence {
  sourceId: string
  occurredAt: string
  summary: string
}

export interface SecurityIncidentTimelineEntry {
  sourceType: string
  sourceId: string
  occurredAt: string
  label: string
}

export interface SecurityIncident extends SecurityIncidentSummary {
  eventIds: string[]
  alertIds: string[]
  evidence: SecurityIncidentEvidence[]
  timeline: SecurityIncidentTimelineEntry[]
  recommendations: string[]
  reviewedAt?: string
  handoffWorkItemId?: string
}

export interface SecurityIncidentPage {
  items: SecurityIncidentSummary[]
  total: number
}

export type SecurityIncidentRole = Extract<DemoRole, 'APPROVER' | 'ADMIN'>
