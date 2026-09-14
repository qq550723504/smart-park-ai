import type { DemoRole } from './workflow'

export type SecurityIncidentRisk = 'LOW' | 'MEDIUM' | 'HIGH'
export type SecurityIncidentStatus = 'OPEN' | 'REVIEWED' | 'HANDOFF'
export type SecurityEventType =
  | 'FIRE_SMOKE'
  | 'PERIMETER_INTRUSION'
  | 'CROWDING'
  | 'POST_ABSENCE'
  | 'ACCESS_ANOMALY'
  | 'UNKNOWN'
export type SecurityDisposition =
  | 'UNREVIEWED'
  | 'CONFIRMED_INCIDENT'
  | 'FALSE_POSITIVE'
  | 'INCONCLUSIVE'
  | 'DUPLICATE'
export type SecurityDispositionSource = 'HUMAN_REVIEW' | 'REGISTERED_MODEL'
export type SecurityCapabilityState = 'AVAILABLE' | 'ADAPTED' | 'NOT_READY'

/**
 * Separates "the model defines this event type" from "this deployment has a real
 * source connected". A type is never reported AVAILABLE without a production source.
 */
export interface SecurityEventCapability {
  eventType: SecurityEventType
  modelSupported: boolean
  sourceConnected: boolean
  productionSource: boolean
  state: SecurityCapabilityState
}

/** Dispositions an operator may choose from the incident center. */
export const REVIEW_DISPOSITIONS: Array<{ value: SecurityDisposition; label: string }> = [
  { value: 'CONFIRMED_INCIDENT', label: '确认事件' },
  { value: 'FALSE_POSITIVE', label: '误报（人工复核结论）' },
  { value: 'INCONCLUSIVE', label: '无法判定' },
  { value: 'DUPLICATE', label: '重复事件' },
]

export interface SecurityIncidentSummary {
  incidentId: string
  parkId: string
  buildingId: string
  eventType: SecurityEventType | string
  riskLevel: SecurityIncidentRisk
  status: SecurityIncidentStatus
  openedAt: string
  lastOccurredAt: string
  eventCount: number
  alertCount: number
  summary: string
  disposition: SecurityDisposition
  dispositionSource?: SecurityDispositionSource
  dispositionDecidedAt?: string
}

export interface SecurityIncidentEvidence {
  sourceId: string
  occurredAt: string
  summary: string
  rawEventType?: string
  sourceType?: string
  eventSourceId?: string
  severity?: string
  confidence?: number
}

export interface SecurityIncidentTimelineEntry {
  sourceType: string
  sourceId: string
  occurredAt: string
  label: string
  /** Source-qualified security event reference, present for security event entries. */
  reference?: string
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
