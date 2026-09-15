import { describe, expect, it } from 'vitest'
import { B2_SCENARIO_FIXTURE } from './fixture'
import {
  b2Totals,
  buildAssessment,
  buildPatrolResult,
  channelTotals,
  defaultParametersFromFixture,
  effectiveLedger,
  parkFollowupKwh,
  parkTotals,
  planEstimate,
  planPowerKw,
  planById,
  roundHalfUp,
  simulateFollowup,
  validateParameters,
  variantPinnedPlanId,
} from './calculations'

const fixture = B2_SCENARIO_FIXTURE
const { ledger } = effectiveLedger(fixture, 'NORMAL')
const defaults = defaultParametersFromFixture(fixture)

describe('B2 scenario ledger and totals', () => {
  it('ships 4 channels x 24 hourly buckets = 96 readings', () => {
    expect(fixture.energyLedger.rows).toHaveLength(96)
    expect(fixture.energyLedger.meterAggregation.childDeviceIds).toHaveLength(4)
  })

  it('keeps B2 baseline 1000, observed 1300, 30% deviation', () => {
    expect(b2Totals(fixture, ledger)).toEqual({
      baselineKwh: 1000,
      observedKwh: 1300,
      observedMissingCount: 0,
      observedComplete: true,
      excessKwh: 300,
      deviationPct: 30,
    })
  })

  it('matches the per-channel totals', () => {
    const totals = Object.fromEntries(channelTotals(fixture, ledger).map((row) => [row.deviceId, row]))
    expect(totals['SCN-B2-HVAC-PUBLIC']).toMatchObject({ baselineKwh: 240, observedKwh: 440 })
    expect(totals['SCN-B2-LIGHT-PUBLIC']).toMatchObject({ baselineKwh: 120, observedKwh: 160 })
    expect(totals['SCN-B2-HVAC-RD']).toMatchObject({ baselineKwh: 280, observedKwh: 340 })
    expect(totals['SCN-B2-ESSENTIAL']).toMatchObject({ baselineKwh: 360, observedKwh: 360 })
  })

  it('derives the park total 2600 -> 2900 (11.54%) without reusing B2 30%', () => {
    expect(parkTotals(fixture, ledger)).toEqual({
      baselineKwh: 2600,
      observedKwh: 2900,
      observedMissingCount: 0,
      observedComplete: true,
      excessKwh: 300,
      deviationPct: 11.54,
    })
  })

  it('never double counts the aggregation-only total meter', () => {
    const totalRow = fixture.energyLedger.rows.find((row) => row.deviceId === 'SCN-B2-METER-TOTAL')
    expect(totalRow).toBeUndefined()
  })

  it('rounds HALF_UP', () => {
    expect(roundHalfUp(4.6153846)).toBe(4.62)
    expect(roundHalfUp(11.5384615)).toBe(11.54)
    expect(roundHalfUp(2.225)).toBe(2.23)
    // Decimal ties must use HALF_UP even when the double is slightly below the
    // tie (4.725 * 100 === 472.49999999999994).
    expect(roundHalfUp(4.725)).toBe(4.73)
    expect(roundHalfUp(1.005)).toBe(1.01)
    expect(roundHalfUp(-4.725)).toBe(-4.73)
  })

  it('keeps the allowed combined-plan monthly estimate HALF_UP', () => {
    const estimate = planEstimate(
      fixture,
      ledger,
      { savedHours: 0.5, tariffCnyPerKwh: 0.42, applicableDaysPerMonth: 1 },
      'SCN-PLAN-HVAC-LIGHT',
    )
    expect(estimate?.estimatedMonthlySavingsCny).toBe(4.73)
  })
})

describe('B2 plan estimation', () => {
  it('computes recommended and combined plan defaults', () => {
    const recommended = planEstimate(fixture, ledger, defaults, 'SCN-PLAN-PUBLIC-HVAC')
    expect(recommended).toEqual({
      planId: 'SCN-PLAN-PUBLIC-HVAC',
      estimatedSavedKwhPerDay: 80,
      estimatedAfterKwhPerDay: 1220,
      estimatedSavingPctOfObserved: 6.15,
      estimatedMonthlySavingsCny: 1760,
    })
    const combined = planEstimate(fixture, ledger, defaults, 'SCN-PLAN-HVAC-LIGHT')
    expect(combined).toMatchObject({ estimatedSavedKwhPerDay: 90, estimatedAfterKwhPerDay: 1210, estimatedMonthlySavingsCny: 1980 })
  })

  it('keeps the no-action plan at zero saving', () => {
    expect(planEstimate(fixture, ledger, defaults, 'SCN-PLAN-NONE')).toMatchObject({ estimatedSavedKwhPerDay: 0, estimatedAfterKwhPerDay: 1300 })
  })

  it('withholds the full-cycle estimate while an observation is missing', () => {
    const { ledger: partial } = effectiveLedger(fixture, 'PARTIAL_DATA')
    expect(planEstimate(fixture, partial, defaults, 'SCN-PLAN-PUBLIC-HVAC')).toBeNull()
    expect(planEstimate(fixture, partial, defaults, 'SCN-PLAN-NONE')).toBeNull()
  })

  it('recomputes when the duration changes to 3h', () => {
    const parameters = { savedHours: 3, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 }
    expect(planEstimate(fixture, ledger, parameters, 'SCN-PLAN-PUBLIC-HVAC')).toEqual({
      planId: 'SCN-PLAN-PUBLIC-HVAC',
      estimatedSavedKwhPerDay: 60,
      estimatedAfterKwhPerDay: 1240,
      estimatedSavingPctOfObserved: 4.62,
      estimatedMonthlySavingsCny: 1320,
    })
  })

  it('derives power from assumptions, not hard-coded literals', () => {
    expect(planPowerKw(fixture, planById(fixture, 'SCN-PLAN-PUBLIC-HVAC'))).toBe(20)
    expect(planPowerKw(fixture, planById(fixture, 'SCN-PLAN-HVAC-LIGHT'))).toBe(22.5)
  })

  it('rejects out-of-range or malformed parameters', () => {
    expect(validateParameters(fixture, { savedHours: 5, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 }).ok).toBe(false)
    expect(validateParameters(fixture, { savedHours: 4.3, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 }).ok).toBe(false)
    expect(validateParameters(fixture, { savedHours: 3, tariffCnyPerKwh: 0, applicableDaysPerMonth: 22 }).ok).toBe(false)
    expect(validateParameters(fixture, { savedHours: 3, tariffCnyPerKwh: 1, applicableDaysPerMonth: 32 }).ok).toBe(false)
    expect(validateParameters(fixture, { savedHours: 3.5, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 }).ok).toBe(true)
  })

  it('enforces the fixture step for every bounded parameter', () => {
    // tariffCnyPerKwh step is 0.01: an in-range value off the grid is rejected.
    const offGridTariff = validateParameters(fixture, { savedHours: 3, tariffCnyPerKwh: 1.005, applicableDaysPerMonth: 22 })
    expect(offGridTariff.ok).toBe(false)
    expect(offGridTariff.errors.join(' ')).toContain('元/kWh')
    expect(validateParameters(fixture, { savedHours: 3, tariffCnyPerKwh: 1.01, applicableDaysPerMonth: 22 }).ok).toBe(true)
    // savedHours step is read from the fixture too, so the error names it.
    const offGridHours = validateParameters(fixture, { savedHours: 1.25, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 })
    expect(offGridHours.ok).toBe(false)
    expect(offGridHours.errors.join(' ')).toContain('按 0.5 小时')
    // Days must stay a whole number of days (step 1).
    expect(validateParameters(fixture, { savedHours: 3, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22.5 }).ok).toBe(false)
  })
})

describe('B2 follow-up simulation', () => {
  it('generates 72 kWh saved / 1228 total / 22.8% for the recommended plan', () => {
    const result = simulateFollowup(fixture, ledger, defaults, 'SCN-PLAN-PUBLIC-HVAC')
    expect(result.savedKwh).toBe(72)
    expect(result.followupKwh).toBe(1228)
    expect(result.remainingDeviationPct).toBe(22.8)
    expect(result.partial).toBe(false)
    expect(parkFollowupKwh(fixture, ledger, result.followupKwh)).toBe(2828)
  })

  it('adds lighting saving for the combined plan (81 / 1219 / 21.9)', () => {
    const result = simulateFollowup(fixture, ledger, defaults, 'SCN-PLAN-HVAC-LIGHT')
    expect(result).toMatchObject({ savedKwh: 81, followupKwh: 1219, remainingDeviationPct: 21.9 })
  })

  it('shrinks to 54 kWh when the duration is 3h', () => {
    const parameters = { savedHours: 3, tariffCnyPerKwh: 1, applicableDaysPerMonth: 22 }
    expect(simulateFollowup(fixture, ledger, parameters, 'SCN-PLAN-PUBLIC-HVAC')).toMatchObject({
      savedKwh: 54, followupKwh: 1246, remainingDeviationPct: 24.6,
    })
  })

  it('never deducts below a bucket baseline and never touches protected devices', () => {
    const result = simulateFollowup(fixture, ledger, defaults, 'SCN-PLAN-HVAC-LIGHT')
    const rd = result.rows.filter((row) => row.deviceId === 'SCN-B2-HVAC-RD')
    const essential = result.rows.filter((row) => row.deviceId === 'SCN-B2-ESSENTIAL')
    expect(rd.every((row) => row.deductedKwh === 0 && row.followupKwh === row.observedKwh)).toBe(true)
    expect(essential.every((row) => row.deductedKwh === 0)).toBe(true)
    const target = result.rows.find((row) => row.deviceId === 'SCN-B2-HVAC-PUBLIC' && row.bucketStart.includes('T22:00'))
    expect(target?.followupKwh).toBeGreaterThanOrEqual(target?.baselineKwh ?? 0)
  })

  it('shifts every bucket by exactly +24h', () => {
    const result = simulateFollowup(fixture, ledger, defaults, 'SCN-PLAN-PUBLIC-HVAC')
    for (const row of result.rows) {
      expect(Date.parse(row.followupBucketStart) - Date.parse(row.bucketStart)).toBe(24 * 3600 * 1000)
    }
  })
})

describe('B2 patrol and assessment', () => {
  it('aggregates 4 checks / 2 attention / 2 pass into exactly 1 event', () => {
    const patrol = buildPatrolResult(fixture, 'COMPLETE')
    expect(patrol.checks).toHaveLength(4)
    expect(patrol.attentionCount).toBe(2)
    expect(patrol.passCount).toBe(2)
    expect(patrol.uniqueEventCount).toBe(1)
    expect(patrol.events[0]?.anomalyId).toBe('SCN-ALT-B2-NIGHT-001')
  })

  it('marks data completeness as attention under PARTIAL', () => {
    const patrol = buildPatrolResult(fixture, 'PARTIAL')
    expect(patrol.attentionCount).toBe(3)
    expect(patrol.passCount).toBe(1)
    expect(patrol.uniqueEventCount).toBe(1)
  })

  it('fills the assessment summary from the ledger', () => {
    const assessment = buildAssessment(fixture, ledger)
    expect(assessment.summary).toContain('1300')
    expect(assessment.summary).toContain('300')
    expect(assessment.summary).toContain('30')
    expect(assessment.recommendedPlanId).toBe('SCN-PLAN-PUBLIC-HVAC')
  })
})

describe('B2 PARTIAL_DATA variant', () => {
  it('marks the omitted observed bucket missing without zero filling', () => {
    const partial = effectiveLedger(fixture, 'PARTIAL_DATA')
    expect(partial.dataQuality).toBe('PARTIAL')
    expect(partial.missingReadingIds).toEqual(['SCN-B2-HVAC-PUBLIC:15'])
    const row = partial.ledger.find((item) => item.readingId === 'SCN-B2-HVAC-PUBLIC:15')
    expect(row?.observedMissing).toBe(true)
    expect(row?.baselineKwh).toBe(0)
    // observed sum excludes the missing bucket instead of treating it as 0
    expect(b2Totals(fixture, partial.ledger).observedKwh).toBe(1280)
  })

  it('withholds excess and deviation while the observation set is incomplete', () => {
    const partial = effectiveLedger(fixture, 'PARTIAL_DATA')
    const totals = b2Totals(fixture, partial.ledger)
    expect(totals.observedComplete).toBe(false)
    expect(totals.observedMissingCount).toBe(1)
    // The 1280 subtotal and the 1000 baseline cover unlike periods, so no exact
    // excess/deviation may be published (this used to read 28%).
    expect(totals.excessKwh).toBeNull()
    expect(totals.deviationPct).toBeNull()

    const park = parkTotals(fixture, partial.ledger)
    expect(park.observedComplete).toBe(false)
    expect(park.excessKwh).toBeNull()
    expect(park.deviationPct).toBeNull()
  })

  it('describes the assessment as incomplete instead of publishing a deviation', () => {
    const partial = effectiveLedger(fixture, 'PARTIAL_DATA')
    const assessment = buildAssessment(fixture, partial.ledger)
    expect(assessment.summary).toContain('观测不完整')
    expect(assessment.summary).toContain('1280')
    expect(assessment.summary).not.toContain('较基线增加')
    expect(assessment.summary).not.toContain('%')
    expect(assessment.unknowns).toContain('观测不完整，本周期总用电与完整基线的偏差尚不可计算')
  })

  it('returns a partial follow-up result that is flagged, not silently complete', () => {
    const partial = effectiveLedger(fixture, 'PARTIAL_DATA')
    const result = simulateFollowup(fixture, partial.ledger, defaults, 'SCN-PLAN-PUBLIC-HVAC')
    expect(result.partial).toBe(true)
    expect(result.missingReadingIds).toEqual(['SCN-B2-HVAC-PUBLIC:15'])
  })
})

describe('B2 variant plan delta', () => {
  it('pins only the variants that declare a selectedPlanId', () => {
    expect(variantPinnedPlanId(fixture, 'NO_ACTION')).toBe('SCN-PLAN-NONE')
    expect(variantPinnedPlanId(fixture, 'NORMAL')).toBeNull()
    expect(variantPinnedPlanId(fixture, 'PARTIAL_DATA')).toBeNull()
    expect(variantPinnedPlanId(fixture, 'LOST_CREATE_RESPONSE')).toBeNull()
  })
})
