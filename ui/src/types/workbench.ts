export type ShowcaseScenarioId =
  | 'ALERT_WORKFLOW'
  | 'EXPERT_COLLABORATION'
  | 'OPERATIONS_ANALYSIS'
  | 'CUSTOMER_SERVICE'
  | 'VOICE_ASSISTANT'

export type WorkbenchView = 'workflow' | 'customer' | 'voice' | 'collaboration' | 'analytics' | 'governance'
export type GuidedWorkbenchView = WorkbenchView

export interface ShowcaseLaunchInput {
  alertId: string | null
  question: string | null
}

export interface ScenarioLaunchRequest {
  requestId: number
  mode: 'guided'
  scenarioId: ShowcaseScenarioId
  view: GuidedWorkbenchView
  launchInput?: ShowcaseLaunchInput
}

export type GuidedLaunchState = 'preparing' | 'started' | 'ready' | 'failed'

export interface GuidedLaunchUpdate {
  requestId: number
  state: GuidedLaunchState
  message: string
}

export interface WorkbenchNavItem {
  value: WorkbenchView
  label: string
  available: boolean
}

export interface WorkbenchEvidenceItem {
  label: string
  value: string
  tone?: 'default' | 'verified' | 'warning' | 'danger'
}
