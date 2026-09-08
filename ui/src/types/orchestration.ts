import type { DemoRole } from './workflow'

export type OrchestrationStatus = 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'WAITING_APPROVAL' | 'CANCELLED'
export type OrchestrationStepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'SKIPPED' | 'BLOCKED' | 'FAILED' | 'WAITING_APPROVAL' | 'CANCELLED'

export interface OrchestrationInput {
  question: string
  alertId: string | null
  buildingIds: string[]
  energyRelated: boolean
  crossDomain: boolean
  securityRelated: boolean
  requestAction: boolean
}

export interface OrchestrationStep {
  id: string
  type: string
  capability: string
  required: boolean
  status: OrchestrationStepStatus
  startedAt: string | null
  completedAt: string | null
  inputSummary: string | null
  outputSummary: string | null
  runReference: string | null
  evidenceReferences: string[]
  sourceReferences: string[]
  recommendations: string[]
  failureReason: string | null
  approvalResult: string | null
  approvalExpiresAt: string | null
}

export interface OrchestrationResult {
  conclusion: string
  recommendations: string[]
  evidenceReferences: string[]
  sourceReferences: string[]
  childRuns: Record<string, string>
  skippedSteps: string[]
  partialReasons: string[]
  humanApprovalResult: string | null
}

export interface OrchestrationRun {
  runId: string
  definitionId: string
  status: OrchestrationStatus
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  role: DemoRole
  input: OrchestrationInput
  summary: string | null
  steps: OrchestrationStep[]
  evidence: string[]
  traceId: string
  failureReason: string | null
  result: OrchestrationResult | null
  cancelRequested: boolean
  revision: number
}

export interface StartOrchestrationResponse {
  runId: string
  status: OrchestrationStatus
  statusUrl: string
  traceUrl: string
  idempotentReplay: boolean
}
