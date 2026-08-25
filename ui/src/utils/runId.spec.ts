import { describe, expect, it } from 'vitest'
import { alertWorkflowRunId } from './runId'

describe('alertWorkflowRunId', () => {
  it('matches the backend UUID v3 mapping (java nameUUIDFromBytes)', () => {
    expect(alertWorkflowRunId('wf-demo-1')).toBe('b88441ba-7022-391f-a93d-ceb4b2e9540a')
    expect(alertWorkflowRunId('ALT-TEMP-001-run-42')).toBe('889e1d78-f861-3690-94c6-e1d2a36419fa')
  })

  it('is deterministic and input-sensitive', () => {
    expect(alertWorkflowRunId('wf-a')).toBe(alertWorkflowRunId('wf-a'))
    expect(alertWorkflowRunId('wf-a')).not.toBe(alertWorkflowRunId('wf-b'))
  })
})
