import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'
import { useExecutionTrace } from './useExecutionTrace'

type Listener = (event: MessageEvent) => void

class FakeEventSource {
  static instances: FakeEventSource[] = []
  url: string
  readyState = 0
  onerror: (() => void) | null = null
  listeners = new Map<string, Set<Listener>>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: Listener): void {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type)!.add(listener)
  }

  close(): void {
    this.readyState = 2
  }

  emit(type: string, data: unknown): void {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: JSON.stringify(data) } as MessageEvent)
    }
  }
}

const baseEvent = {
  eventId: '00000000-0000-0000-0000-000000000001',
  runId: '00000000-0000-0000-0000-00000000aaaa',
  timestamp: '2026-08-24T08:00:00Z',
  scenario: 'ALERT_WORKFLOW',
  actor: 'alert workflow',
  stage: 'ANALYSIS',
  status: 'RUNNING',
  safeSummary: 'step done',
  displayPayload: null,
}

function eventOf(sequence: number, eventType: string, overrides: Record<string, unknown> = {}) {
  const id = String(sequence).padStart(12, '0')
  return {
    ...baseEvent,
    sequence,
    eventType,
    eventId: `00000000-0000-0000-0000-${id}`,
    ...overrides,
  }
}

describe('useExecutionTrace', () => {
  const originalEventSource = globalThis.EventSource

  beforeEach(() => {
    FakeEventSource.instances = []
    ;(globalThis as { EventSource: unknown }).EventSource = FakeEventSource
  })

  afterEach(() => {
    ;(globalThis as { EventSource: unknown }).EventSource = originalEventSource
  })

  it('subscribes to fixed named SSE events and dedupes by eventId', () => {
    const trace = useExecutionTrace()
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))

    expect(trace.events.value).toHaveLength(1)
  })

  it('reorders out-of-sequence events by sequence', () => {
    const trace = useExecutionTrace()
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('NODE_COMPLETED', eventOf(3, 'NODE_COMPLETED'))
    source.emit('NODE_STARTED', eventOf(2, 'NODE_STARTED'))
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))

    expect(trace.events.value.map((event) => event.sequence)).toEqual([1, 2, 3])
  })

  it('reports a sequence gap instead of silently accepting a broken stream', () => {
    const trace = useExecutionTrace()
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))
    source.emit('COMPLETED', eventOf(4, 'COMPLETED'))

    expect(trace.error.value).toContain('序号')
    expect(trace.events.value).toHaveLength(1)
  })

  it('closes the stream at the terminal event and records terminal status', () => {
    const trace = useExecutionTrace()
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))
    source.emit('COMPLETED', eventOf(2, 'COMPLETED'))

    expect(source.readyState).toBe(2)
    expect(trace.status.value).toBe('completed')

    source.emit('TEXT_DELTA', eventOf(3, 'TEXT_DELTA'))
    expect(trace.events.value).toHaveLength(2)
  })

  it('marks interrupted and failed terminal states', () => {
    const trace = useExecutionTrace()
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('INTERRUPTED', eventOf(1, 'INTERRUPTED', { status: 'INTERRUPTED' }))
    expect(trace.status.value).toBe('interrupted')
  })

  it('keeps streaming after a transient error and accepts recovered events', () => {
    const trace = useExecutionTrace()
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))

    // A nonterminal error must not mark the trace terminal: EventSource
    // reconnects automatically and later events must still be consumed.
    source.onerror?.()
    expect(trace.status.value).toBe('streaming')

    source.emit('NODE_COMPLETED', eventOf(2, 'NODE_COMPLETED'))
    expect(trace.events.value.map((event) => event.sequence)).toEqual([1, 2])
    expect(trace.status.value).toBe('streaming')
  })

  it('surfaces parse failures without corrupting existing state', () => {
    const trace = useExecutionTrace()
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))
    source.listeners.get('TEXT_DELTA')!.forEach((listener) =>
      listener({ data: '{not json' } as MessageEvent),
    )

    expect(trace.error.value).not.toBe('')
    expect(trace.events.value).toHaveLength(1)
  })

  it('closes the active stream when its component scope is disposed', () => {
    const scope = effectScope()
    const trace = scope.run(() => useExecutionTrace())!
    trace.subscribe('run-1')
    const source = FakeEventSource.instances.at(-1)!
    source.emit('RUN_STARTED', eventOf(1, 'RUN_STARTED'))

    scope.stop()

    expect(source.readyState).toBe(2)
    expect(trace.status.value).toBe('idle')
    expect(trace.events.value).toEqual([])
  })
})
