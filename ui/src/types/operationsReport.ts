export type OperationsReportStatus = 'REQUESTED' | 'GENERATING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
export type OperationsReportSectionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'UNAVAILABLE' | 'FAILED'

export interface OperationsReportTimeWindow { fromInclusive: string; toExclusive: string }
export interface OperationsReportTimeResolution {
  status: 'NONE' | 'PARSED' | 'EMPTY'
  fromInclusive: string | null
  toExclusive: string | null
  source: 'EXPLICIT_USER_RANGE' | 'DEFAULT_METRIC_LOOKBACK'
  explanation: string
  empty: boolean
}
export interface OperationsReportEvidence {
  sourceSystem: string; metric: string; entity: string; observationTime: string; runReference: string; summary: string
}
export interface OperationsReportSource { sourceSystem: string; metric: string; unit: string; status: string; asOf: string }
export interface OperationsReportArtifact {
  artifactId: string; format: string; fileName: string; contentType: string; size: number; createdAt: string; checksum: string; rendererVersion: string
}
export interface OperationsReportSection {
  sectionId: string
  title: string
  question: string
  status: OperationsReportSectionStatus
  summary: string
  rowCount: number
  truncated: boolean
  columns: string[]
  rows: unknown[][]
  timeResolution: OperationsReportTimeResolution | Record<string, never>
  evidenceReferences: OperationsReportEvidence[]
  sourceReferences: OperationsReportSource[]
  partialReason: string | null
  failureReason: string | null
  runId: string | null
}
export interface OperationsReportSummary {
  reportId: string
  reportType: string
  title: string
  status: OperationsReportStatus
  createdAt: string
  completedAt: string | null
  timeWindow: OperationsReportTimeWindow
  timezone: string
  asOf: string | null
  runId: string
  traceId: string
  downloadAvailable: boolean
  artifact?: OperationsReportArtifact
}
export interface OperationsDailyReport extends OperationsReportSummary {
  requestedBy: string
  role: string
  startedAt: string | null
  summary: string
  sections: OperationsReportSection[]
  evidence: OperationsReportEvidence[]
  sourceReferences: OperationsReportSource[]
  schemaVersion: number
  generationVersion: string
}
export interface OperationsReportPage {
  content: OperationsReportSummary[]; page: number; size: number; totalElements: number; hasNext: boolean
}
