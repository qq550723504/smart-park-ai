import { describe, expect, it } from 'vitest'
import { B2_SCENARIO_FIXTURE } from './fixture'
import { MockScenarioProvider } from './provider'
import { createB2ScenarioStore, type ScenarioStorage } from './store'

class FakeStorage implements ScenarioStorage {
  private readonly map = new Map<string, string>()
  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
  removeItem(key: string): void {
    this.map.delete(key)
  }
}

const fixture = B2_SCENARIO_FIXTURE

function storeWith(storage: ScenarioStorage | null = null, latencyMs = 0) {
  return createB2ScenarioStore({ storage, latencyMs, provider: new MockScenarioProvider({ fixture }) })
}

describe('B2 shared store', () => {
  it('does not re-run a command on refresh and keeps the same run', async () => {
    const store = storeWith()
    await store.startPatrol()
    const revision = store.snapshot.value.state.stateRevision
    const runId = store.snapshot.value.state.scenarioRunId
    store.refresh()
    expect(store.snapshot.value.state.stateRevision).toBe(revision)
    expect(store.snapshot.value.state.scenarioRunId).toBe(runId)
  })

  it('ignores a second command while one is in flight', async () => {
    const store = storeWith(null, 10)
    const first = store.startPatrol()
    const second = store.runAssessment()
    expect(await second).toBe(false)
    expect(await first).toBe(true)
    expect(store.snapshot.value.state.stage).toBe('PATROL_DONE')
  })

  it('restores the same run after a page reload', async () => {
    const storage = new FakeStorage()
    const first = storeWith(storage)
    first.enter()
    await first.startPatrol()
    await first.runAssessment()
    await first.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    await first.confirmAndCreateOrder()

    const reloaded = storeWith(storage)
    expect(reloaded.active.value).toBe(true)
    expect(reloaded.snapshot.value.state.scenarioRunId).toBe('SCN-B2-NIGHT-ENERGY-001-RUN-001')
    expect(reloaded.snapshot.value.state.stage).toBe('ORDER_CREATED')
    expect(reloaded.snapshot.value.state.workOrder?.id).toBe('SCN-WO-B2-001-001')
  })

  it('freezes the combined plan in the shared work order', async () => {
    const store = storeWith()
    await store.startPatrol()
    await store.runAssessment()
    await store.selectPlan('SCN-PLAN-HVAC-LIGHT')
    await store.confirmAndCreateOrder()
    expect(store.workOrder.value?.estimateSnapshot).toMatchObject({ estimatedSavedKwhPerDay: 90, estimatedMonthlySavingsCny: 1980 })
  })

  it('surfaces the lost-response fault and keeps the same order on retry', async () => {
    const store = createB2ScenarioStore({
      storage: null,
      provider: new MockScenarioProvider({ fixture, faults: { lostCreateResponse: true } }),
    })
    await store.startPatrol()
    await store.runAssessment()
    await store.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    expect(await store.confirmAndCreateOrder()).toBe(false)
    expect(store.error.value).toContain('响应丢失')
    expect(store.pending.value?.status).toBe('LOST_RESPONSE')
    expect(store.canReset.value).toBe(false)
    expect(await store.confirmAndCreateOrder()).toBe(true)
    expect(store.pending.value).toBeNull()
    expect(store.workOrder.value?.id).toBe('SCN-WO-B2-001-001')
  })

  it('bumps the run sequence and clears pending state on reset', async () => {
    const store = storeWith()
    await store.startPatrol()
    await store.runAssessment()
    await store.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    await store.confirmAndCreateOrder()
    const generation = store.generation.value
    expect(await store.reset()).toBe(true)
    expect(store.generation.value).toBe(generation + 1)
    expect(store.snapshot.value.state.scenarioRunId).toBe('SCN-B2-NIGHT-ENERGY-001-RUN-002')
    expect(store.snapshot.value.state.stage).toBe('READY')
  })

  it('blocks reset while a command carries an unconfirmed identity', async () => {
    const store = createB2ScenarioStore({
      storage: null,
      provider: new MockScenarioProvider({ fixture, faults: { lostCreateResponse: true } }),
    })
    await store.startPatrol()
    await store.runAssessment()
    await store.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    await store.confirmAndCreateOrder()
    expect(store.canReset.value).toBe(false)
    expect(await store.reset()).toBe(false)
    expect(store.snapshot.value.state.scenarioRunId).toBe('SCN-B2-NIGHT-ENERGY-001-RUN-001')
  })

  it('keeps an in-flight command from mutating a reset run', async () => {
    const store = storeWith(null, 20)
    const inFlight = store.startPatrol()
    // A reset is refused while busy, so the pending command keeps its identity.
    expect(await store.reset()).toBe(false)
    expect(await inFlight).toBe(true)
    expect(store.snapshot.value.state.stage).toBe('PATROL_DONE')
  })

  it('exposes report snapshots through the shared state', async () => {
    const store = storeWith()
    await store.startPatrol()
    await store.runAssessment()
    await store.generateReport()
    expect(store.reports.value).toHaveLength(1)
    const reportId = store.reports.value[0]!.reportId
    expect(store.activeReport.value?.reportId).toBe(reportId)
    await store.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    await store.generateReport()
    await store.openReport(reportId)
    expect(store.activeReport.value?.reportId).toBe(reportId)
    expect(store.reports.value).toHaveLength(2)
  })

  it('drives the full recommended story to a verified result', async () => {
    const store = storeWith()
    await store.startPatrol()
    await store.runAssessment()
    await store.selectPlan('SCN-PLAN-PUBLIC-HVAC')
    await store.confirmAndCreateOrder()
    await store.takeOrder()
    await store.applySimulatedPlan()
    expect(store.snapshot.value.state.followupResult).toBeNull()
    await store.verifyNextCycle()
    expect(store.snapshot.value.state.stage).toBe('VERIFIED')
    expect(store.snapshot.value.state.followupResult).toMatchObject({ savedKwh: 72, followupKwh: 1228, remainingDeviationPct: 22.8 })
    expect(store.snapshot.value.state.eventStatus).toBe('MONITORING')
    expect(store.snapshot.value.parkFollowupKwh).toBe(2828)
  })

  it('tracks the scenario mode flag independently of the run', async () => {
    const storage = new FakeStorage()
    const store = storeWith(storage)
    expect(store.active.value).toBe(false)
    store.enter()
    expect(store.active.value).toBe(true)
    await store.startPatrol()
    store.exit()
    const reloaded = storeWith(storage)
    expect(reloaded.active.value).toBe(false)
    expect(reloaded.snapshot.value.state.stage).toBe('PATROL_DONE')
  })
})
