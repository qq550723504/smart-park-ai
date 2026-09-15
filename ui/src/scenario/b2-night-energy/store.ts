import { computed, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type {
  ScenarioParameters,
  ScenarioReport,
  ScenarioSnapshot,
  ScenarioVariantId,
  ScenarioWorkOrder,
} from '../../types/scenarioEnergy'
import { MockScenarioProvider, ScenarioFaultError, type PersistedScenarioRun } from './provider'
import type { PlanEstimate } from '../../types/scenarioEnergy'

const RUN_STORAGE_KEY = 'smart-park:scenario:b2-night-energy:run:v1'
const MODE_STORAGE_KEY = 'smart-park:scenario:b2-night-energy:mode:v1'

export interface ScenarioStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface B2ScenarioStore {
  /** Explicit scenario mode flag; the online pages keep their default behaviour when false. */
  active: Ref<boolean>
  snapshot: Ref<ScenarioSnapshot>
  busy: Ref<boolean>
  error: Ref<string>
  generation: Ref<number>
  pending: ComputedRef<ScenarioSnapshot['state']['pendingCommand']>
  stage: ComputedRef<ScenarioSnapshot['state']['stage']>
  reports: ComputedRef<ScenarioReport[]>
  activeReport: ComputedRef<ScenarioReport | null>
  workOrder: ComputedRef<ScenarioWorkOrder | null>
  hasStoredRun: ComputedRef<boolean>
  canReset: ComputedRef<boolean>
  enter(): void
  exit(): void
  refresh(): void
  startPatrol(): Promise<boolean>
  runAssessment(): Promise<boolean>
  selectPlan(planId: string): Promise<boolean>
  updateParameters(raw: Partial<ScenarioParameters>): Promise<boolean>
  confirmAndCreateOrder(): Promise<boolean>
  keepObserving(): Promise<boolean>
  takeOrder(): Promise<boolean>
  applySimulatedPlan(): Promise<boolean>
  verifyNextCycle(): Promise<boolean>
  generateReport(): Promise<boolean>
  openReport(reportId: string): Promise<boolean>
  setVariant(variant: ScenarioVariantId): Promise<boolean>
  reset(): Promise<boolean>
}

export interface B2ScenarioStoreOptions {
  storage?: ScenarioStorage | null
  provider?: MockScenarioProvider
  latencyMs?: number
}

function resolveStorage(): ScenarioStorage | null {
  try {
    if (typeof globalThis !== 'undefined' && globalThis.sessionStorage) return globalThis.sessionStorage
  } catch {
    /* storage can throw in restricted embeds */
  }
  return null
}

function errorMessage(error: unknown): string {
  if (error instanceof ScenarioFaultError) return error.message
  if (error instanceof Error) return error.message
  return '场景操作失败，请重试。'
}

/**
 * The shared runtime state for SCN-B2-NIGHT-ENERGY-001.
 *
 * A single store instance backs every page: switching pages, refreshing or
 * re-reading never re-runs a command, while a reset bumps the run generation
 * so stale awaiters cannot mutate a newer run.
 */
export function createB2ScenarioStore(options: B2ScenarioStoreOptions = {}): B2ScenarioStore {
  const storage = options.storage === undefined ? resolveStorage() : options.storage
  const latencyMs = options.latencyMs ?? 0
  const provider = options.provider ?? new MockScenarioProvider()

  const active = ref(false)
  const busy = ref(false)
  const error = ref('')
  const generation = ref(0)
  const restoredFromCache = ref(false)

  if (storage) {
    const modeRaw = storage.getItem(MODE_STORAGE_KEY)
    active.value = modeRaw === 'true'
    const runRaw = storage.getItem(RUN_STORAGE_KEY)
    if (runRaw) {
      try {
        const persisted = JSON.parse(runRaw) as PersistedScenarioRun
        restoredFromCache.value = provider.restoreState(persisted)
      } catch {
        restoredFromCache.value = false
      }
    }
  }

  const snapshot = shallowRef<ScenarioSnapshot>(provider.read())

  function persist(): void {
    if (!storage) return
    try {
      storage.setItem(RUN_STORAGE_KEY, JSON.stringify(provider.exportState()))
      storage.setItem(MODE_STORAGE_KEY, String(active.value))
    } catch {
      /* quota or serialization failures must not break the run */
    }
  }

  function delay(): Promise<void> {
    if (latencyMs <= 0) return Promise.resolve()
    return new Promise((resolve) => setTimeout(resolve, latencyMs))
  }

  async function runCommand(action: string, execute: () => ScenarioSnapshot): Promise<boolean> {
    if (busy.value) return false
    const startGeneration = generation.value
    busy.value = true
    error.value = ''
    try {
      await delay()
      if (generation.value !== startGeneration) return false
      snapshot.value = execute()
      persist()
      return true
    } catch (caught) {
      if (generation.value !== startGeneration) return false
      // A lost create response still committed inside the run; surface the
      // committed state so the retry can reuse the same identity.
      snapshot.value = provider.read()
      error.value = errorMessage(caught)
      persist()
      return false
    } finally {
      if (generation.value === startGeneration) busy.value = false
    }
  }

  const pending = computed(() => snapshot.value.state.pendingCommand)
  const stage = computed(() => snapshot.value.state.stage)
  const reports = computed(() => snapshot.value.state.reports)
  const activeReport = computed(() => {
    const id = snapshot.value.state.activeReportId
    return id ? snapshot.value.state.reports.find((report) => report.reportId === id) ?? null : null
  })
  const workOrder = computed(() => snapshot.value.state.workOrder)
  const hasStoredRun = computed(() => restoredFromCache.value || snapshot.value.state.stateRevision > 0)
  const canReset = computed(() => !busy.value && !pending.value)

  return {
    active,
    snapshot,
    busy,
    error,
    generation,
    pending,
    stage,
    reports,
    activeReport,
    workOrder,
    hasStoredRun,
    canReset,
    enter() {
      active.value = true
      persist()
    },
    exit() {
      active.value = false
      persist()
    },
    refresh() {
      // Orthogonal read: re-reads the same run without changing revision.
      snapshot.value = provider.read()
    },
    startPatrol: () => runCommand('START_PATROL', () => provider.startPatrol()),
    runAssessment: () => runCommand('RUN_ASSESSMENT', () => provider.runAssessment()),
    selectPlan: (planId) => runCommand('SELECT_PLAN', () => provider.selectPlan(planId)),
    updateParameters: (raw) => runCommand('UPDATE_PARAMETERS', () => provider.updateParameters(raw)),
    confirmAndCreateOrder: () => runCommand('CONFIRM_AND_CREATE_ORDER', () => provider.confirmAndCreateOrder()),
    keepObserving: () => runCommand('KEEP_OBSERVING', () => provider.keepObserving()),
    takeOrder: () => runCommand('TAKE_ORDER', () => provider.takeOrder()),
    applySimulatedPlan: () => runCommand('APPLY_SIMULATED_PLAN', () => provider.applySimulatedPlan()),
    verifyNextCycle: () => runCommand('VERIFY_NEXT_CYCLE', () => provider.verifyNextCycle()),
    generateReport: () => runCommand('GENERATE_REPORT', () => provider.generateReport()),
    openReport: (reportId) => runCommand('OPEN_REPORT', () => provider.openReport(reportId)),
    setVariant: (variant) => runCommand('SET_VARIANT', () => provider.setVariant(variant)),
    async reset() {
      if (!canReset.value) {
        error.value = '存在未确认的模拟命令，请先完成或重试后再重开场景。'
        return false
      }
      generation.value += 1
      error.value = ''
      snapshot.value = provider.reset()
      restoredFromCache.value = false
      persist()
      return true
    },
  }
}

let singleton: B2ScenarioStore | null = null

/** Shared per-session store used by every customer page. */
export function useB2NightEnergyScenario(): B2ScenarioStore {
  if (!singleton) singleton = createB2ScenarioStore()
  return singleton
}

/** Test-only hook to drop the module singleton between cases. */
export function resetB2NightEnergyScenarioSingleton(): void {
  singleton = null
}

/** Convenience: the estimate currently shown for the selected plan. */
export function activeEstimate(store: B2ScenarioStore): PlanEstimate | null {
  const state = store.snapshot.value.state
  return state.confirmedPlan?.estimate ?? state.planDraft?.estimate ?? null
}
