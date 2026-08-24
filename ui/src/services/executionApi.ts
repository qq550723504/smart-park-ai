import type { ExecutionEvent } from '../types/execution'
import { isTerminalEvent } from '../types/execution'

/** Fixed named SSE event types published by GET /api/executions/{runId}/events. */
export const EXECUTION_EVENT_TYPES = [
  'RUN_STARTED',
  'TEXT_DELTA',
  'TEXT_COMPLETED',
  'TOOL_CALL_STARTED',
  'TOOL_CALL_COMPLETED',
  'TOOL_CALL_FAILED',
  'EXPERT_HANDOFF',
  'NODE_STARTED',
  'NODE_COMPLETED',
  'PAUSED',
  'RESUMED',
  'SQL_GENERATED',
  'SQL_VALIDATED',
  'SQL_REJECTED',
  'QUERY_EXECUTED',
  'CHART_SPECIFIED',
  'AUDIO_STARTED',
  'AUDIO_CHUNK',
  'AUDIO_COMPLETED',
  'INTERRUPTED',
  'FAILED',
  'COMPLETED',
] as const

export interface ExecutionStreamHandlers {
  onEvent(event: ExecutionEvent): void
  onError(message: string): void
}

export interface ExecutionStream extends DisposableLike {
  close(): void
}

interface DisposableLike {
  [Symbol.dispose]?: () => void
}

export function subscribeToExecutionEvents(runId: string, handlers: ExecutionStreamHandlers): ExecutionStream {
  const source = new EventSource(`/api/executions/${encodeURIComponent(runId)}/events`)
  let closedByTerminal = false

  function close(): void {
    source.close()
  }

  for (const type of EXECUTION_EVENT_TYPES) {
    source.addEventListener(type, (raw: MessageEvent) => {
      if (closedByTerminal) return
      try {
        const parsed = JSON.parse(raw.data as string) as ExecutionEvent
        handlers.onEvent(parsed)
        if (isTerminalEvent(parsed)) {
          closedByTerminal = true
          close()
        }
      } catch {
        handlers.onError('执行事件解析失败，已忽略该事件')
      }
    })
  }

  source.onerror = () => {
    if (!closedByTerminal) {
      handlers.onError('执行事件连接中断')
    }
  }

  return { close }
}
