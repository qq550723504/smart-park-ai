import { watch } from 'vue'
import type {
  GuidedLaunchUpdate,
  ScenarioLaunchRequest,
  ShowcaseScenarioId,
} from '../types/workbench'

interface GuidedLaunchResult {
  state: 'started' | 'ready'
  message: string
}

interface GuidedLaunchOptions {
  active: () => boolean
  request: () => ScenarioLaunchRequest | null | undefined
  scenarioId: ShowcaseScenarioId
  start: (request: ScenarioLaunchRequest) => Promise<GuidedLaunchResult>
  onUpdate: (update: GuidedLaunchUpdate) => void
}

export function useGuidedLaunch(options: GuidedLaunchOptions): void {
  let consumedRequestId: number | null = null
  watch(
    [options.request, options.active],
    async ([request, active]) => {
      if (!active || !request || request.scenarioId !== options.scenarioId
        || request.requestId === consumedRequestId) return
      consumedRequestId = request.requestId
      options.onUpdate({ requestId: request.requestId, state: 'preparing', message: '演示准备中' })
      try {
        const result = await options.start(request)
        options.onUpdate({ requestId: request.requestId, ...result })
      } catch (cause) {
        options.onUpdate({
          requestId: request.requestId,
          state: 'failed',
          message: cause instanceof Error ? cause.message : '现场演示启动失败',
        })
      }
    },
    { immediate: true },
  )
}
