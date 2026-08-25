/** Mirrors the public operations-analysis status projection. */

export interface MetricSelectionDto {
  term: string
  metric: string
}

export interface AnalysisStatusDto {
  runId: string
  status: 'RUNNING' | 'NEEDS_CLARIFICATION' | 'COMPLETED' | 'FAILED'
  clarificationQuestions?: string[]
  /** One candidate metric list per pending clarification question. */
  clarificationOptions?: string[][]
  summary?: string
  rowCount?: number
  truncated?: boolean
  columns?: string[]
  rows?: Array<Array<unknown>>
  failureStage?: string
  createdAt: string
}

export function isTerminalAnalysisStatus(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED'
}
