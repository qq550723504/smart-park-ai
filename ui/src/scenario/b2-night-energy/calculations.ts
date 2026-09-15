import type {
  ChannelTotal,
  DataQuality,
  EnergyLedgerRow,
  FollowupRow,
  FollowupSimulation,
  LedgerTotals,
  PlanEstimate,
  ScenarioBuilding,
  ScenarioFixture,
  ScenarioParameters,
  ScenarioPlan,
  ScenarioVariantId,
  PatrolCheckResult,
  PatrolEvent,
  PatrolResult,
  Assessment,
} from '../../types/scenarioEnergy'

/**
 * Pure derived calculations for SCN-B2-NIGHT-ENERGY-001. No page may hold a
 * second copy of these numbers; every view reads the same helpers. Formulas
 * are implemented explicitly (never `eval`-ed from formulaDefinitions).
 */

export function parseDecimal(value: string | number): number {
  return typeof value === 'number' ? value : Number.parseFloat(value)
}

/** Decimal HALF_UP to `digits` places, matching the fixture's rounding rule. */
export function roundHalfUp(value: number, digits = 2): number {
  if (!Number.isFinite(value)) return value
  const factor = 10 ** digits
  const sign = value < 0 ? -1 : 1
  return (sign * Math.round(Math.abs(value) * factor + Number.EPSILON)) / factor
}

export function formatDisplayNumber(value: number, maximumFractionDigits = 2): string {
  if (!Number.isFinite(value)) return '—'
  return value.toLocaleString('zh-CN', { maximumFractionDigits })
}

/** Plain (no thousands separator) formatting for contract text templates. */
export function formatPlainNumber(value: number, maximumFractionDigits = 2): string {
  if (!Number.isFinite(value)) return '—'
  return String(Number(value.toFixed(maximumFractionDigits)))
}

export function defaultParametersFromFixture(fixture: ScenarioFixture): ScenarioParameters {
  return {
    savedHours: parseDecimal(fixture.optimization.defaultParameters.savedHours),
    tariffCnyPerKwh: parseDecimal(fixture.optimization.defaultParameters.tariffCnyPerKwh),
    applicableDaysPerMonth: fixture.optimization.defaultParameters.applicableDaysPerMonth,
  }
}

export interface ParameterValidation {
  ok: boolean
  value: ScenarioParameters | null
  errors: string[]
}

/** Validates page input against the scenario's interactive bounds (A07). */
export function validateParameters(fixture: ScenarioFixture, raw: Partial<ScenarioParameters>): ParameterValidation {
  const bounds = fixture.optimization.parameterBounds
  const errors: string[] = []
  const savedHours = Number(raw.savedHours)
  const tariff = Number(raw.tariffCnyPerKwh)
  const days = Number(raw.applicableDaysPerMonth)

  const minHours = parseDecimal(bounds.savedHours.min)
  const maxHours = parseDecimal(bounds.savedHours.max)
  const minTariff = parseDecimal(bounds.tariffCnyPerKwh.min)
  const maxTariff = parseDecimal(bounds.tariffCnyPerKwh.max)

  if (!Number.isFinite(savedHours) || savedHours < minHours || savedHours > maxHours) {
    errors.push(`减少时长需在 ${minHours}—${maxHours} 小时之间。`)
  } else if (Math.abs(savedHours / 0.5 - Math.round(savedHours / 0.5)) > 1e-9) {
    errors.push('减少时长需按 0.5 小时调整。')
  }
  if (!Number.isFinite(tariff) || tariff < minTariff || tariff > maxTariff) {
    errors.push(`演示电价需在 ${minTariff}—${maxTariff} 元/kWh 之间。`)
  }
  if (!Number.isInteger(days) || days < bounds.applicableDaysPerMonth.min || days > bounds.applicableDaysPerMonth.max) {
    errors.push(`月度适用日需为 ${bounds.applicableDaysPerMonth.min}—${bounds.applicableDaysPerMonth.max} 的整数。`)
  }
  if (errors.length) return { ok: false, value: null, errors }
  return {
    ok: true,
    value: { savedHours, tariffCnyPerKwh: tariff, applicableDaysPerMonth: days },
    errors,
  }
}

/** Applies the PARTIAL_DATA variant without mutating the fixture. */
export function effectiveLedger(
  fixture: ScenarioFixture,
  variant: ScenarioVariantId,
): { ledger: EnergyLedgerRow[]; missingReadingIds: string[]; dataQuality: DataQuality } {
  const omitted = new Set<string>()
  if (variant === 'PARTIAL_DATA') {
    const partial = fixture.variants.find((item) => item.variantId === 'PARTIAL_DATA')
    const omitId = partial?.delta?.omitObservedReadingId
    if (typeof omitId === 'string') omitted.add(omitId)
  }
  const ledger = fixture.energyLedger.rows.map((row) => omitted.has(row.readingId)
    ? { ...row, observedMissing: true }
    : { ...row })
  return {
    ledger,
    missingReadingIds: [...omitted],
    dataQuality: omitted.size ? 'PARTIAL' : 'COMPLETE',
  }
}

export function sumBaseline(rows: EnergyLedgerRow[]): number {
  return roundHalfUp(rows.reduce((sum, row) => sum + row.baselineKwh, 0))
}

export function sumObserved(rows: EnergyLedgerRow[]): number {
  return roundHalfUp(rows.reduce((sum, row) => sum + (row.observedMissing ? 0 : row.observedKwh), 0))
}

export function totalsFromRows(rows: EnergyLedgerRow[]): LedgerTotals {
  const baselineKwh = sumBaseline(rows)
  const observedKwh = sumObserved(rows)
  return {
    baselineKwh,
    observedKwh,
    excessKwh: roundHalfUp(observedKwh - baselineKwh),
    deviationPct: baselineKwh === 0 ? 0 : roundHalfUp(((observedKwh - baselineKwh) / baselineKwh) * 100),
  }
}

export function channelTotals(fixture: ScenarioFixture, ledger: EnergyLedgerRow[]): ChannelTotal[] {
  return fixture.energyLedger.meterAggregation.childDeviceIds.map((deviceId) => {
    const rows = ledger.filter((row) => row.deviceId === deviceId)
    return {
      deviceId,
      baselineKwh: sumBaseline(rows),
      observedKwh: sumObserved(rows),
      observedMissingCount: rows.filter((row) => row.observedMissing).length,
    }
  })
}

/** B2 total meter is the per-bucket sum of its four child channels only. */
export function b2Totals(fixture: ScenarioFixture, ledger: EnergyLedgerRow[]): LedgerTotals {
  const childIds = new Set(fixture.energyLedger.meterAggregation.childDeviceIds)
  return totalsFromRows(ledger.filter((row) => childIds.has(row.deviceId)))
}

/**
 * B1/B3 are explicit demo generators: both baseline and observed are the B2
 * baseline sum times their scale, so the park never reuses B2's 30% story.
 */
export function parkTotals(fixture: ScenarioFixture, ledger: EnergyLedgerRow[]): LedgerTotals {
  const b2 = b2Totals(fixture, ledger)
  const background = fixture.buildings
    .filter((building) => building.meterRule === 'SCALED_B2_BASELINE' && building.scale)
    .reduce((sum, building) => sum + b2.baselineKwh * parseDecimal(building.scale!), 0)
  const baselineKwh = roundHalfUp(b2.baselineKwh + background)
  const observedKwh = roundHalfUp(b2.observedKwh + background)
  return {
    baselineKwh,
    observedKwh,
    excessKwh: roundHalfUp(observedKwh - baselineKwh),
    deviationPct: baselineKwh === 0 ? 0 : roundHalfUp(((observedKwh - baselineKwh) / baselineKwh) * 100),
  }
}

export function planById(fixture: ScenarioFixture, planId: string | null | undefined): ScenarioPlan | null {
  if (!planId) return null
  return fixture.plans.find((plan) => plan.planId === planId) ?? null
}

export function avoidablePowerKw(fixture: ScenarioFixture, deviceId: string): number {
  const assumption = fixture.optimization.powerAssumptions.find((item) => item.deviceId === deviceId)
  return assumption ? parseDecimal(assumption.avoidablePowerKw) : 0
}

export function planPowerKw(fixture: ScenarioFixture, plan: ScenarioPlan | null): number {
  if (!plan || !plan.createsOrder) return 0
  return roundHalfUp(plan.targetDeviceIds.reduce((sum, deviceId) => sum + avoidablePowerKw(fixture, deviceId), 0))
}

/**
 * Estimates a plan against a ledger.
 *
 * Returns `null` when the observation set is incomplete. A full-cycle saving
 * estimate is only defined on a complete ledger (scenario constraint:
 * “缺失需要的观测时不计算完整周期收益”), so callers must present the
 * estimate as unavailable rather than derive numbers from partial data.
 */
export function planEstimate(
  fixture: ScenarioFixture,
  ledger: EnergyLedgerRow[],
  parameters: ScenarioParameters,
  planId: string,
): PlanEstimate | null {
  if (ledger.some((row) => row.observedMissing)) return null
  const observed = b2Totals(fixture, ledger).observedKwh
  const plan = planById(fixture, planId)
  const power = planPowerKw(fixture, plan)
  const saved = roundHalfUp(power * parameters.savedHours)
  return {
    planId,
    estimatedSavedKwhPerDay: saved,
    estimatedAfterKwhPerDay: roundHalfUp(observed - saved),
    estimatedSavingPctOfObserved: observed === 0 ? 0 : roundHalfUp((saved / observed) * 100),
    estimatedMonthlySavingsCny: roundHalfUp(saved * parameters.applicableDaysPerMonth * parameters.tariffCnyPerKwh),
  }
}

function windowOverlapHours(bucketStartMs: number, durationMinutes: number, windowStartMs: number, windowEndMs: number): number {
  const bucketEndMs = bucketStartMs + durationMinutes * 60_000
  const overlap = Math.min(bucketEndMs, windowEndMs) - Math.max(bucketStartMs, windowStartMs)
  return overlap > 0 ? overlap / 3_600_000 : 0
}

/**
 * Generates the next-cycle simulated result (FOLLOWUP_ENERGY_V1).
 *
 * The observation buckets are shifted +24h; only the selected plan's target
 * devices inside the adjustment window are reduced by
 * `power × overlapHours × responseFactor`, and never below the bucket's own
 * baseline. Protected devices and background buildings are untouched.
 */
export function simulateFollowup(
  fixture: ScenarioFixture,
  ledger: EnergyLedgerRow[],
  parameters: ScenarioParameters,
  planId: string,
  options: { verificationNow?: string } = {},
): FollowupSimulation {
  const plan = planById(fixture, planId)
  const responseFactor = parseDecimal(fixture.followupSimulation.responseFactor)
  const missingReadingIds = ledger.filter((row) => row.observedMissing).map((row) => row.readingId)
  const observedTotal = b2Totals(fixture, ledger).observedKwh
  const baselineTotal = b2Totals(fixture, ledger).baselineKwh

  const targetDevices = new Set(plan?.createsOrder ? plan.targetDeviceIds : [])
  const windowStartMs = Date.parse(fixture.optimization.adjustmentWindowInObservation.from)
  const windowMaxMs = Date.parse(fixture.optimization.adjustmentWindowInObservation.maxTo)
  const requestedEndMs = windowStartMs + parameters.savedHours * 3_600_000
  const windowEndMs = Math.min(requestedEndMs, windowMaxMs)
  const shiftMs = 24 * 3_600_000

  let savedKwh = 0
  const rows: FollowupRow[] = ledger.map((row) => {
    let deducted = 0
    if (targetDevices.has(row.deviceId) && !row.observedMissing) {
      const bucketStartMs = Date.parse(row.bucketStart)
      const overlapHours = windowOverlapHours(bucketStartMs, row.durationMinutes, windowStartMs, windowEndMs)
      if (overlapHours > 0) {
        const requested = avoidablePowerKw(fixture, row.deviceId) * overlapHours * responseFactor
        const positiveIncrement = Math.max(0, row.observedKwh - row.baselineKwh)
        deducted = roundHalfUp(Math.min(requested, positiveIncrement))
      }
    }
    savedKwh += deducted
    return {
      deviceId: row.deviceId,
      bucketStart: row.bucketStart,
      followupBucketStart: new Date(Date.parse(row.bucketStart) + shiftMs).toISOString(),
      baselineKwh: row.baselineKwh,
      observedKwh: row.observedMissing ? Number.NaN : row.observedKwh,
      followupKwh: row.observedMissing ? Number.NaN : roundHalfUp(row.observedKwh - deducted),
      deductedKwh: deducted,
    }
  })

  const roundedSaved = roundHalfUp(savedKwh)
  const followupKwh = roundHalfUp(observedTotal - roundedSaved)
  return {
    planId,
    parameters,
    savedKwh: roundedSaved,
    followupKwh,
    remainingDeviationPct: baselineTotal === 0 ? 0 : roundHalfUp(((followupKwh - baselineTotal) / baselineTotal) * 100),
    rows,
    partial: missingReadingIds.length > 0,
    missingReadingIds,
  }
}

/** Park-level simulated follow-up total including the unchanged B1/B3. */
export function parkFollowupKwh(fixture: ScenarioFixture, ledger: EnergyLedgerRow[], b2FollowupKwh: number): number {
  const b2Baseline = b2Totals(fixture, ledger).baselineKwh
  const background = fixture.buildings
    .filter((building) => building.meterRule === 'SCALED_B2_BASELINE' && building.scale)
    .reduce((sum, building) => sum + b2Baseline * parseDecimal(building.scale!), 0)
  return roundHalfUp(b2FollowupKwh + background)
}

export function buildPatrolResult(fixture: ScenarioFixture, dataQuality: DataQuality): PatrolResult {
  const checks: PatrolCheckResult[] = fixture.patrolChecks.map((check) => {
    if (check.checkId === 'SCN-CHECK-DATA' && dataQuality === 'PARTIAL') {
      return { ...check, successResult: 'ATTENTION', result: 'ATTENTION' } as PatrolCheckResult
    }
    return {
      checkId: check.checkId,
      label: check.label,
      result: check.successResult,
      eventRef: check.eventRef,
      evidenceRefs: check.evidenceRefs,
    }
  })
  const attention = checks.filter((check) => check.result === 'ATTENTION')
  const pass = checks.filter((check) => check.result === 'PASS')
  const events: PatrolEvent[] = attention.some((check) => check.eventRef)
    ? [{
        anomalyId: fixture.eventTemplate.anomalyId,
        title: fixture.eventTemplate.title,
        buildingId: fixture.eventTemplate.buildingId,
        deviceId: fixture.eventTemplate.deviceId,
        category: fixture.eventTemplate.category,
        priority: fixture.eventTemplate.priority,
        observedAt: fixture.eventTemplate.observedAt,
        affectedDeviceIds: [...fixture.eventTemplate.affectedDeviceIds],
      }]
    : []
  return {
    checks,
    attentionCount: attention.length,
    passCount: pass.length,
    uniqueEventCount: events.length,
    events,
  }
}

function interpolate(template: string, values: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) => values[key] ?? match)
}

export function buildAssessment(fixture: ScenarioFixture, ledger: EnergyLedgerRow[]): Assessment {
  const b2 = b2Totals(fixture, ledger)
  const template = fixture.assessmentTemplate
  const summary = interpolate(template.summaryTemplate, {
    observedKwh: formatPlainNumber(b2.observedKwh, 0),
    excessKwh: formatPlainNumber(b2.excessKwh, 0),
    deviationPct: formatPlainNumber(b2.deviationPct, 2),
  })
  return {
    title: template.title,
    mode: template.mode,
    summary,
    facts: template.facts.map((fact) => ({ factId: fact.factId, text: fact.template, evidenceRefs: [...fact.evidenceRefs] })),
    unknowns: [...template.unknowns],
    recommendedPlanId: template.recommendedPlanId,
    evidenceRefs: ['/energyLedger', '/operatingFacts'],
  }
}

/** Whether a plan is allowed given the data quality and protected devices. */
export function planCanExecute(fixture: ScenarioFixture, plan: ScenarioPlan | null, dataQuality: DataQuality): boolean {
  if (!plan || !plan.createsOrder) return false
  if (dataQuality !== 'COMPLETE') return false
  const protectedIds = new Set(fixture.operatingFacts.protectedDeviceIds)
  return plan.targetDeviceIds.every((deviceId) => !protectedIds.has(deviceId))
}

export function buildingName(buildings: ScenarioBuilding[], buildingId: string): string {
  return buildings.find((building) => building.buildingId === buildingId)?.name ?? buildingId
}
