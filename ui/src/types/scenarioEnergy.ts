/**
 * B2 night-energy unified simulated scenario (SCN-B2-NIGHT-ENERGY-001 v1.0.0).
 *
 * These types describe the scenario fixture, its derived read model, and the
 * shared runtime state. They are intentionally separate from the online
 * operations-analytics contracts: SCN objects must never be sent to the
 * existing production/mock endpoints, and the existing online B2 23.68% case
 * is not touched.
 */

export type ScenarioDataSource = 'SCENARIO_FIXTURE'

export interface ScenarioWindow {
  from: string
  to: string
  semantics: string
}

export interface ScenarioMeta {
  scenarioId: string
  version: string
  title: string
  artifactStatus: string
  dataSource: ScenarioDataSource
  businessDataMode: string
  narrativeMode: string
  banner: string
  detailModeLabel: string
  repositoryBaseline: { repository: string; commit: string }
  description: string
  currency: string
  energyUnit: string
  powerUnit: string
  rounding: string
  createdOn: string
}

export interface ScenarioClock {
  timezone: string
  initialNow: string
  policy: string
  observationWindow: ScenarioWindow
  baselineWindowRef: string
  followupWindow: ScenarioWindow
  verificationNow: string
  hostClockAffectsBusinessData: boolean
}

export interface ScenarioBuilding {
  buildingId: string
  name: string
  purpose: string
  meterRule: string
  scale?: string
}

export interface ScenarioDevice {
  deviceId: string
  buildingId: string
  zoneId: string | null
  name: string
  type: string
  aggregationOnly: boolean
  adjustable: boolean
  state: string
  note?: string
}

export interface ScenarioActor {
  actorId: string
  displayName: string
  role: string
  teamName?: string
}

export interface ScenarioOperatingFacts {
  scheduleId: string
  publicOffHours: { from: string; to: string; crossesMidnight: boolean }
  publicObservedExtraRun: { from: string; to: string; deviceIds: string[] }
  publicReservation: { recordId: string; from: string; to: string; approvedUseCount: number; scope: string }
  researchOvertime: { recordId: string; zoneId: string; from: string; to: string; approved: boolean }
  protectedDeviceIds: string[]
  causeBoundary: string
}

export interface EnergyLedgerRow {
  readingId: string
  buildingId: string
  deviceId: string
  bucketStart: string
  durationMinutes: number
  baselineKwh: number
  observedKwh: number
  quality: string
  /**
   * Set by the PARTIAL_DATA variant: the observed value is genuinely missing.
   * It must never be treated as zero or silently back-filled; totals exclude
   * the bucket from observed sums while the baseline row stays available.
   */
  observedMissing?: boolean
}

export interface ScenarioEventTemplate {
  anomalyId: string
  parkId: string
  buildingId: string
  deviceId: string
  affectedDeviceIds: string[]
  category: string
  priority: string
  title: string
  observedAt: string
  windowRef: string
  initialStatus: string
  surfaceAfter: string
  businessKeyRule: string
  resolvedByOrderAlone: boolean
  multiCheckDeduplication: string
}

export interface ScenarioPatrolCheckTemplate {
  checkId: string
  label: string
  scope: string
  rule: string
  thresholdPct?: string
  thresholdOrigin?: string
  successResult: 'ATTENTION' | 'PASS'
  eventRef: string | null
  evidenceRefs: string[]
}

export interface ScenarioAssessmentTemplate {
  mode: string
  title: string
  summaryTemplate: string
  facts: Array<{ factId: string; template: string; evidenceRefs: string[] }>
  unknowns: string[]
  recommendedPlanId: string
  aiRequestRequired: boolean
}

export interface ScenarioOptimization {
  calculatorId: string
  doNotEvaluateArbitraryExpressions: boolean
  defaultParameters: {
    savedHours: string
    tariffCnyPerKwh: string
    applicableDaysPerMonth: number
  }
  parameterBounds: {
    savedHours: { min: string; max: string; step: string }
    tariffCnyPerKwh: { min: string; max: string; step: string }
    applicableDaysPerMonth: { min: number; max: number; step: number }
  }
  adjustmentWindowInObservation: { from: string; maxTo: string }
  adjustmentWindowInFollowup: { from: string; maxTo: string }
  powerAssumptions: Array<{ deviceId: string; avoidablePowerKw: string }>
  formulaDefinitions: Record<string, string>
  constraints: string[]
  costScope: string
  noMeasuredCustomerSavingsClaim: boolean
}

export interface ScenarioPlan {
  planId: string
  label: string
  targetDeviceIds: string[]
  recommended: boolean
  createsOrder: boolean
  description: string
}

export interface ScenarioFollowupTemplate {
  generatorId: string
  responseFactor: string
  factorMeaning: string
  generationRule: string
  visibleAfter: string
  resultLabel: string
  sameExternalConditionsAssumption: boolean
  residualEventPolicy: string
}

export interface ScenarioWorkOrderTemplate {
  idPrefix: string
  workflowIdPrefix: string
  parkId: string
  buildingId: string
  deviceId: string
  alertId: string
  reviewerActorId: string
  assigneeActorId: string
  initialStatus: ScenarioWorkOrderStatus
  statusLabels: Record<string, string>
  materializeAfter: string
  materializeFields: string[]
  idempotencyKeyRule: string
  completionScope: string
}

export interface ScenarioReportContract {
  idPrefix: string
  kind: string
  format: string
  snapshotPolicy: string
  allowedAfter: ScenarioStage[]
  sections: string[]
  sourceFields: string[]
  idempotencyKeyRule: string
  readAndDownloadNeverGenerate: boolean
  existingReportBoundary: string
}

export interface ScenarioVariant {
  variantId: ScenarioVariantId
  label: string
  delta: Record<string, unknown> | null
  expected: string
}

export interface ScenarioExpectedAssertions {
  use: string
  initialB2: LedgerTotals
  channelTotals: Record<string, { baselineKwh: number; observedKwh: number }>
  initialPark: LedgerTotals
  defaultPlanResults: Record<string, PlanEstimate & FollowupTotals>
  parameterExample: {
    planId: string
    savedHours: number
    tariffCnyPerKwh: number
    applicableDaysPerMonth: number
    outputs: PlanEstimate & FollowupTotals
  }
  patrol: {
    checkCount: number
    attentionCheckCount: number
    passCheckCount: number
    uniqueEventCount: number
    initialWorkOrderCount: number
  }
  recommendedPostVerification: {
    workOrderStatus: ScenarioWorkOrderStatus
    eventStatus: ScenarioEventStatus
    remainingExcessKwh: number
    parkFollowupKwh: number
    futureResultVisibleBeforeVerification: boolean
  }
}

export interface ScenarioFixture {
  meta: ScenarioMeta
  clock: ScenarioClock
  park: { parkId: string; name: string }
  buildings: ScenarioBuilding[]
  zones: Array<{ zoneId: string; buildingId: string; name: string; protected: boolean; protectionReason?: string }>
  devices: ScenarioDevice[]
  actors: ScenarioActor[]
  operatingFacts: ScenarioOperatingFacts
  energyLedger: {
    granularity: string
    bucketMeaning: string
    rows: EnergyLedgerRow[]
    meterAggregation: { parentDeviceId: string; childDeviceIds: string[]; rule: string }
    supportingBuildingsRule: string
  }
  eventTemplate: ScenarioEventTemplate
  patrolChecks: ScenarioPatrolCheckTemplate[]
  assessmentTemplate: ScenarioAssessmentTemplate
  optimization: ScenarioOptimization
  plans: ScenarioPlan[]
  followupSimulation: ScenarioFollowupTemplate
  workOrderTemplate: ScenarioWorkOrderTemplate
  reportContract: ScenarioReportContract
  runtimeContract: {
    persistence: string
    identityRule: string
    initialState: ScenarioRunState
    resetPolicy: string
    pendingPolicy: string
    stageLabels: Record<ScenarioStage, string>
    mainStages: ScenarioStage[]
    actionTransitions: ScenarioActionTransition[]
    orthogonalActions: Record<string, string>
  }
  variants: ScenarioVariant[]
  expectedAssertions: ScenarioExpectedAssertions
  sourceBasis: Array<Record<string, unknown>>
}

// ---------------------------------------------------------------------------
// Derived read model
// ---------------------------------------------------------------------------

export interface LedgerTotals {
  baselineKwh: number
  observedKwh: number
  /** Number of rows whose observation is unknown (0 when coverage is complete). */
  observedMissingCount: number
  /** True only when every expected observation in the window is present. */
  observedComplete: boolean
  /**
   * Excess over baseline. `null` while observed coverage is incomplete: a
   * partial observed subtotal and a complete baseline cover different periods,
   * so an exact excess would be fabricated.
   */
  excessKwh: number | null
  /** Baseline deviation percentage. `null` while observed coverage is incomplete. */
  deviationPct: number | null
}

export interface ChannelTotal {
  deviceId: string
  baselineKwh: number
  observedKwh: number
  observedMissingCount: number
}

export interface ScenarioParameters {
  savedHours: number
  tariffCnyPerKwh: number
  applicableDaysPerMonth: number
}

export interface PlanEstimate {
  planId: string
  estimatedSavedKwhPerDay: number
  estimatedAfterKwhPerDay: number
  estimatedSavingPctOfObserved: number
  estimatedMonthlySavingsCny: number
}

export interface FollowupRow {
  deviceId: string
  bucketStart: string
  followupBucketStart: string
  baselineKwh: number
  observedKwh: number
  followupKwh: number
  deductedKwh: number
}

export interface FollowupSimulation {
  planId: string
  parameters: ScenarioParameters
  savedKwh: number
  followupKwh: number
  remainingDeviationPct: number
  rows: FollowupRow[]
  partial: boolean
  missingReadingIds: string[]
}

export interface FollowupTotals {
  simulatedSavedKwh: number
  simulatedFollowupKwh: number
  simulatedRemainingDeviationPct: number
}

export type DataQuality = 'COMPLETE' | 'PARTIAL'

export interface PatrolCheckResult {
  checkId: string
  label: string
  result: 'ATTENTION' | 'PASS'
  eventRef: string | null
  evidenceRefs: string[]
}

export interface PatrolEvent {
  anomalyId: string
  title: string
  buildingId: string
  deviceId: string
  category: string
  priority: string
  observedAt: string
  affectedDeviceIds: string[]
}

export interface PatrolResult {
  checks: PatrolCheckResult[]
  attentionCount: number
  passCount: number
  uniqueEventCount: number
  events: PatrolEvent[]
}

export interface Assessment {
  title: string
  mode: string
  summary: string
  facts: Array<{ factId: string; text: string; evidenceRefs: string[] }>
  unknowns: string[]
  recommendedPlanId: string
  evidenceRefs: string[]
}

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------

export type ScenarioStage =
  | 'READY'
  | 'PATROL_DONE'
  | 'ASSESSED'
  | 'PLAN_SELECTED'
  | 'ORDER_CREATED'
  | 'PROCESSING'
  | 'APPLIED_AWAITING_VERIFICATION'
  | 'VERIFIED'
  | 'CLOSED_NO_ACTION'

export type ScenarioVariantId = 'NORMAL' | 'NO_ACTION' | 'PARTIAL_DATA' | 'LOST_CREATE_RESPONSE'

export type ScenarioEventStatus = 'NOT_SURFACED' | 'OPEN' | 'HANDLING' | 'MONITORING' | 'CLOSED'

export type ScenarioWorkOrderStatus = 'PENDING_EXECUTION' | 'IN_PROGRESS' | 'RESOLVED' | 'CANCELLED'

export type ScenarioReportKind = 'SCENARIO_EVENT_BRIEF'

export interface ScenarioPlanDraft {
  planId: string
  parameters: ScenarioParameters
  /** Null while the observation set is incomplete; no full-cycle estimate exists then. */
  estimate: PlanEstimate | null
}

export interface ScenarioConfirmedPlan {
  planId: string
  targetDeviceIds: string[]
  protectedDeviceIds: string[]
  parameters: ScenarioParameters
  estimate: PlanEstimate
  planRevision: number
}

export interface ScenarioWorkOrder {
  id: string
  workflowId: string
  parkId: string
  buildingId: string
  deviceId: string
  alertId: string
  summary: string
  status: ScenarioWorkOrderStatus
  statusLabel: string
  selectedPlanId: string
  targetDeviceIds: string[]
  protectedDeviceIds: string[]
  parameterSnapshot: ScenarioParameters
  estimateSnapshot: PlanEstimate
  assigneeActorId: string
  createdAt: string
  updatedAt: string
}

export interface ScenarioReportSnapshot {
  reportId: string
  kind: ScenarioReportKind
  format: 'Markdown'
  scenarioId: string
  scenarioVersion: string
  scenarioRunId: string
  dataSource: ScenarioDataSource
  stateRevision: number
  stage: ScenarioStage
  eventStatus: ScenarioEventStatus
  virtualGeneratedAt: string
  observationWindow: ScenarioWindow
  followupWindow: ScenarioWindow
  buildingId: string
  anomalyId: string
  selectedPlanId: string | null
  planSnapshot: ScenarioConfirmedPlan | null
  orderSnapshot: ScenarioWorkOrder | null
  estimateSnapshot: PlanEstimate | null
  followupSnapshot: FollowupSimulation | null
  b2Totals: LedgerTotals
  /** Data-quality facts frozen with the snapshot, so a partial run's brief keeps disclosing its limitation. */
  dataQuality: DataQuality
  observedComplete: boolean
  missingReadingIds: string[]
}

export interface ScenarioReport extends ScenarioReportSnapshot {
  title: string
  markdown: string
}

export interface PendingCommand {
  command: string
  idempotencyKey: string
  status: 'PENDING' | 'LOST_RESPONSE'
}

export interface ScenarioRunState {
  scenarioRunId: string
  stage: ScenarioStage
  stateRevision: number
  virtualNow: string
  selectedBuildingId: string
  selectedPlanId: string | null
  planRevision: number
  planDraft: ScenarioPlanDraft | null
  eventStatus: ScenarioEventStatus
  patrolResult: PatrolResult | null
  assessment: Assessment | null
  confirmedPlan: ScenarioConfirmedPlan | null
  workOrder: ScenarioWorkOrder | null
  followupResult: FollowupSimulation | null
  reports: ScenarioReport[]
  activeReportId: string | null
  pendingCommand: PendingCommand | null
  commandLog: Array<{ action: string; at: string; stateRevision: number }>
}

export interface ScenarioActionTransition {
  action: string
  from: ScenarioStage[]
  to: ScenarioStage
  virtualAdvanceMinutes?: number
  effect: string
}

/** Immutable read model returned by the provider to the shared store/pages. */
export interface ScenarioSnapshot {
  scenarioId: string
  scenarioVersion: string
  dataSource: ScenarioDataSource
  banner: string
  detailModeLabel: string
  variant: ScenarioVariantId
  state: ScenarioRunState
  clock: ScenarioClock
  park: { parkId: string; name: string }
  buildings: ScenarioBuilding[]
  devices: ScenarioDevice[]
  plans: ScenarioPlan[]
  actors: ScenarioActor[]
  protectedDeviceIds: string[]
  optimization: ScenarioOptimization
  reportContract: ScenarioReportContract
  stageLabels: Record<ScenarioStage, string>
  stateRevision: number
  anomaly: {
    anomalyId: string
    title: string
    category: string
    priority: string
    observedAt: string
    deviceId: string
    affectedDeviceIds: string[]
  }
  effectiveLedger: EnergyLedgerRow[]
  dataQuality: DataQuality
  missingReadingIds: string[]
  channelTotals: ChannelTotal[]
  b2: LedgerTotals
  parkTotals: LedgerTotals
  parkFollowupKwh: number | null
  defaultParameters: ScenarioParameters
  /** Plan pinned by the active variant's delta (e.g. NO_ACTION → SCN-PLAN-NONE); null when the operator chooses. */
  pinnedPlanId: string | null
}
