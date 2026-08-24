import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import type { ExecutionEvent } from '../types/execution'
import { isTerminalEvent } from '../types/execution'
import { subscribeToExecutionEvents } from '../services/executionApi'
import type { ExecutionStream } from '../services/executionApi'

export type TraceStatus = 'idle' | 'streaming' | 'completed' | 'failed' | 'interrupted'

export interface ExecutionTrace {
  events: Ref<ExecutionEvent[]>
  status: Ref<TraceStatus>
  error: Ref<string>
  lastSequence: Ref<number>
  isTerminal: Ref<boolean>
  subscribe(runId: string): void
  reset(): void
}

/**
 * Consumes the unified execution trace SSE stream: dedupes by eventId,
 * buffers out-of-order deliveries until they form a contiguous run, reports a
 * sequence gap at the terminal event instead of silently accepting a broken
 * stream, and closes exactly at the terminal event. The UI never fabricates
 * process events itself.
 */
export function useExecutionTrace(): ExecutionTrace {
  const events = ref<ExecutionEvent[]>([])
  const status = ref<TraceStatus>('idle')
  const error = ref('')
  const lastSequence = ref(0)

  const seen = new Map<number, ExecutionEvent>()
  let stream: ExecutionStream | null = null

  const isTerminal = computed(() => status.value !== 'idle' && status.value !== 'streaming')

  function publishContiguousPrefix(): void {
    const ordered: ExecutionEvent[] = []
    let next = lastSequence.value + 1
    while (seen.has(next)) {
      const event = seen.get(next)!
      seen.delete(next)
      ordered.push(event)
      next += 1
    }
    if (ordered.length === 0) return

    events.value = [...events.value, ...ordered].sort((left, right) => left.sequence - right.sequence)
    lastSequence.value = ordered[ordered.length - 1].sequence
    for (const event of ordered) {
      if (isTerminalEvent(event)) {
        status.value =
          event.eventType === 'COMPLETED' ? 'completed' : event.eventType === 'FAILED' ? 'failed' : 'interrupted'
      }
    }
  }

  function handleEvent(event: ExecutionEvent): void {
    if (isTerminal.value) return
    if (events.value.some((item) => item.eventId === event.eventId)) return
    if (seen.has(event.sequence) || event.sequence < 1) return

    seen.set(event.sequence, event)
    publishContiguousPrefix()

    // A terminal event still leaves a hole: the stream is broken — surface it.
    if (isTerminalEvent(event) && !isTerminal.value) {
      error.value = `执行事件序号缺口：收到 ${event.sequence}，缺少 ${lastSequence.value + 1} 及其后事件`
      status.value = 'failed'
      stream?.close()
    }
  }

  function subscribe(runId: string): void {
    reset()
    status.value = 'streaming'
    stream = subscribeToExecutionEvents(runId, {
      onEvent: handleEvent,
      onError: (message) => {
        error.value = message
        if (!isTerminal.value) status.value = 'failed'
      },
    })
  }

  function reset(): void {
    stream?.close()
    stream = null
    seen.clear()
    events.value = []
    lastSequence.value = 0
    error.value = ''
    status.value = 'idle'
  }

  return { events, status, error, lastSequence, isTerminal, subscribe, reset }
}
