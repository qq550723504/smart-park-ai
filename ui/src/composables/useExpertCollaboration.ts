import { computed, onBeforeUnmount, ref } from 'vue'
import { getCollaborationRun, startCollaboration } from '../services/collaborationApi'
import type { CollaborationRun } from '../types/collaboration'

export function useExpertCollaboration(pollIntervalMs = 500) {
  const run = ref<CollaborationRun | null>(null)
  const loading = ref(false)
  const error = ref('')
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let generation = 0

  const MAX_CONSECUTIVE_FAILURES = 5
  let consecutiveFailures = 0

  const isRunning = computed(() => run.value?.status === 'RUNNING')
  const isTerminal = computed(() => Boolean(run.value && run.value.status !== 'RUNNING'))

  function stopPolling() {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
  }

  async function poll(runId: string, currentGeneration: number): Promise<void> {
    try {
      const next = await getCollaborationRun(runId)
      if (generation !== currentGeneration) return
      consecutiveFailures = 0
      run.value = next
      if (next.status === 'RUNNING') {
        pollTimer = setTimeout(() => void poll(runId, currentGeneration), pollIntervalMs)
      }
    } catch (cause) {
      if (generation !== currentGeneration) return
      // A transient failure must not strand a RUNNING run: retry with bounded
      // backoff while this collaboration is still the current one.
      consecutiveFailures += 1
      if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
        const backoff = Math.min(4000, pollIntervalMs * 2 ** consecutiveFailures)
        error.value = cause instanceof Error ? cause.message : '专家协作状态同步失败'
        pollTimer = setTimeout(() => void poll(runId, currentGeneration), backoff)
      } else {
        error.value = cause instanceof Error ? cause.message : '专家协作状态同步失败，已停止重试'
      }
    }
  }

  async function start(question: string): Promise<string> {
    const normalized = question.trim()
    if (!normalized) throw new Error('请输入需要专家协作分析的问题')
    stopPolling()
    const currentGeneration = ++generation
    loading.value = true
    error.value = ''
    run.value = null
    // A fresh collaboration gets its own poll retry budget; a previous run
    // that exhausted the retries must not consume it.
    consecutiveFailures = 0
    try {
      const started = await startCollaboration(normalized)
      if (generation === currentGeneration) void poll(started.runId, currentGeneration)
      return started.runId
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '专家协作启动失败'
      throw cause
    } finally {
      if (generation === currentGeneration) loading.value = false
    }
  }

  function reset() {
    generation++
    stopPolling()
    run.value = null
    error.value = ''
    loading.value = false
    consecutiveFailures = 0
  }

  onBeforeUnmount(reset)
  return { run, loading, error, isRunning, isTerminal, start, reset }
}
