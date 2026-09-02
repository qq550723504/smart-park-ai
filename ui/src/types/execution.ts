/**
 * TypeScript mirror of the Java execution event contract
 * (com.example.smartpark.execution.model). Keep both sides in sync: the
 * payload union is closed and discriminated by `payloadType`.
 */

export type ExecutionScenario = 'VOICE' | 'EXPERT_COLLABORATION' | 'OPERATIONS_ANALYSIS' | 'ALERT_WORKFLOW' | 'CUSTOMER_SERVICE'

export type ExecutionEventType =
  | 'RUN_STARTED'
  | 'TEXT_DELTA'
  | 'TEXT_COMPLETED'
  | 'TOOL_CALL_STARTED'
  | 'TOOL_CALL_COMPLETED'
  | 'TOOL_CALL_FAILED'
  | 'EXPERT_HANDOFF'
  | 'NODE_STARTED'
  | 'NODE_COMPLETED'
  | 'PAUSED'
  | 'RESUMED'
  | 'SQL_GENERATED'
  | 'SQL_VALIDATED'
  | 'SQL_REJECTED'
  | 'QUERY_EXECUTED'
  | 'CHART_SPECIFIED'
  | 'AUDIO_STARTED'
  | 'AUDIO_CHUNK'
  | 'AUDIO_COMPLETED'
  | 'INTERRUPTED'
  | 'FAILED'
  | 'COMPLETED'

export type ExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED' | 'NEEDS_CLARIFICATION'

const TERMINAL_EVENT_TYPES: readonly ExecutionEventType[] = ['COMPLETED', 'FAILED', 'INTERRUPTED']

export function isTerminalEvent(event: ExecutionEvent): boolean {
  return TERMINAL_EVENT_TYPES.includes(event.eventType)
}

/** Closed display payload union mirroring the sealed Java DisplayPayload. */
export type DisplayPayload =
  | { payloadType: 'TEXT'; text: string; partial: boolean }
  | {
      payloadType: 'TOOL_CALL'
      toolName: string
      safeArguments: Record<string, string>
      resultSummary: string
    }
  | { payloadType: 'EXPERT_HANDOFF'; domain: string; direction: string; findingStatus: string }
  | { payloadType: 'SQL'; safeSql: string; parameterNames: string[]; validationStatus: string }
  | {
      payloadType: 'CHART'
      type: 'LINE' | 'BAR' | 'TABLE' | 'KPI' | 'STACKED_BAR' | 'HEATMAP' | 'CALENDAR_HEATMAP' | 'SCATTER' | 'GAUGE' | 'MAP'
      title: string
      xField: string
      yFields: string[]
      seriesField: string
      unit: string
      orientation?: 'VERTICAL' | 'HORIZONTAL'
      stacked?: boolean
      targetValue?: number | null
      coordinateXField?: string
      coordinateYField?: string
    }
  | {
      payloadType: 'TIME_RANGE'
      status: 'NONE' | 'PARSED' | 'EMPTY'
      fromInclusive: string | null
      toExclusive: string | null
      source: 'EXPLICIT_USER_RANGE' | 'DEFAULT_METRIC_LOOKBACK'
      explanation: string
      empty: boolean
    }
  | { payloadType: 'AUDIO'; state: string; durationMs: number | null }
  | {
      payloadType: 'ERROR'
      stage: string
      errorCode: string
      retryable: boolean
      safeMessage: string
    }

export interface ExecutionEvent {
  eventId: string
  runId: string
  sequence: number
  timestamp: string
  scenario: ExecutionScenario
  actor: string
  stage: string
  eventType: ExecutionEventType
  status: ExecutionStatus
  safeSummary: string
  displayPayload: DisplayPayload | null
}

export interface ExecutionRunSummary {
  status: string
  totalEvents: number
}
