export type EnergyTimeSeriesStatus = 'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE'
export type EnergyTimeSeriesGranularity = 'HOUR' | 'DAY'

export interface EnergyTimeSeriesPoint {
  timestamp: string
  value: number
}

export interface EnergyTimeSeriesSeries {
  buildingId: string
  points: EnergyTimeSeriesPoint[]
  missingTimestamps: string[]
}

export interface EnergyTimeSeriesResponse {
  metric: string
  unit: string
  timezone: string
  window: {
    from: string
    to: string
    granularity: EnergyTimeSeriesGranularity
  }
  status: EnergyTimeSeriesStatus
  series: EnergyTimeSeriesSeries[]
  asOf: string | null
  source: {
    system: string
    metricDefinition: string
    status: EnergyTimeSeriesStatus
  }
  evidence: Array<{
    buildingId: string
    expectedPointCount: number
    actualPointCount: number
    firstObservedAt: string | null
    lastObservedAt: string | null
  }>
}

export interface EnergyTimeSeriesFilters {
  metric?: string
  buildingIds: string[]
  from?: string
  to?: string
  granularity?: EnergyTimeSeriesGranularity
}
