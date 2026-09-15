import type {
  LedgerTotals,
  ScenarioConfirmedPlan,
  ScenarioFixture,
  ScenarioParameters,
  ScenarioReport,
  ScenarioReportSnapshot,
  ScenarioRunState,
  ScenarioSnapshot,
  ScenarioStage,
  ScenarioVariantId,
  ScenarioWorkOrder,
} from '../../types/scenarioEnergy'
import {
  b2Totals,
  buildAssessment,
  buildPatrolResult,
  channelTotals,
  defaultParametersFromFixture,
  effectiveLedger,
  formatDisplayNumber,
  parkFollowupKwh,
  parkTotals,
  planById,
  planCanExecute,
  planEstimate,
  roundHalfUp,
  simulateFollowup,
  validateParameters,
  variantPinnedPlanId,
} from './calculations'
import { B2_SCENARIO_FIXTURE, createInitialState, runSequence, scenarioRunIdFor } from './fixture'

export class ScenarioFaultError extends Error {
  constructor(message: string, readonly command: string, readonly idempotencyKey: string) {
    super(message)
    this.name = 'ScenarioFaultError'
  }
}

export class ScenarioStateError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ScenarioStateError'
  }
}

export interface ScenarioFaultConfig {
  /** Simulates a committed create whose response is lost after commit (once). */
  lostCreateResponse?: boolean
}

export interface MockScenarioProviderOptions {
  fixture?: ScenarioFixture
  variant?: ScenarioVariantId
  runSequenceNumber?: number
  faults?: ScenarioFaultConfig
}

export interface PersistedScenarioRun {
  schemaVersion: 1
  scenarioId: string
  scenarioVersion: string
  variant: ScenarioVariantId
  runSequenceNumber: number
  state: ScenarioRunState
}

/**
 * Formats an epoch instant as an ISO-8601 string carrying the scenario's fixed
 * UTC offset (e.g. `+08:00`). The frozen clock is pinned to Asia/Shanghai and
 * consumers render timestamps by slicing the string, so advancing the clock
 * must not convert to UTC (`toISOString`), which would show a 09:00 patrol as
 * 01:00.
 */
function formatScenarioTimestamp(epochMs: number, offsetMinutes: number): string {
  const shifted = new Date(epochMs + offsetMinutes * 60_000)
  const sign = offsetMinutes < 0 ? '-' : '+'
  const abs = Math.abs(offsetMinutes)
  const offset = `${sign}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`
  return `${shifted.toISOString().slice(0, 19)}${offset}`
}

/** Reads the fixed UTC offset (in minutes) declared by the frozen clock string. */
function clockOffsetMinutes(initialNow: string): number {
  const match = /([+-])(\d{2}):(\d{2})$/.exec(initialNow)
  if (!match) return 0
  const sign = match[1] === '-' ? -1 : 1
  return sign * (Number(match[2]) * 60 + Number(match[3]))
}

function advanceVirtualNow(virtualNow: string, minutes: number, offsetMinutes: number): string {
  if (!minutes) return virtualNow
  return formatScenarioTimestamp(Date.parse(virtualNow) + minutes * 60_000, offsetMinutes)
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

function renderReportMarkdown(snapshot: ScenarioReportSnapshot): string {
  const plan = snapshot.planSnapshot
  const estimate = snapshot.estimateSnapshot
  const followup = snapshot.followupSnapshot
  const order = snapshot.orderSnapshot
  // A closed no-action run has no `planSnapshot` but deliberately selected
  // SCN-PLAN-NONE; the brief must record that decision explicitly so it is not
  // indistinguishable from a report generated before any plan was chosen.
  const noActionSelected = snapshot.selectedPlanId === 'SCN-PLAN-NONE'
  const lines: string[] = []
  lines.push(`# 研发大厦夜间能耗事件简报`)
  lines.push('')
  lines.push(`> 演示园区 · 模拟场景 · ${snapshot.scenarioRunId} · 快照版本 ${snapshot.stateRevision}`)
  lines.push('')
  lines.push('## 1. 本次问题与周期')
  lines.push(`- 楼宇：${snapshot.buildingId} 研发大厦`)
  lines.push(`- 事件：${snapshot.anomalyId}（${snapshot.eventStatus}）`)
  lines.push(`- 观察周期：${snapshot.observationWindow.from} 至 ${snapshot.observationWindow.to}`)
  lines.push(`- 当前阶段：${snapshot.stage}`)
  lines.push('')
  lines.push('## 2. 多方面依据')
  lines.push('- 用电账本与基线来自同一固定观察窗口。')
  lines.push('- 公共区域在 22:00—02:00 存在计划外运行，且本场景同期没有批准预约。')
  lines.push('- 研发区域有已批准加班安排，不纳入方案；机房与必要基础负荷保持不变。')
  lines.push('')
  lines.push('## 3. 选择的方案及参数')
  if (plan) {
    lines.push(`- 方案：${plan.planId}`)
    lines.push(`- 目标设备：${plan.targetDeviceIds.join('、') || '无'}`)
    lines.push(`- 保护设备：${plan.protectedDeviceIds.join('、') || '无'}`)
    lines.push(`- 参数：减少 ${plan.parameters.savedHours} 小时 / ${plan.parameters.tariffCnyPerKwh} 元每 kWh / 每月 ${plan.parameters.applicableDaysPerMonth} 个适用日`)
  } else if (noActionSelected) {
    lines.push('- 客户明确选择“保持现状 / 保持观察”，本次不创建任务。')
    lines.push('- 该结论是主动决策，不是缺少可执行方案；整体偏差继续观察。')
  } else {
    lines.push('- 尚未确认可执行方案。')
  }
  lines.push('')
  lines.push('## 4. 预计效果')
  if (estimate) {
    lines.push(`- 预计每日减少用电：${formatDisplayNumber(estimate.estimatedSavedKwhPerDay)} kWh`)
    lines.push(`- 预计调整后日用能：${formatDisplayNumber(estimate.estimatedAfterKwhPerDay)} kWh`)
    lines.push(`- 预计月度电量电费减少：${formatDisplayNumber(estimate.estimatedMonthlySavingsCny)} 元（演示电价估算）`)
  } else {
    lines.push('- 尚未形成预计效果。')
  }
  lines.push('')
  lines.push('## 5. 任务状态与处理记录')
  if (order) {
    lines.push(`- 工单：${order.id}（${order.statusLabel}）`)
    lines.push(`- 负责人：${order.assigneeActorId}`)
    lines.push(`- 摘要：${order.summary}`)
  } else if (noActionSelected) {
    lines.push('- 本次选择保持观察，未创建任务。')
  } else {
    lines.push('- 本次未创建任务。')
  }
  lines.push('')
  lines.push('## 6. 模拟后续结果（已验证才显示）')
  if (followup) {
    lines.push(`- 下一周期模拟减少：${formatDisplayNumber(followup.savedKwh)} kWh`)
    lines.push(`- 下一周期模拟总用电：${formatDisplayNumber(followup.followupKwh)} kWh`)
    lines.push(`- 仍高于基线：${formatDisplayNumber(followup.remainingDeviationPct)}%`)
  } else {
    lines.push('- 尚未验证下一周期，不展示未来读数。')
  }
  lines.push('')
  lines.push('## 7. 仍需关注与后续建议')
  lines.push('- 完成本方案的任务不等于整栋楼恢复正常，整体偏差仍需持续观察。')
  lines.push('- 月度费用为演示假设估算，不能把单一模拟周期结果描述为已核实收益。')
  lines.push('')
  return lines.join('\n')
}

export class MockScenarioProvider {
  private readonly fixture: ScenarioFixture
  private state: ScenarioRunState
  private variant: ScenarioVariantId
  private readonly faults: ScenarioFaultConfig
  private readonly clockOffsetMinutes: number
  private runSequenceNumber: number

  constructor(options: MockScenarioProviderOptions = {}) {
    this.fixture = options.fixture ?? B2_SCENARIO_FIXTURE
    this.variant = options.variant ?? 'NORMAL'
    this.runSequenceNumber = options.runSequenceNumber ?? 1
    // The variant and the lost-response fault are one switch: selecting the
    // variant must arm the fault here too, exactly like `setVariant`/`reset`.
    this.faults = { lostCreateResponse: this.variant === 'LOST_CREATE_RESPONSE', ...options.faults }
    this.clockOffsetMinutes = clockOffsetMinutes(this.fixture.clock.initialNow)
    this.state = createInitialState(scenarioRunIdFor(this.runSequenceNumber))
  }

  get scenarioRunId(): string {
    return this.state.scenarioRunId
  }

  get currentVariant(): ScenarioVariantId {
    return this.variant
  }

  setVariant(variant: ScenarioVariantId): ScenarioSnapshot {
    if (variant === this.variant) return this.read()
    this.requireNoPendingCommand('SET_VARIANT')
    // The variant decides the interpretation of the fixture (ledger, data
    // quality, fault). Derived state is frozen at the stage it was produced,
    // so swapping the variant mid-run would mix ledgers and estimates. The
    // run must be reset instead.
    if (this.state.stage !== 'READY') {
      throw new ScenarioStateError('场景已开始，不能中途切换演示变体；请先重开本场景。')
    }
    this.variant = variant
    this.faults.lostCreateResponse = variant === 'LOST_CREATE_RESPONSE'
    return this.read()
  }

  read(): ScenarioSnapshot {
    return this.buildSnapshot(this.state)
  }

  private buildSnapshot(state: ScenarioRunState): ScenarioSnapshot {
    const { ledger, missingReadingIds, dataQuality } = effectiveLedger(this.fixture, this.variant)
    const b2 = b2Totals(this.fixture, ledger)
    const followup = state.followupResult
    return {
      scenarioId: this.fixture.meta.scenarioId,
      scenarioVersion: this.fixture.meta.version,
      dataSource: this.fixture.meta.dataSource,
      banner: this.fixture.meta.banner,
      detailModeLabel: this.fixture.meta.detailModeLabel,
      variant: this.variant,
      state: clone(state),
      clock: clone(this.fixture.clock),
      park: clone(this.fixture.park),
      buildings: clone(this.fixture.buildings),
      devices: clone(this.fixture.devices),
      plans: clone(this.fixture.plans),
      actors: clone(this.fixture.actors),
      protectedDeviceIds: [...this.fixture.operatingFacts.protectedDeviceIds],
      optimization: clone(this.fixture.optimization),
      reportContract: clone(this.fixture.reportContract),
      stageLabels: clone(this.fixture.runtimeContract.stageLabels),
      stateRevision: state.stateRevision,
      anomaly: {
        anomalyId: this.fixture.eventTemplate.anomalyId,
        title: this.fixture.eventTemplate.title,
        category: this.fixture.eventTemplate.category,
        priority: this.fixture.eventTemplate.priority,
        observedAt: this.fixture.eventTemplate.observedAt,
        deviceId: this.fixture.eventTemplate.deviceId,
        affectedDeviceIds: [...this.fixture.eventTemplate.affectedDeviceIds],
      },
      effectiveLedger: ledger,
      dataQuality,
      missingReadingIds,
      channelTotals: channelTotals(this.fixture, ledger),
      b2,
      parkTotals: parkTotals(this.fixture, ledger),
      parkFollowupKwh: followup ? parkFollowupKwh(this.fixture, ledger, followup.followupKwh) : null,
      defaultParameters: defaultParametersFromFixture(this.fixture),
      pinnedPlanId: variantPinnedPlanId(this.fixture, this.variant),
    }
  }

  /**
   * A lost create response leaves an unresolved command identity behind. Stage
   * mutations (other than that command's own same-identity retry) must not run
   * until it is acknowledged: otherwise a later commit could silently drop the
   * pending receipt and re-enable reset without ever recovering the committed
   * order. Orthogonal reads (report generation/opening) stay allowed.
   */
  private requireNoPendingCommand(action: string): void {
    const pending = this.state.pendingCommand
    if (pending && pending.command !== action) {
      throw new ScenarioStateError(`存在未确认的模拟命令（${pending.command}），请先按同一身份重试，不要执行其他变更。`)
    }
  }

  private commit(action: string, mutate: (draft: ScenarioRunState) => void, advanceMinutes = 0, bumpRevision = true): ScenarioSnapshot {
    const draft = clone(this.state)
    mutate(draft)
    if (bumpRevision) draft.stateRevision += 1
    draft.virtualNow = advanceVirtualNow(draft.virtualNow, advanceMinutes, this.clockOffsetMinutes)
    draft.commandLog = [...draft.commandLog, { action, at: draft.virtualNow, stateRevision: draft.stateRevision }]
    // `pendingCommand` is deliberately not cleared here. It is only removed by
    // the same-identity retry that acknowledges the lost response, so unrelated
    // commits (e.g. generating a report at ORDER_CREATED) can never drop it.
    this.state = draft
    return this.read()
  }

  startPatrol(): ScenarioSnapshot {
    this.requireNoPendingCommand('START_PATROL')
    if (this.state.stage !== 'READY') throw new ScenarioStateError('当前阶段不能重复启动巡检。')
    const { dataQuality } = effectiveLedger(this.fixture, this.variant)
    return this.commit('START_PATROL', (draft) => {
      draft.stage = 'PATROL_DONE'
      draft.eventStatus = 'OPEN'
      draft.patrolResult = buildPatrolResult(this.fixture, dataQuality)
    }, 1)
  }

  runAssessment(): ScenarioSnapshot {
    this.requireNoPendingCommand('RUN_ASSESSMENT')
    if (this.state.stage !== 'PATROL_DONE') throw new ScenarioStateError('需要先完成巡检，才能形成综合研判。')
    const { ledger } = effectiveLedger(this.fixture, this.variant)
    return this.commit('RUN_ASSESSMENT', (draft) => {
      draft.stage = 'ASSESSED'
      draft.assessment = buildAssessment(this.fixture, ledger)
    }, 1)
  }

  selectPlan(planId: string): ScenarioSnapshot {
    this.requireNoPendingCommand('SELECT_PLAN')
    if (this.state.stage !== 'ASSESSED' && this.state.stage !== 'PLAN_SELECTED') {
      throw new ScenarioStateError('当前阶段不能选择方案。')
    }
    // A variant may pin the run's plan (NO_ACTION → 保持现状). Enforce it here,
    // in the authoritative state layer, so a pinned variant can never create an
    // executable order regardless of what a page requests.
    const pinned = variantPinnedPlanId(this.fixture, this.variant)
    if (pinned && planId !== pinned) {
      const pinnedLabel = planById(this.fixture, pinned)?.label ?? pinned
      throw new ScenarioStateError(`当前演示变体固定选择“${pinnedLabel}”，不能改选其他方案。`)
    }
    const plan = planById(this.fixture, planId)
    if (!plan) throw new ScenarioStateError('未知方案。')
    const { ledger } = effectiveLedger(this.fixture, this.variant)
    const keepDraft = this.state.planDraft?.planId === planId ? this.state.planDraft.parameters : null
    const parameters = keepDraft ?? defaultParametersFromFixture(this.fixture)
    return this.commit('SELECT_PLAN', (draft) => {
      draft.stage = 'PLAN_SELECTED'
      draft.selectedPlanId = planId
      draft.planRevision += 1
      draft.planDraft = { planId, parameters: { ...parameters }, estimate: planEstimate(this.fixture, ledger, parameters, planId) }
    })
  }

  updateParameters(raw: Partial<ScenarioParameters>): ScenarioSnapshot {
    this.requireNoPendingCommand('UPDATE_PARAMETERS')
    if (this.state.stage !== 'PLAN_SELECTED') throw new ScenarioStateError('只有在待确认阶段才能调整参数。')
    const planId = this.state.selectedPlanId
    if (!planId) throw new ScenarioStateError('请先选择方案。')
    const validation = validateParameters(this.fixture, raw)
    if (!validation.ok || !validation.value) throw new ScenarioStateError(validation.errors.join(' '))
    const { ledger } = effectiveLedger(this.fixture, this.variant)
    const parameters = validation.value
    return this.commit('UPDATE_PARAMETERS', (draft) => {
      draft.planRevision += 1
      draft.planDraft = { planId, parameters: { ...parameters }, estimate: planEstimate(this.fixture, ledger, parameters, planId) }
    })
  }

  private orderIdempotencyKey(planRevision: number): string {
    return `${this.state.scenarioRunId}+${this.fixture.eventTemplate.anomalyId}+${planRevision}`
  }

  /**
   * Resolves the committed order for the current plan revision directly from
   * the persisted run state. There is no side cache: `state.confirmedPlan` and
   * `state.workOrder` are the authority, so a retry always reconstructs the
   * exact current run (including reports generated while the receipt was
   * unresolved) instead of restoring a stale snapshot from an earlier commit.
   */
  private committedOrderSnapshot(): ScenarioSnapshot | null {
    const confirmed = this.state.confirmedPlan
    if (!confirmed || !this.state.workOrder) return null
    if (confirmed.planRevision !== this.state.planRevision) return null
    return this.buildSnapshot({ ...clone(this.state), pendingCommand: null })
  }

  confirmAndCreateOrder(): ScenarioSnapshot {
    // The same-identity retry is the one action allowed to resolve the pending
    // receipt; every other mutating action is rejected while it is unresolved.
    this.requireNoPendingCommand('CONFIRM_AND_CREATE_ORDER')
    const committedOrder = this.committedOrderSnapshot()
    if (committedOrder) {
      this.state = clone(committedOrder.state)
      this.state.pendingCommand = null
      return this.read()
    }
    if (this.state.stage !== 'PLAN_SELECTED') throw new ScenarioStateError('请先选择并确认方案。')
    const planId = this.state.selectedPlanId
    const plan = planById(this.fixture, planId)
    const { dataQuality } = effectiveLedger(this.fixture, this.variant)
    if (!plan || !plan.createsOrder) throw new ScenarioStateError('保持现状不会创建任务；请选择可执行方案。')
    if (!planCanExecute(this.fixture, plan, dataQuality)) {
      throw new ScenarioStateError('关键小时数据不完整，已暂停完整节能估算与提交。')
    }
    const draft = this.state.planDraft
    if (!draft || !planId) throw new ScenarioStateError('方案参数缺失，不能创建任务。')
    if (!draft.estimate) throw new ScenarioStateError('数据不完整，无法形成完整节能估算，已暂停创建任务。')
    const seq = runSequence(this.state.scenarioRunId)
    const now = this.state.virtualNow
    const confirmed: ScenarioConfirmedPlan = {
      planId,
      targetDeviceIds: [...plan.targetDeviceIds],
      protectedDeviceIds: [...this.fixture.operatingFacts.protectedDeviceIds],
      parameters: { ...draft.parameters },
      estimate: { ...draft.estimate },
      planRevision: this.state.planRevision,
    }
    const workOrder: ScenarioWorkOrder = {
      id: `${this.fixture.workOrderTemplate.idPrefix}-${seq}-001`,
      workflowId: `${this.fixture.workOrderTemplate.workflowIdPrefix}-${seq}-001`,
      parkId: this.fixture.workOrderTemplate.parkId,
      buildingId: this.fixture.workOrderTemplate.buildingId,
      deviceId: this.fixture.workOrderTemplate.deviceId,
      alertId: this.fixture.workOrderTemplate.alertId,
      summary: `${plan.label}：对 ${plan.targetDeviceIds.join('、')} 减少非必要运行 ${draft.parameters.savedHours} 小时。`,
      status: this.fixture.workOrderTemplate.initialStatus,
      statusLabel: this.fixture.workOrderTemplate.statusLabels[this.fixture.workOrderTemplate.initialStatus] ?? '演示任务已创建，待处理',
      selectedPlanId: planId,
      targetDeviceIds: [...plan.targetDeviceIds],
      protectedDeviceIds: [...this.fixture.operatingFacts.protectedDeviceIds],
      parameterSnapshot: { ...draft.parameters },
      estimateSnapshot: { ...draft.estimate },
      assigneeActorId: this.fixture.workOrderTemplate.assigneeActorId,
      createdAt: now,
      updatedAt: now,
    }
    const snapshot = this.commit('CONFIRM_AND_CREATE_ORDER', (state) => {
      state.stage = 'ORDER_CREATED'
      state.eventStatus = 'HANDLING'
      state.confirmedPlan = confirmed
      state.workOrder = workOrder
    }, 1)
    const key = this.orderIdempotencyKey(confirmed.planRevision)
    if (this.faults.lostCreateResponse) {
      this.faults.lostCreateResponse = false
      this.state = clone(this.state)
      this.state.pendingCommand = { command: 'CONFIRM_AND_CREATE_ORDER', idempotencyKey: key, status: 'LOST_RESPONSE' }
      throw new ScenarioFaultError(
        '建单已在场景内提交，但模拟响应丢失；请按同一身份重试，不会重复建单。',
        'CONFIRM_AND_CREATE_ORDER',
        key,
      )
    }
    return this.read()
  }

  keepObserving(): ScenarioSnapshot {
    this.requireNoPendingCommand('KEEP_OBSERVING')
    if (this.state.stage !== 'PLAN_SELECTED') throw new ScenarioStateError('只有在待确认阶段才能选择保持观察。')
    if (this.state.selectedPlanId !== 'SCN-PLAN-NONE') throw new ScenarioStateError('只有保持现状方案才能跳过建单。')
    return this.commit('KEEP_OBSERVING', (draft) => {
      draft.stage = 'CLOSED_NO_ACTION'
      draft.eventStatus = 'MONITORING'
    })
  }

  takeOrder(): ScenarioSnapshot {
    this.requireNoPendingCommand('TAKE_ORDER')
    if (this.state.stage !== 'ORDER_CREATED' || !this.state.workOrder) throw new ScenarioStateError('当前没有待接单的模拟任务。')
    return this.commit('TAKE_ORDER', (draft) => {
      draft.stage = 'PROCESSING'
      if (draft.workOrder) {
        draft.workOrder = {
          ...draft.workOrder,
          status: 'IN_PROGRESS',
          statusLabel: this.fixture.workOrderTemplate.statusLabels.IN_PROGRESS ?? '模拟处理进行中',
          updatedAt: draft.virtualNow,
        }
      }
    }, 1)
  }

  applySimulatedPlan(): ScenarioSnapshot {
    this.requireNoPendingCommand('APPLY_SIMULATED_PLAN')
    if (this.state.stage !== 'PROCESSING' || !this.state.workOrder) throw new ScenarioStateError('模拟应用前需要先接单。')
    return this.commit('APPLY_SIMULATED_PLAN', (draft) => {
      draft.stage = 'APPLIED_AWAITING_VERIFICATION'
      if (draft.workOrder) draft.workOrder = { ...draft.workOrder, updatedAt: draft.virtualNow }
    }, 5)
  }

  verifyNextCycle(): ScenarioSnapshot {
    this.requireNoPendingCommand('VERIFY_NEXT_CYCLE')
    if (this.state.stage !== 'APPLIED_AWAITING_VERIFICATION' || !this.state.confirmedPlan) {
      throw new ScenarioStateError('模拟结果只有在显式验证下一周期后才可见。')
    }
    const { ledger } = effectiveLedger(this.fixture, this.variant)
    const confirmed = this.state.confirmedPlan
    const result = simulateFollowup(this.fixture, ledger, confirmed.parameters, confirmed.planId)
    return this.commit('VERIFY_NEXT_CYCLE', (draft) => {
      draft.stage = 'VERIFIED'
      draft.virtualNow = this.fixture.clock.verificationNow
      draft.followupResult = result
      draft.eventStatus = result.remainingDeviationPct > 20 ? 'MONITORING' : 'CLOSED'
      if (draft.workOrder) {
        draft.workOrder = {
          ...draft.workOrder,
          status: 'RESOLVED',
          statusLabel: this.fixture.workOrderTemplate.statusLabels.RESOLVED ?? '模拟任务已完成',
          updatedAt: draft.virtualNow,
        }
      }
    })
  }

  generateReport(): ScenarioSnapshot {
    const allowed = this.fixture.reportContract.allowedAfter
    if (!allowed.includes(this.state.stage)) throw new ScenarioStateError('当前阶段不能生成事件简报。')
    const kind = 'SCENARIO_EVENT_BRIEF' as const
    // Report idempotency is derived from the persisted run state: the same run
    // + stateRevision + kind already resolves to a snapshot in `state.reports`,
    // so a reloaded run never mints a second copy or recomputes.
    const existing = this.state.reports.find(
      (report) => report.stateRevision === this.state.stateRevision && report.kind === kind,
    )
    if (existing) {
      this.state = this.state.activeReportId === existing.reportId
        ? this.state
        : { ...clone(this.state), activeReportId: existing.reportId }
      return this.read()
    }
    const snapshotData = this.buildSnapshot(this.state)
    const reportNo = this.state.reports.length + 1
    const seq = runSequence(this.state.scenarioRunId)
    const reportSnapshot: ScenarioReportSnapshot = {
      reportId: `${this.fixture.reportContract.idPrefix}-${seq}-${String(reportNo).padStart(2, '0')}`,
      kind,
      format: 'Markdown',
      scenarioId: this.fixture.meta.scenarioId,
      scenarioVersion: this.fixture.meta.version,
      scenarioRunId: this.state.scenarioRunId,
      dataSource: this.fixture.meta.dataSource,
      stateRevision: this.state.stateRevision,
      stage: this.state.stage,
      eventStatus: this.state.eventStatus,
      virtualGeneratedAt: this.state.virtualNow,
      observationWindow: { ...this.fixture.clock.observationWindow },
      followupWindow: { ...this.fixture.clock.followupWindow },
      buildingId: this.fixture.eventTemplate.buildingId,
      anomalyId: this.fixture.eventTemplate.anomalyId,
      selectedPlanId: this.state.selectedPlanId,
      planSnapshot: this.state.confirmedPlan ? clone(this.state.confirmedPlan) : null,
      orderSnapshot: this.state.workOrder ? clone(this.state.workOrder) : null,
      estimateSnapshot: this.state.confirmedPlan?.estimate ?? snapshotData.state.planDraft?.estimate ?? null,
      followupSnapshot: this.state.followupResult ? clone(this.state.followupResult) : null,
      b2Totals: clone(snapshotData.b2),
    }
    const report: ScenarioReport = {
      ...reportSnapshot,
      title: `研发大厦夜间能耗事件简报 · ${this.state.scenarioRunId}`,
      markdown: renderReportMarkdown(reportSnapshot),
    }
    this.commit('GENERATE_REPORT', (draft) => {
      draft.reports = [...draft.reports, report]
      draft.activeReportId = report.reportId
    }, 0, false)
    return this.read()
  }

  openReport(reportId: string): ScenarioSnapshot {
    const exists = this.state.reports.some((report) => report.reportId === reportId)
    if (!exists) throw new ScenarioStateError('未找到该场景简报快照。')
    const draft = clone(this.state)
    draft.activeReportId = reportId
    this.state = draft
    return this.read()
  }

  /** Serialisable snapshot of the whole shared run, for session persistence. */
  exportState(): PersistedScenarioRun {
    return {
      schemaVersion: 1,
      scenarioId: this.fixture.meta.scenarioId,
      scenarioVersion: this.fixture.meta.version,
      variant: this.variant,
      runSequenceNumber: this.runSequenceNumber,
      state: clone(this.state),
    }
  }

  /**
   * Rehydrates a persisted shared run. Identity is scenarioId + version +
   * scenarioRunId; anything else is rejected so a stale cache can never leak
   * into a new run.
   */
  restoreState(persisted: PersistedScenarioRun): boolean {
    if (
      persisted.schemaVersion !== 1
      || persisted.scenarioId !== this.fixture.meta.scenarioId
      || persisted.scenarioVersion !== this.fixture.meta.version
      || !persisted.state
      || typeof persisted.state.scenarioRunId !== 'string'
    ) {
      return false
    }
    this.variant = persisted.variant
    this.runSequenceNumber = persisted.runSequenceNumber
    this.state = clone(persisted.state)
    this.state.commandLog = [...(persisted.state.commandLog ?? [])]
    // Re-arm the lost-response fault unless this run already consumed it, so a
    // reloaded run keeps the same fault semantics while never re-firing it.
    const faultConsumed = this.state.pendingCommand?.status === 'LOST_RESPONSE'
    this.faults.lostCreateResponse = persisted.variant === 'LOST_CREATE_RESPONSE' && !faultConsumed
    return true
  }

  /** Resets the scenario to a fresh run and invalidates prior command identities. */
  reset(): ScenarioSnapshot {
    this.requireNoPendingCommand('RESET_SCENARIO')
    this.runSequenceNumber += 1
    this.state = createInitialState(scenarioRunIdFor(this.runSequenceNumber))
    this.faults.lostCreateResponse = this.variant === 'LOST_CREATE_RESPONSE'
    return this.read()
  }
}

/** Builds the expected per-plan totals used by tests and acceptance checks. */
export function defaultPlanTotals(fixture: ScenarioFixture): Record<string, { estimate: ReturnType<typeof planEstimate>; followup: ReturnType<typeof simulateFollowup> }> {
  const { ledger } = effectiveLedger(fixture, 'NORMAL')
  const parameters = defaultParametersFromFixture(fixture)
  const result: Record<string, { estimate: ReturnType<typeof planEstimate>; followup: ReturnType<typeof simulateFollowup> }> = {}
  for (const plan of fixture.plans) {
    result[plan.planId] = {
      estimate: planEstimate(fixture, ledger, parameters, plan.planId),
      followup: simulateFollowup(fixture, ledger, parameters, plan.planId),
    }
  }
  return result
}
