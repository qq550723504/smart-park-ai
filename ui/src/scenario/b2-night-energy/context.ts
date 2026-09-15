import type { CustomerAnalysisContext } from '../../types/customer'
import type { ScenarioSnapshot } from '../../types/scenarioEnergy'

/**
 * Maps the shared B2 run onto the existing customer context shape so the
 * scenario can reuse the shell, hero and assistant entry points without
 * pretending to be an OPERATIONS_ANALYTICS response.
 */
export function scenarioAnalysisContext(snapshot: ScenarioSnapshot): CustomerAnalysisContext {
  const window = snapshot.clock.observationWindow
  return {
    buildingId: 'B2',
    buildingName: '研发大厦',
    anomalyId: snapshot.anomaly.anomalyId,
    title: snapshot.anomaly.title,
    priority: '中',
    summary: null,
    overviewDomainStatus: {
      alerts: 'OK',
      devices: 'OK',
      energy: snapshot.dataQuality === 'PARTIAL' ? 'PARTIAL' : 'OK',
    },
    anomalyWindow: { from: window.from, to: window.to, timezone: snapshot.clock.timezone },
    energyWindow: { from: window.from, to: window.to, timezone: snapshot.clock.timezone, granularity: 'HOUR' },
    source: 'SCENARIO_FIXTURE',
  }
}
