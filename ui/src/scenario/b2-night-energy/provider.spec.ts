import { describe, expect, it } from 'vitest'
import { B2_SCENARIO_FIXTURE } from './fixture'
import { MockScenarioProvider, ScenarioFaultError, ScenarioStateError, defaultPlanTotals } from './provider'

const fixture = B2_SCENARIO_FIXTURE

function newProvider() {
  return new MockScenarioProvider({ fixture, runSequenceNumber: 1 })
}

function toOrdered(provider: MockScenarioProvider) {
  provider.startPatrol()
  provider.runAssessment()
  return provider
}

describe('B2 provider initial state', () => {
  it('starts READY at the frozen virtual clock with no event or order', () => {
    const snapshot = newProvider().read()
    expect(snapshot.state.scenarioRunId).toBe('SCN-B2-NIGHT-ENERGY-001-RUN-001')
    expect(snapshot.state.stage).toBe('READY')
    expect(snapshot.state.stateRevision).toBe(0)
    expect(snapshot.state.virtualNow).toBe('2026-09-11T09:00:00+08:00')
    expect(snapshot.state.eventStatus).toBe('NOT_SURFACED')
    expect(snapshot.state.workOrder).toBeNull()
    expect(snapshot.state.reports).toHaveLength(0)
    expect(snapshot.dataQuality).toBe('COMPLETE')
    expect(snapshot.b2).toMatchObject({ baselineKwh: 1000, observedKwh: 1300, deviationPct: 30 })
    expect(snapshot.parkTotals).toMatchObject({ baselineKwh: 2600, observedKwh: 2900, deviationPct: 11.54 })
  })

  it('rejects out-of-order commands', () => {
    const provider = newProvider()
    expect(() => provider.runAssessment()).toThrow(ScenarioStateError)
    expect(() => provider.confirmAndCreateOrder()).toThrow(ScenarioStateError)
    expect(() => provider.generateReport()).toThrow(ScenarioStateError)
  })
})

describe('B2 provider patrol and assessment', () => {
  it('surfaces one event from 4 checks without creating a work order', () => {
    const provider = newProvider()
    const afterPatrol = provider.startPatrol()
    expect(afterPatrol.state.stage).toBe('PATROL_DONE')
    expect(afterPatrol.state.stateRevision).toBe(1)
    expect(afterPatrol.state.eventStatus).toBe('OPEN')
    expect(afterPatrol.state.patrolResult?.uniqueEventCount).toBe(1)
    expect(afterPatrol.state.workOrder).toBeNull()
    expect(Date.parse(afterPatrol.state.virtualNow)).toBe(Date.parse('2026-09-11T09:00:00+08:00') + 60_000)
    // The frozen clock keeps its +08:00 offset: consumers slice the string, so a
    // UTC conversion would render 09:01 as 01:01.
    expect(afterPatrol.state.virtualNow).toBe('2026-09-11T09:01:00+08:00')
    expect(afterPatrol.state.commandLog.every((entry) => entry.at.endsWith('+08:00'))).toBe(true)
  })

  it('builds the assessment from the same shared ledger', () => {
    const provider = toOrdered(newProvider())
    const snapshot = provider.read()
    expect(snapshot.state.stage).toBe('ASSESSED')
    expect(snapshot.state.assessment?.recommendedPlanId).toBe('SCN-PLAN-PUBLIC-HVAC')
    expect(snapshot.state.assessment?.summary).toContain('1300')
  })
})

describe('B2 provider plan selection and parameters', () => {
  it('recomputes the estimate when the plan or duration changes', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(provider.read().state.planDraft?.estimate?.estimatedSavedKwhPerDay).toBe(80)
    provider.updateParameters({ savedHours: 3, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 })
    expect(provider.read().state.planDraft?.estimate).toMatchObject({ estimatedSavedKwhPerDay: 60, estimatedAfterKwhPerDay: 1240 })
    provider.selectPlan('SCN-PLAN-HVAC-LIGHT')
    expect(provider.read().state.planDraft?.estimate?.estimatedMonthlySavingsCny).toBe(1980)
    expect(provider.read().state.planRevision).toBe(3)
  })

  it('rejects invalid parameters without bumping the revision', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    const revision = provider.read().state.planRevision
    expect(() => provider.updateParameters({ savedHours: 9, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 })).toThrow(ScenarioStateError)
    expect(provider.read().state.planRevision).toBe(revision)
  })
})

describe('B2 provider order creation and idempotency', () => {
  it('freezes the combined plan as 90/1980, not the recommended 80/1760', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-HVAC-LIGHT')
    const snapshot = provider.confirmAndCreateOrder()
    expect(snapshot.state.stage).toBe('ORDER_CREATED')
    expect(snapshot.state.eventStatus).toBe('HANDLING')
    expect(snapshot.state.workOrder?.id).toBe('SCN-WO-B2-001-001')
    expect(snapshot.state.workOrder?.status).toBe('PENDING_EXECUTION')
    expect(snapshot.state.confirmedPlan?.estimate).toMatchObject({ estimatedSavedKwhPerDay: 90, estimatedMonthlySavingsCny: 1980 })
  })

  it('returns the same order for a repeated confirm (no duplicate order)', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    const first = provider.confirmAndCreateOrder().state.workOrder
    const second = provider.confirmAndCreateOrder().state.workOrder
    expect(second?.id).toBe(first?.id)
    expect(provider.read().state.stateRevision).toBe(4)
  })

  it('does not create an order for the no-action plan', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-NONE')
    expect(() => provider.confirmAndCreateOrder()).toThrow(ScenarioStateError)
  })

  it('keeps no-action as monitoring with no order', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-NONE')
    const snapshot = provider.keepObserving()
    expect(snapshot.state.stage).toBe('CLOSED_NO_ACTION')
    expect(snapshot.state.eventStatus).toBe('MONITORING')
    expect(snapshot.state.workOrder).toBeNull()
  })

  it('records the deliberate no-action decision in the generated brief', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-NONE')
    provider.keepObserving()
    const report = provider.generateReport().state.reports[0]
    expect(report.selectedPlanId).toBe('SCN-PLAN-NONE')
    expect(report.planSnapshot).toBeNull()
    expect(report.markdown).toContain('客户明确选择“保持现状 / 保持观察”')
    expect(report.markdown).toContain('本次选择保持观察，未创建任务。')
    expect(report.markdown).not.toContain('尚未确认可执行方案。')
  })

  it('keeps no-action wording pending until the decision is finalized', () => {
    const provider = toOrdered(newProvider())
    // SCN-PLAN-NONE is only selected here; the run is still PLAN_SELECTED and
    // the user could switch away, so the brief must not claim a final decision.
    provider.selectPlan('SCN-PLAN-NONE')
    const report = provider.generateReport().state.reports[0]
    expect(report.stage).toBe('PLAN_SELECTED')
    expect(report.markdown).toContain('当前选择“保持现状 / 保持观察”，尚未确认')
    expect(report.markdown).not.toContain('客户明确选择')
  })
})

describe('B2 provider lost create response', () => {
  it('commits once, throws once, then returns the same order on retry', () => {
    const provider = new MockScenarioProvider({ fixture, runSequenceNumber: 1, faults: { lostCreateResponse: true } })
    toOrdered(provider)
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(() => provider.confirmAndCreateOrder()).toThrow(ScenarioFaultError)
    const afterFault = provider.read()
    expect(afterFault.state.stage).toBe('ORDER_CREATED')
    expect(afterFault.state.workOrder?.id).toBe('SCN-WO-B2-001-001')
    expect(afterFault.state.pendingCommand).toMatchObject({ status: 'LOST_RESPONSE', command: 'CONFIRM_AND_CREATE_ORDER' })
    const retried = provider.confirmAndCreateOrder()
    expect(retried.state.workOrder?.id).toBe('SCN-WO-B2-001-001')
    expect(retried.state.pendingCommand).toBeNull()
    expect(retried.state.stateRevision).toBe(afterFault.state.stateRevision)
  })

  it('resolves the committed identity from a reloaded run and clears the pending command', () => {
    const provider = new MockScenarioProvider({ fixture, runSequenceNumber: 1, faults: { lostCreateResponse: true } })
    toOrdered(provider)
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(() => provider.confirmAndCreateOrder()).toThrow(ScenarioFaultError)
    const persisted = provider.exportState()

    // A page reload builds a provider with an empty in-memory idempotency cache.
    const restored = new MockScenarioProvider({ fixture })
    expect(restored.restoreState(persisted)).toBe(true)
    expect(restored.read().state.pendingCommand?.status).toBe('LOST_RESPONSE')

    const retried = restored.confirmAndCreateOrder()
    expect(retried.state.workOrder?.id).toBe('SCN-WO-B2-001-001')
    expect(retried.state.pendingCommand).toBeNull()
    // The recovered run is no longer stuck: reset is allowed again.
    expect(restored.reset().state.scenarioRunId).toBe('SCN-B2-NIGHT-ENERGY-001-RUN-002')
  })

  it('keeps the pending identity through unrelated commits and blocks other mutations', () => {
    const provider = new MockScenarioProvider({ fixture, runSequenceNumber: 1, faults: { lostCreateResponse: true } })
    toOrdered(provider)
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(() => provider.confirmAndCreateOrder()).toThrow(ScenarioFaultError)

    // Generating a report at ORDER_CREATED is allowed, but it must not drop the
    // unresolved receipt and re-enable reset without a same-identity retry.
    provider.generateReport()
    expect(provider.read().state.pendingCommand?.status).toBe('LOST_RESPONSE')
    expect(provider.read().state.reports).toHaveLength(1)
    expect(() => provider.takeOrder()).toThrow(ScenarioStateError)
    expect(() => provider.reset()).toThrow(ScenarioStateError)

    const retried = provider.confirmAndCreateOrder()
    expect(retried.state.pendingCommand).toBeNull()
    expect(retried.state.workOrder?.id).toBe('SCN-WO-B2-001-001')
  })

  it('does not re-arm the lost-response fault for a run that already consumed it', () => {
    const provider = new MockScenarioProvider({ fixture, runSequenceNumber: 1, variant: 'LOST_CREATE_RESPONSE' })
    toOrdered(provider)
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(() => provider.confirmAndCreateOrder()).toThrow(ScenarioFaultError)

    const restored = new MockScenarioProvider({ fixture })
    restored.restoreState(provider.exportState())
    // Retry succeeds; it must not fire a second lost-response fault.
    expect(restored.confirmAndCreateOrder().state.pendingCommand).toBeNull()
  })

  it('reuses the same report snapshot after a reload instead of minting a second one', () => {
    const provider = toOrdered(newProvider())
    provider.generateReport()
    const persisted = provider.exportState()

    const restored = new MockScenarioProvider({ fixture })
    expect(restored.restoreState(persisted)).toBe(true)
    const revision = restored.read().state.stateRevision
    const afterRegenerate = restored.generateReport()
    expect(afterRegenerate.state.reports).toHaveLength(1)
    expect(afterRegenerate.state.reports[0].reportId).toBe('SCN-RPT-B2-001-01')
    expect(afterRegenerate.state.stateRevision).toBe(revision)
  })
})

describe('B2 provider processing and verification', () => {
  function runToProcessing() {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    provider.confirmAndCreateOrder()
    provider.takeOrder()
    provider.applySimulatedPlan()
    return provider
  }

  it('moves through take -> apply without exposing future readings', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    provider.confirmAndCreateOrder()
    const processing = provider.takeOrder()
    expect(processing.state.stage).toBe('PROCESSING')
    expect(processing.state.workOrder?.status).toBe('IN_PROGRESS')
    const applied = provider.applySimulatedPlan()
    expect(applied.state.stage).toBe('APPLIED_AWAITING_VERIFICATION')
    expect(applied.state.followupResult).toBeNull()
  })

  it('reveals 72/1228/22.8 only after explicit verification and keeps the event monitoring', () => {
    const provider = runToProcessing()
    const verified = provider.verifyNextCycle()
    expect(verified.state.stage).toBe('VERIFIED')
    expect(verified.state.virtualNow).toBe('2026-09-12T09:00:00+08:00')
    expect(verified.state.followupResult).toMatchObject({ savedKwh: 72, followupKwh: 1228, remainingDeviationPct: 22.8 })
    expect(verified.state.workOrder?.status).toBe('RESOLVED')
    expect(verified.state.eventStatus).toBe('MONITORING')
    expect(verified.parkFollowupKwh).toBe(2828)
  })

  it('cannot verify before applying', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    provider.confirmAndCreateOrder()
    expect(() => provider.verifyNextCycle()).toThrow(ScenarioStateError)
  })
})

describe('B2 provider reports', () => {
  it('freezes each report at the generating revision and reuses it idempotently', () => {
    const provider = toOrdered(newProvider())
    const r1 = provider.generateReport()
    expect(r1.state.reports).toHaveLength(1)
    expect(r1.state.reports[0]?.stateRevision).toBe(r1.state.stateRevision)
    const r1Again = provider.generateReport()
    expect(r1Again.state.stateRevision).toBe(r1.state.stateRevision)
    expect(r1Again.state.reports).toHaveLength(1)
    expect(r1Again.state.reports[0]?.reportId).toBe(r1.state.reports[0]?.reportId)
  })

  it('creates a second, independent snapshot after the state advances', () => {
    const provider = toOrdered(newProvider())
    const r1 = provider.generateReport().state.reports[0]!
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    provider.confirmAndCreateOrder()
    const snapshot = provider.generateReport()
    expect(snapshot.state.reports).toHaveLength(2)
    const r2 = snapshot.state.reports[1]!
    expect(r2.reportId).not.toBe(r1.reportId)
    expect(r2.orderSnapshot?.id).toBe('SCN-WO-B2-001-001')
    // R1 must remain frozen without the later order.
    expect(snapshot.state.reports[0]?.orderSnapshot).toBeNull()
  })

  it('only generates reports after the assessment stage', () => {
    const provider = newProvider()
    expect(() => provider.generateReport()).toThrow(ScenarioStateError)
    provider.startPatrol()
    provider.runAssessment()
    expect(provider.generateReport().state.reports).toHaveLength(1)
  })

  it('freezes and discloses partial data quality in the brief', () => {
    const provider = new MockScenarioProvider({ fixture, runSequenceNumber: 1, variant: 'PARTIAL_DATA' })
    toOrdered(provider)
    const report = provider.generateReport().state.reports[0]!
    // The limitation is frozen with the snapshot, not re-read from live state.
    expect(report.dataQuality).toBe('PARTIAL')
    expect(report.observedComplete).toBe(false)
    expect(report.missingReadingIds).toEqual(['SCN-B2-HVAC-PUBLIC:15'])
    expect(report.markdown).toContain('数据质量：PARTIAL（关键小时观测缺失）')
    expect(report.markdown).toContain('缺失观测：SCN-B2-HVAC-PUBLIC:15（不补零）')
    expect(report.markdown).toContain('暂停完整节能估算与提交')
    // Must not read like a complete NORMAL brief at the same stage.
    expect(report.markdown).not.toContain('尚未形成预计效果。')
    expect(report.markdown).not.toContain('COMPLETE（完整观察窗口）')

    const normal = toOrdered(newProvider()).generateReport().state.reports[0]!
    expect(normal.dataQuality).toBe('COMPLETE')
    expect(normal.observedComplete).toBe(true)
    expect(normal.missingReadingIds).toEqual([])
    expect(normal.markdown).toContain('数据质量：COMPLETE（完整观察窗口）')
    expect(normal.markdown).toContain('尚未形成预计效果。')
  })
})

describe('B2 provider variants and reset', () => {
  it('blocks confirmation under PARTIAL_DATA', () => {
    const provider = new MockScenarioProvider({ fixture, runSequenceNumber: 1, variant: 'PARTIAL_DATA' })
    toOrdered(provider)
    const snapshot = provider.read()
    expect(snapshot.dataQuality).toBe('PARTIAL')
    expect(snapshot.missingReadingIds).toEqual(['SCN-B2-HVAC-PUBLIC:15'])
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(provider.read().state.planDraft?.estimate).toBeNull()
    expect(() => provider.confirmAndCreateOrder()).toThrow(ScenarioStateError)
  })

  it('bumps the run sequence and clears state on reset', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    provider.confirmAndCreateOrder()
    const reset = provider.reset()
    expect(reset.state.scenarioRunId).toBe('SCN-B2-NIGHT-ENERGY-001-RUN-002')
    expect(reset.state.stage).toBe('READY')
    expect(reset.state.stateRevision).toBe(0)
    expect(reset.state.workOrder).toBeNull()
  })

  it('allows a variant switch only before the run starts', () => {
    const provider = newProvider()
    provider.setVariant('PARTIAL_DATA')
    expect(provider.read().dataQuality).toBe('PARTIAL')
    provider.startPatrol()
    expect(() => provider.setVariant('NORMAL')).toThrow(ScenarioStateError)
    expect(provider.read().variant).toBe('PARTIAL_DATA')
  })

  it('pins the no-action variant to SCN-PLAN-NONE and refuses executable plans', () => {
    const provider = new MockScenarioProvider({ fixture, runSequenceNumber: 1, variant: 'NO_ACTION' })
    expect(provider.read().pinnedPlanId).toBe('SCN-PLAN-NONE')
    toOrdered(provider)
    expect(() => provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')).toThrow(ScenarioStateError)
    expect(provider.read().state.selectedPlanId).toBeNull()
    provider.selectPlan('SCN-PLAN-NONE')
    expect(provider.read().state.selectedPlanId).toBe('SCN-PLAN-NONE')
  })

  it('leaves the plan choice open for variants without a plan delta', () => {
    const provider = newProvider()
    expect(provider.read().pinnedPlanId).toBeNull()
    toOrdered(provider)
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(provider.read().state.selectedPlanId).toBe('SCN-PLAN-PUBLIC-HVAC')
  })

  it('round-trips the shared run through export/restore', () => {
    const provider = toOrdered(newProvider())
    provider.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    provider.confirmAndCreateOrder()
    const persisted = provider.exportState()
    const restored = new MockScenarioProvider({ fixture })
    expect(restored.restoreState(persisted)).toBe(true)
    expect(restored.read().state.workOrder?.id).toBe('SCN-WO-B2-001-001')
    expect(restored.read().state.stage).toBe('ORDER_CREATED')
  })

  it('rejects a persisted run from another scenario identity', () => {
    const provider = newProvider()
    const persisted = provider.exportState()
    expect(provider.restoreState({ ...persisted, scenarioVersion: '9.9.9' })).toBe(false)
  })
})

describe('B2 default plan totals match expectedAssertions', () => {
  it('agrees with the fixture contract for every plan', () => {
    const totals = defaultPlanTotals(fixture)
    for (const [planId, expected] of Object.entries(fixture.expectedAssertions.defaultPlanResults)) {
      expect(totals[planId]?.estimate).toMatchObject({
        estimatedSavedKwhPerDay: expected.estimatedSavedKwhPerDay,
        estimatedAfterKwhPerDay: expected.estimatedAfterKwhPerDay,
        estimatedMonthlySavingsCny: expected.estimatedMonthlySavingsCny,
      })
      expect(totals[planId]?.followup).toMatchObject({
        savedKwh: expected.simulatedSavedKwh,
        followupKwh: expected.simulatedFollowupKwh,
        remainingDeviationPct: expected.simulatedRemainingDeviationPct,
      })
    }
  })
})
