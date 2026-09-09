import type { AnomalyBuildingSummary, AnomalyWindow } from './operationsAnomaly'

export type CustomerPage = 'overview' | 'analysis'

export interface CustomerEnergyWindow {
  from: string
  to: string
  timezone: string
  granularity: 'HOUR'
}

export interface CustomerAnalysisContext {
  buildingId: string
  buildingName: string
  anomalyId: string | null
  title: string
  priority: '高' | '中' | '关注'
  summary: AnomalyBuildingSummary | null
  anomalyWindow: AnomalyWindow
  energyWindow: CustomerEnergyWindow
  source: 'OPERATIONS_ANALYTICS'
}

export const CUSTOMER_BUILDINGS: Record<string, { name: string; position: { left: string; top: string } }> = {
  B1: { name: '创新中心', position: { left: '30%', top: '65%' } },
  B2: { name: '研发大厦', position: { left: '58%', top: '46%' } },
  B3: { name: '运营中心', position: { left: '76%', top: '24%' } },
}

export function customerBuildingName(id: string): string {
  return CUSTOMER_BUILDINGS[id]?.name ?? id
}

export function alignedRecent24Hours(window: AnomalyWindow): CustomerEnergyWindow | null {
  const to = Date.parse(window.to)
  const from = Date.parse(window.from)
  if (!Number.isFinite(to) || !Number.isFinite(from)) return null
  const hour = 60 * 60 * 1000
  const alignedTo = Math.floor(to / hour) * hour
  const alignedFrom = Math.max(Math.ceil(from / hour) * hour, alignedTo - 24 * hour)
  if (alignedFrom >= alignedTo) return null
  return {
    from: new Date(alignedFrom).toISOString(),
    to: new Date(alignedTo).toISOString(),
    timezone: window.timezone,
    granularity: 'HOUR',
  }
}
