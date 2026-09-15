import rawScenario from './scenario-data.json'
import type { ScenarioFixture, ScenarioRunState } from '../../types/scenarioEnergy'

/**
 * The single source of truth for the B2 simulated scenario. The JSON file is
 * byte-identical to the handoff package's `04-scenario-data.json`; pages and
 * providers must never hard-code these numbers again.
 */
export const B2_SCENARIO_FIXTURE = rawScenario as unknown as ScenarioFixture

export function runSequence(scenarioRunId: string): string {
  const match = /RUN-(\d+)$/.exec(scenarioRunId)
  return match ? match[1]! : '001'
}

export function scenarioRunIdFor(sequence: number): string {
  return `${B2_SCENARIO_FIXTURE.meta.scenarioId}-RUN-${String(sequence).padStart(3, '0')}`
}

export function createInitialState(scenarioRunId: string): ScenarioRunState {
  const template = B2_SCENARIO_FIXTURE.runtimeContract.initialState
  return {
    ...structuredClone(template),
    scenarioRunId,
    commandLog: [],
  }
}

export function fixtureDevice(fixture: ScenarioFixture, deviceId: string) {
  return fixture.devices.find((device) => device.deviceId === deviceId) ?? null
}
