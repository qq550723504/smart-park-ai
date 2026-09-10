import type { DemoRole } from './workflow'

export interface AnomalyFilters {
  from?: string
  to?: string
  buildingId?: string
  riskLevel?: string
  category?: string
  status?: string
  deviceType?: string
}

export interface AnomalyWindow {
  from: string
  to: string
  timezone: string
}

export interface AnomalyBreakdown {
  key: string
  count: number
}

export interface AnomalyBuildingSummary {
  buildingId: string
  alertCount: number
  highRiskAlertCount: number
  offlineDeviceCount: number
  energyDeviationPct: number | null
}

export type AnomalyDomain = 'alerts' | 'devices' | 'energy'
export type AnomalyDomainStatus = 'OK' | 'PARTIAL' | 'UNAVAILABLE'
export type AnomalyDomainStatusMap = Record<AnomalyDomain, AnomalyDomainStatus>

export interface AnomalyOverview {
  window: AnomalyWindow
  asOf: string | null
  summary: {
    alertCount: number
    highRiskAlertCount: number
    offlineDeviceCount: number
    affectedBuildingCount: number
  }
  breakdowns: Record<string, AnomalyBreakdown[]>
  buildings: AnomalyBuildingSummary[]
  domainStatus: AnomalyDomainStatusMap
}

export interface AnomalyEvidence {
  buildingId: string
  window: AnomalyWindow
  asOf: string | null
  alerts: Array<Record<string, unknown>>
  devices: Array<Record<string, unknown>>
  energy: Array<Record<string, unknown>>
  domainStatus: AnomalyDomainStatusMap
}

export type { DemoRole }
