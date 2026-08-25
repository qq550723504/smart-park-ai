export type ExpertDomain = 'ENERGY' | 'DEVICE' | 'SECURITY'
export type FindingStatus = 'SUPPORTED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED'
export type CollaborationStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'NEEDS_CLARIFICATION'

export interface SupervisorPlan {
  normalizedQuestion: string
  selectedDomains: ExpertDomain[]
  assignments: Partial<Record<ExpertDomain, string>>
  selectionReason: string
}

export interface ExpertFinding {
  domain: ExpertDomain
  status: FindingStatus
  conclusion: string
  evidenceRefs: string[]
  confidence: number
  nextChecks: string[]
}

export interface Synthesis {
  status: FindingStatus
  conclusion: string
  evidenceRefs: string[]
  confidence: number
  uncertainties: string[]
}

export interface CollaborationRun {
  runId: string
  question: string
  status: CollaborationStatus
  plan: SupervisorPlan | null
  findings: ExpertFinding[]
  synthesis: Synthesis | null
  error: string | null
  updatedAt: string
}

export interface StartCollaborationResponse {
  runId: string
  statusUrl: string
  eventsUrl: string
}
