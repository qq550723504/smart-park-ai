export type OperationsReportStatus = 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
export type OperationsReportSectionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export type OperationsReportTimeResolutionStatus = 'NONE' | 'PARSED' | 'EMPTY'
export type OperationsReportTimeResolutionSource = 'EXPLICIT_USER_RANGE' | 'DEFAULT_METRIC_LOOKBACK'

export interface OperationsReportTimeResolution {
  status: OperationsReportTimeResolutionStatus
  fromInclusive: string | null
  toExclusive: string | null
  source: OperationsReportTimeResolutionSource
  explanation: string
  empty: boolean
}

export interface OperationsReportSection {
  id: string
  title: string
  question: string
  status: OperationsReportSectionStatus
  summary?: string
  rowCount?: number
  truncated?: boolean
  columns?: string[]
  rows?: unknown[][]
  timeResolution?: OperationsReportTimeResolution
  failureStage?: string
}

export interface OperationsDailyReport {
  runId: string
  status: OperationsReportStatus
  createdAt: string
  updatedAt: string
  sections: OperationsReportSection[]
}
