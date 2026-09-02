export type OperationsReportStatus = 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
export type OperationsReportSectionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

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
  timeResolution?: Record<string, unknown>
  failureStage?: string
}

export interface OperationsDailyReport {
  runId: string
  status: OperationsReportStatus
  createdAt: string
  updatedAt: string
  sections: OperationsReportSection[]
}
