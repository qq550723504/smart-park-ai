# Immersive Workbench Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将首页、运营工作台及五个业务场景统一为首页的沉浸式深色视觉体系，并让“开始现场演示”真正触发一次性引导演示。

**Architecture:** `App.vue` 负责生成一次性的引导启动请求，`OperationsWorkbench.vue` 负责场景与启动状态编排，业务页面只消费匹配自己的请求。视觉层提取首页主题变量，新增纯展示的工作台壳层与证据带，并将原先集中在 `styles.css` 的场景样式拆到各自组件附近。

**Tech Stack:** Vue 3.5、TypeScript 5.9、Element Plus 2.11、ECharts 6.1、Vitest 4.1、Vite 7.1、Docker Compose

**Spec:** `docs/plans/2026-08-30-immersive-workbench-redesign-design.md`

## Global Constraints

- 首页是唯一视觉基准；主背景必须是 `#06090f`，次级背景必须是 `#0c111a`，主强调色必须是 `#70e8ff`，次强调色必须是 `#8f5cff`，主文字必须是 `#fff0d2`，警示色必须是 `#ffd27a`。
- 桌面双栏断点为 `>= 1280px`；`768px–1279px` 改为单列；`< 768px` 使用紧凑导航和折叠轨迹。
- 右侧执行轨迹在桌面端固定可见；等待审批状态在窄屏轨迹中默认展开。
- 不改变后端 API 协议、智能体执行语义、业务数据源或现有场景数量。
- 不引入新的 UI 框架；继续使用 Vue、Element Plus、ECharts 和现有 `@element-plus/icons-vue`。
- 不复制 `showcase-home.css`，不使用大范围 `!important` 覆盖浅色主题，不伪造执行结果或证据。
- 语音引导只预连接后端会话；麦克风权限仍必须由用户点击确认。
- 所有异步区域覆盖空闲、加载、成功、失败和无数据状态；状态不能只依赖颜色表达。

---

## File Structure

### New files

- `ui/src/types/workbench.ts`：场景 ID、工作台视图、一次性启动请求、启动状态、导航项和证据项类型。
- `ui/src/composables/useGuidedLaunch.ts`：只消费一次匹配启动请求的公共逻辑。
- `ui/src/composables/useGuidedLaunch.spec.ts`：一次性消费、延迟到激活、失败状态测试。
- `ui/src/styles/showcase-theme.css`：首页与工作台共享的主题变量、页面基础背景和可访问性基础规则。
- `ui/src/styles/theme-contract.spec.ts`：共享色板、遗留浅色色板和响应式规则的静态契约测试。
- `ui/src/components/workbench/ImmersiveWorkbenchShell.vue`：纯展示壳层，包含顶部导航、主舞台、轨迹插槽与证据带。
- `ui/src/components/workbench/ImmersiveWorkbenchShell.spec.ts`：壳层语义、导航、角色和插槽测试。
- `ui/src/components/workbench/WorkbenchEvidenceRibbon.vue`：底部证据摘要列表。
- `ui/src/components/workbench/immersive-workbench.css`：壳层、玻璃面板、Element Plus 作用域变量和响应式规则。
- `ui/src/styles/workbench-primitives.css`：场景共同使用的标题、面板、状态、按钮和表单规则。
- `ui/src/styles/workflow.css`：告警工作流及其子组件规则。
- `ui/src/components/customer-service.css`：园区客服规则。
- `ui/src/components/expert-collaboration.css`：专家协作及专家卡规则。
- `ui/src/components/voice/voice-assistant.css`：语音页面规则。

### Modified files

- `ui/src/main.ts`：全局加载共享主题。
- `ui/src/services/workflowApi.ts`：复用统一场景 ID 类型。
- `ui/src/App.vue`、`ui/src/App.spec.ts`：区分手动入口与引导入口。
- `ui/src/components/OperationsWorkbench.vue`、`ui/src/components/OperationsWorkbench.spec.ts`：接入壳层、启动编排和证据项。
- `ui/src/components/ExpertCollaborationPage.vue`、对应 spec：消费专家协作引导请求。
- `ui/src/components/analytics/OperationsAnalysisPage.vue`、对应 spec：消费默认分析引导请求。
- `ui/src/components/voice/VoiceAssistantPage.vue`、对应 spec：引导时预连接但不请求麦克风。
- `ui/src/composables/useVoiceSession.ts`、对应 spec：公开无麦克风权限副作用的 `prepare()`。
- `ui/src/components/analytics/AnalyticsChart.vue`、对应 spec：为所有图表类型注入暗色主题。
- `ui/src/components/analytics/analytics.css`：迁移为共享主题变量。
- `ui/src/components/execution/execution-rail.css`：迁移执行轨迹与事件卡的暗色样式。
- `ui/src/components/CustomerServiceConsole.vue`、`ExpertCollaborationPage.vue`、`VoiceAssistantPage.vue`：加载各自样式文件。
- `ui/src/components/showcase/showcase-home.css`：删除局部变量定义，消费共享变量。
- `ui/src/layout.spec.ts`：改为读取拆分后的布局 CSS 并验证新断点。
- `ui/src/styles.css`：完成迁移后删除，防止继续维护第二套全局样式系统。

---

### Task 1: Define entry intent and one-shot launch request

**Files:**
- Create: `ui/src/types/workbench.ts`
- Modify: `ui/src/services/workflowApi.ts:21-39`
- Modify: `ui/src/App.vue:1-76`
- Test: `ui/src/App.spec.ts`

**Interfaces:**
- Produces: `ShowcaseScenarioId`, `WorkbenchView`, `ScenarioLaunchRequest`, `GuidedLaunchUpdate`, `WorkbenchNavItem`, `WorkbenchEvidenceItem`.
- Produces: `OperationsWorkbench.launchRequest: ScenarioLaunchRequest | null`.
- Consumes: existing `ShowcaseHome` events `start-scenario` and `enter-workbench`.

- [ ] **Step 1: Write failing coordinator tests**

Update the workbench test stub with a nullable object prop and assert that scenario starts are guided while the generic workbench entry is manual:

```ts
launchRequest: {
  type: Object as PropType<ScenarioLaunchRequest | null>,
  default: null,
},
```

```ts
it('creates a guided one-shot request for a showcase scenario', async () => {
  const wrapper = mountApp()
  wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'EXPERT_COLLABORATION')
  await nextTick()

  expect(wrapper.getComponent(OperationsWorkbench).props('launchRequest')).toMatchObject({
    requestId: 1,
    mode: 'guided',
    scenarioId: 'EXPERT_COLLABORATION',
    view: 'collaboration',
  })
})

it('clears guided launch state for the manual workbench entry', async () => {
  const wrapper = mountApp()
  wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'ALERT_WORKFLOW')
  await nextTick()
  await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')
  wrapper.getComponent(ShowcaseHome).vm.$emit('enter-workbench')
  await nextTick()

  expect(wrapper.getComponent(OperationsWorkbench).props('initialView')).toBe('workflow')
  expect(wrapper.getComponent(OperationsWorkbench).props('launchRequest')).toBeNull()
})

it('issues a fresh request id after returning to the showcase', async () => {
  const wrapper = mountApp()
  wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'OPERATIONS_ANALYSIS')
  await nextTick()
  const first = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest
  await wrapper.get('[data-testid="back-to-showcase"]').trigger('click')
  wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'OPERATIONS_ANALYSIS')
  await nextTick()
  const second = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest

  expect(second.requestId).toBeGreaterThan(first.requestId)
})
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `cd ui; npm run test:unit -- src/App.spec.ts`

Expected: FAIL because `launchRequest` and `ScenarioLaunchRequest` do not exist.

- [ ] **Step 3: Add the shared types**

Create `ui/src/types/workbench.ts` with this exact public contract:

```ts
export type ShowcaseScenarioId =
  | 'ALERT_WORKFLOW'
  | 'EXPERT_COLLABORATION'
  | 'OPERATIONS_ANALYSIS'
  | 'VOICE_ASSISTANT'

export type WorkbenchView = 'workflow' | 'customer' | 'voice' | 'collaboration' | 'analytics'
export type GuidedWorkbenchView = Exclude<WorkbenchView, 'customer'>

export interface ScenarioLaunchRequest {
  requestId: number
  mode: 'guided'
  scenarioId: ShowcaseScenarioId
  view: GuidedWorkbenchView
}

export type GuidedLaunchState = 'preparing' | 'started' | 'ready' | 'failed'

export interface GuidedLaunchUpdate {
  requestId: number
  state: GuidedLaunchState
  message: string
}

export interface WorkbenchNavItem {
  value: WorkbenchView
  label: string
  available: boolean
}

export interface WorkbenchEvidenceItem {
  label: string
  value: string
  tone?: 'default' | 'verified' | 'warning' | 'danger'
}
```

Change `ShowcaseScenario.id` in `workflowApi.ts` to `ShowcaseScenarioId` and import the type from `../types/workbench`. Type `scenarioView` in `App.vue` as `Record<ShowcaseScenarioId, GuidedWorkbenchView>` so a guided request can never target `customer`.

- [ ] **Step 4: Generate and pass launch requests from App**

Use a monotonic counter scoped to the mounted app instance:

```ts
const requestedLaunch = ref<ScenarioLaunchRequest | null>(null)
let nextLaunchRequestId = 0

function startScenario(id: ShowcaseScenarioId) {
  const view = scenarioView[id]
  requestedLaunch.value = {
    requestId: ++nextLaunchRequestId,
    mode: 'guided',
    scenarioId: id,
    view,
  }
  void showWorkbench(view)
}

function enterWorkbench() {
  requestedLaunch.value = null
  void showWorkbench('workflow')
}
```

Pass `:launch-request="requestedLaunch"` to `OperationsWorkbench`. Import `WorkbenchView`, `ScenarioLaunchRequest`, and `ShowcaseScenarioId` from `types/workbench.ts`; stop exporting `WorkbenchView` from `OperationsWorkbench.vue`.

- [ ] **Step 5: Run coordinator tests**

Run: `cd ui; npm run test:unit -- src/App.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ui/src/types/workbench.ts ui/src/services/workflowApi.ts ui/src/App.vue ui/src/App.spec.ts
git commit -m "feat(ui): distinguish guided and manual workbench entry"
```

---

### Task 2: Consume guided requests exactly once and prepare voice safely

**Files:**
- Create: `ui/src/composables/useGuidedLaunch.ts`
- Test: `ui/src/composables/useGuidedLaunch.spec.ts`
- Modify: `ui/src/composables/useVoiceSession.ts:46-60,131-154,375-387`
- Test: `ui/src/composables/useVoiceSession.spec.ts`

**Interfaces:**
- Consumes: `ScenarioLaunchRequest`, `ShowcaseScenarioId`, `GuidedLaunchUpdate` from Task 1.
- Produces: `useGuidedLaunch(options): void`.
- Produces: `VoiceSessionBinding.prepare(): Promise<void>`; it creates/connects a voice session but never requests microphone permission.

- [ ] **Step 1: Write failing one-shot launch tests**

Create tests using Vue `effectScope`, `ref`, and `nextTick`:

```ts
it('waits for activation and consumes a matching request once', async () => {
  const active = ref(false)
  const request = ref<ScenarioLaunchRequest | null>({
    requestId: 7,
    mode: 'guided',
    scenarioId: 'OPERATIONS_ANALYSIS',
    view: 'analytics',
  })
  const start = vi.fn(async () => ({ state: 'started' as const, message: '分析已启动' }))
  const updates: GuidedLaunchUpdate[] = []
  const scope = effectScope()
  scope.run(() => useGuidedLaunch({
    active: () => active.value,
    request: () => request.value,
    scenarioId: 'OPERATIONS_ANALYSIS',
    start,
    onUpdate: (update) => updates.push(update),
  }))

  await nextTick()
  expect(start).not.toHaveBeenCalled()
  active.value = true
  await flushPromises()
  expect(start).toHaveBeenCalledTimes(1)
  active.value = false
  await nextTick()
  active.value = true
  await flushPromises()
  expect(start).toHaveBeenCalledTimes(1)
  expect(updates.map((update) => update.state)).toEqual(['preparing', 'started'])
  scope.stop()
})
```

Add these two cases beside the activation case:

```ts
it('ignores a request for another scenario', async () => {
  const start = vi.fn(async () => ({ state: 'started' as const, message: 'started' }))
  const request = ref<ScenarioLaunchRequest | null>({
    requestId: 8, mode: 'guided', scenarioId: 'VOICE_ASSISTANT', view: 'voice',
  })
  const scope = effectScope()
  scope.run(() => useGuidedLaunch({
    active: () => true,
    request: () => request.value,
    scenarioId: 'OPERATIONS_ANALYSIS',
    start,
    onUpdate: vi.fn(),
  }))
  await flushPromises()
  expect(start).not.toHaveBeenCalled()
  scope.stop()
})

it('reports a rejected start as failed', async () => {
  const updates: GuidedLaunchUpdate[] = []
  const request = ref<ScenarioLaunchRequest | null>({
    requestId: 9, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow',
  })
  const scope = effectScope()
  scope.run(() => useGuidedLaunch({
    active: () => true,
    request: () => request.value,
    scenarioId: 'ALERT_WORKFLOW',
    start: async () => { throw new Error('后端不可用') },
    onUpdate: (update) => updates.push(update),
  }))
  await flushPromises()
  expect(updates.at(-1)).toEqual({ requestId: 9, state: 'failed', message: '后端不可用' })
  scope.stop()
})
```

- [ ] **Step 2: Run the guided launch test and verify failure**

Run: `cd ui; npm run test:unit -- src/composables/useGuidedLaunch.spec.ts`

Expected: FAIL because `useGuidedLaunch` does not exist.

- [ ] **Step 3: Implement the reusable request consumer**

```ts
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
```

- [ ] **Step 4: Write a failing voice preparation test**

In `useVoiceSession.spec.ts`, use the existing harness and assert preparation creates a session/WebSocket without touching the capture dependency:

```ts
it('prepares a backend session without requesting microphone access', async () => {
  const harness = makeHarness()
  await harness.binding.prepare()

  expect(harness.fakeCapture.started).toBe(0)
  expect(harness.binding.connectionPhase.value).toBe('connected')
})
```

- [ ] **Step 5: Implement `VoiceSessionBinding.prepare()`**

Add the method to the interface and returned binding:

```ts
async function prepare(): Promise<void> {
  const generation = lifecycleGeneration
  try {
    await ensureConnected(generation)
  } catch (cause) {
    if (generation !== lifecycleGeneration) return
    errorMessage.value = cause instanceof Error ? cause.message : String(cause)
    connectionPhase.value = 'failed'
    throw cause
  }
}
```

Do not call `requestMicrophone`, `startListening`, or `sendControl('START_INPUT')` from `prepare()`.

- [ ] **Step 6: Run focused tests**

Run: `cd ui; npm run test:unit -- src/composables/useGuidedLaunch.spec.ts src/composables/useVoiceSession.spec.ts`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add ui/src/composables/useGuidedLaunch.ts ui/src/composables/useGuidedLaunch.spec.ts ui/src/composables/useVoiceSession.ts ui/src/composables/useVoiceSession.spec.ts
git commit -m "feat(ui): add one-shot guided launch orchestration"
```

---

### Task 3: Wire guided launch into every homepage scenario

**Files:**
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Test: `ui/src/components/OperationsWorkbench.spec.ts`
- Modify: `ui/src/components/ExpertCollaborationPage.vue`
- Test: `ui/src/components/ExpertCollaborationPage.spec.ts`
- Modify: `ui/src/components/analytics/OperationsAnalysisPage.vue`
- Test: `ui/src/components/analytics/OperationsAnalysisPage.spec.ts`
- Modify: `ui/src/components/voice/VoiceAssistantPage.vue`
- Test: `ui/src/components/voice/VoiceAssistantPage.spec.ts`
- Modify: `ui/src/App.vue`
- Modify: `ui/src/App.spec.ts`

**Interfaces:**
- Consumes: `launchRequest?: ScenarioLaunchRequest | null` and `useGuidedLaunch`.
- Produces from each scene: `launch-status(update: GuidedLaunchUpdate)`.
- Produces from `OperationsWorkbench`: visible `guidedLaunchUpdate` state for the current request.

- [ ] **Step 1: Write failing page-level guided launch tests**

Add one focused case per scenario:

```ts
// ExpertCollaborationPage.spec.ts; declare beside the existing `polls` counter.
let collaborationPosts = 0

// In beforeEach's existing fetch stub:
if (init?.method === 'POST') {
  collaborationPosts += 1
  return jsonResponse({ runId: RUN_ID, statusUrl: '/status', eventsUrl: '/events' }, 202)
}

it('starts the default collaboration once for a matching guided request', async () => {
  const request = { requestId: 21, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration' } as const
  const wrapper = mount(ExpertCollaborationPage, {
    props: { trace: traceStub(), active: true, launchRequest: request },
    global: { stubs: collaborationElementStubs },
  })
  await new Promise((resolve) => setTimeout(resolve, 10))
  expect(collaborationPosts).toBe(1)
  await wrapper.setProps({ active: false })
  await wrapper.setProps({ active: true })
  await new Promise((resolve) => setTimeout(resolve, 10))
  expect(collaborationPosts).toBe(1)
  expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({ requestId: 21, state: 'started' })
  wrapper.unmount()
})
```

Extract the repeated existing Element Plus stubs into the test-local constant `collaborationElementStubs`; its values are the same `el-tag`, `el-input`, and `el-button` stubs already used in this spec. Reset `collaborationPosts = 0` in `beforeEach`.

```ts
// OperationsAnalysisPage.spec.ts
it('starts the verified default question for a matching guided request', async () => {
  let submittedQuestion = ''
  handler = (_url, init) => {
    if (init?.method === 'POST') {
      submittedQuestion = JSON.parse(String(init.body)).question
      return jsonResponse({ runId: RUN_ID }, 202)
    }
    return jsonResponse({ runId: RUN_ID, status: 'RUNNING', createdAt: '' })
  }
  const wrapper = mount(OperationsAnalysisPage, {
    props: {
      active: true,
      pollIntervalMs: 1,
      launchRequest: { requestId: 22, mode: 'guided', scenarioId: 'OPERATIONS_ANALYSIS', view: 'analytics' },
    },
  })
  await flush(2)
  expect(submittedQuestion).toBe('过去5天各楼宇能耗')
  expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({ requestId: 22, state: 'started' })
  wrapper.unmount()
})
```

```ts
// VoiceAssistantPage.spec.ts
it('prepares guided voice without toggling the microphone', async () => {
  const prepare = vi.fn(async () => undefined)
  binding.prepare = prepare
  const { wrapper } = mountPage(true, {
    requestId: 23,
    mode: 'guided',
    scenarioId: 'VOICE_ASSISTANT',
    view: 'voice',
  })
  await flushPromises()
  expect(prepare).toHaveBeenCalledTimes(1)
  expect(toggleMicrophone).not.toHaveBeenCalled()
  expect(wrapper.emitted('launch-status')?.at(-1)?.[0]).toMatchObject({ state: 'ready' })
})
```

Change the voice test helper signature to `mountPage(active = true, launchRequest: ScenarioLaunchRequest | null = null)` and pass `{ trace, active, launchRequest }` as component props.

Add this focused workflow case to `OperationsWorkbench.spec.ts`:

```ts
it('starts a guided alert workflow once across workbench reactivation', async () => {
  let workflowStarts = 0
  const originalEventSource = globalThis.EventSource
  globalThis.EventSource = class {
    onerror: ((event: Event) => void) | null = null
    constructor(_url: string | URL) {}
    addEventListener(): void {}
    close(): void {}
  } as unknown as typeof EventSource
  globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'POST') {
      workflowStarts += 1
      return new Response(JSON.stringify({
        workflowId: 'wf-guided-1', alertId: 'ALT-TEMP-001', status: 'RUNNING',
        diagnosis: null, approval: null, workOrder: null, errors: [], eventSequence: 1, riskReasons: [],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({
      knowledgeMode: 'mock', customerAnswerMode: 'mock', vectorStore: 'none',
      analyticsEnabled: true, collaborationEnabled: true, voiceEnabled: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch

  const wrapper = mount(OperationsWorkbench, {
    props: {
      active: true,
      initialView: 'workflow',
      launchRequest: { requestId: 20, mode: 'guided', scenarioId: 'ALERT_WORKFLOW', view: 'workflow' },
    },
    global: { stubs: operatorStubs },
  })
  await settleCapabilities()
  expect(workflowStarts).toBe(1)
  await wrapper.setProps({ active: false })
  await wrapper.setProps({ active: true })
  await settleCapabilities()
  expect(workflowStarts).toBe(1)
  wrapper.unmount()
  globalThis.EventSource = originalEventSource
})
```

Extend the workbench stub's emitted events with `retry-guided-launch`, then add this `App.spec.ts` case:

```ts
it('retries a failed guided launch with a fresh request id', async () => {
  const wrapper = mountApp()
  wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'OPERATIONS_ANALYSIS')
  await nextTick()
  const first = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest
  wrapper.getComponent(OperationsWorkbench).vm.$emit('retry-guided-launch', 'OPERATIONS_ANALYSIS')
  await nextTick()
  const retried = wrapper.getComponent(OperationsWorkbench).props('launchRequest') as ScenarioLaunchRequest
  expect(retried.requestId).toBeGreaterThan(first.requestId)
  expect(retried.scenarioId).toBe('OPERATIONS_ANALYSIS')
})
```

- [ ] **Step 2: Run scene tests and verify failure**

Run:

```bash
cd ui
npm run test:unit -- src/components/OperationsWorkbench.spec.ts src/components/ExpertCollaborationPage.spec.ts src/components/analytics/OperationsAnalysisPage.spec.ts src/components/voice/VoiceAssistantPage.spec.ts
```

Expected: FAIL because the scene props/events and guided watchers do not exist.

- [ ] **Step 3: Add the common props and events**

Use these exact contracts in the four consumers:

```ts
// OperationsWorkbench.vue
const props = withDefaults(defineProps<{
  initialView?: WorkbenchView
  active?: boolean
  launchRequest?: ScenarioLaunchRequest | null
}>(), { initialView: 'workflow', active: true, launchRequest: null })
const emit = defineEmits<{
  'back-to-showcase': []
  'retry-guided-launch': [scenarioId: ShowcaseScenarioId]
}>()

// ExpertCollaborationPage.vue
const props = withDefaults(defineProps<{
  trace: ExecutionTrace
  active?: boolean
  launchRequest?: ScenarioLaunchRequest | null
}>(), { active: true, launchRequest: null })
const emit = defineEmits<{ 'launch-status': [update: GuidedLaunchUpdate] }>()

// OperationsAnalysisPage.vue
const props = withDefaults(defineProps<{
  trace?: ExecutionTraceLike
  pollIntervalMs?: number
  active?: boolean
  launchRequest?: ScenarioLaunchRequest | null
}>(), { active: true, launchRequest: null })
const emit = defineEmits<{
  'run-started': [runId: string]
  'launch-status': [update: GuidedLaunchUpdate]
}>()

// VoiceAssistantPage.vue
const props = withDefaults(defineProps<{
  trace: ExecutionTrace
  active?: boolean
  launchRequest?: ScenarioLaunchRequest | null
}>(), { active: true, launchRequest: null })
const emit = defineEmits<{ 'launch-status': [update: GuidedLaunchUpdate] }>()
```

- [ ] **Step 4: Connect scenario-specific start handlers**

Use the existing real start functions; do not synthesize results:

```ts
// collaboration
useGuidedLaunch({
  active: () => props.active,
  request: () => props.launchRequest,
  scenarioId: 'EXPERT_COLLABORATION',
  start: async () => {
    await start(question.value)
    return { state: 'started', message: '专家协作已启动' }
  },
  onUpdate: (update) => emit('launch-status', update),
})
```

```ts
// analytics
const DEFAULT_GUIDED_QUESTION = '过去5天各楼宇能耗'
useGuidedLaunch({
  active: () => props.active,
  request: () => props.launchRequest,
  scenarioId: 'OPERATIONS_ANALYSIS',
  start: async () => {
    question.value = DEFAULT_GUIDED_QUESTION
    const started = waitForAnalysisStart()
    launch()
    await started
    return { state: 'started', message: '运营分析已启动' }
  },
  onUpdate: (update) => emit('launch-status', update),
})
```

Define `waitForAnalysisStart()` in the analytics page so “已启动” is emitted after the backend returns a run ID, without waiting for terminal polling:

```ts
function waitForAnalysisStart(): Promise<void> {
  return new Promise((resolve, reject) => {
    const stop = watch(
      [() => analysis.runId.value, () => analysis.phase.value],
      ([runId, phase]) => {
        if (runId) {
          stop()
          resolve()
        } else if (phase === 'failed') {
          stop()
          reject(new Error(analysis.error.value || '运营分析启动失败'))
        }
      },
      { flush: 'post' },
    )
  })
}
```

```ts
// voice
useGuidedLaunch({
  active: () => props.active,
  request: () => props.launchRequest,
  scenarioId: 'VOICE_ASSISTANT',
  start: async () => {
    await prepare()
    return { state: 'ready', message: '语音链路已就绪，请点击麦克风授权并开始提问' }
  },
  onUpdate: (update) => emit('launch-status', update),
})
```

For the workflow inside `OperationsWorkbench`, use the same helper with `scenarioId: 'ALERT_WORKFLOW'` and call the existing `launch()` method. After `await launch()`, throw `new Error(error.value || '告警工作流启动失败')` when `workflow.value` is still null; only then return the `started` update. Pass `active`, `launchRequest`, and `@launch-status="handleGuidedLaunchUpdate"` to each page.

- [ ] **Step 5: Render honest guided status**

Add a status region above the active scene:

```vue
<p
  v-if="guidedLaunchUpdate?.requestId === props.launchRequest?.requestId"
  class="guided-launch-status"
  :data-state="guidedLaunchUpdate.state"
  role="status"
>
  {{ guidedLaunchUpdate.message }}
</p>
```

On failure, render a “重新开始” button. `OperationsWorkbench` emits `retry-guided-launch` with the failed `scenarioId`; `App.vue` handles it by calling `startScenario(id)`, which generates a new monotonic request ID. Add an `App.spec.ts` case asserting the retried ID is greater than the failed ID; never mutate or reuse the consumed request object.

- [ ] **Step 6: Run all focused tests**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add ui/src/App.vue ui/src/App.spec.ts ui/src/components/OperationsWorkbench.vue ui/src/components/OperationsWorkbench.spec.ts ui/src/components/ExpertCollaborationPage.vue ui/src/components/ExpertCollaborationPage.spec.ts ui/src/components/analytics/OperationsAnalysisPage.vue ui/src/components/analytics/OperationsAnalysisPage.spec.ts ui/src/components/voice/VoiceAssistantPage.vue ui/src/components/voice/VoiceAssistantPage.spec.ts
git commit -m "feat(ui): start guided showcase scenarios"
```

---

### Task 4: Extract the homepage theme and build the immersive shell

**Files:**
- Create: `ui/src/styles/showcase-theme.css`
- Create: `ui/src/styles/theme-contract.spec.ts`
- Create: `ui/src/styles/workbench-primitives.css`
- Create: `ui/src/components/workbench/WorkbenchEvidenceRibbon.vue`
- Create: `ui/src/components/workbench/ImmersiveWorkbenchShell.vue`
- Create: `ui/src/components/workbench/ImmersiveWorkbenchShell.spec.ts`
- Create: `ui/src/components/workbench/immersive-workbench.css`
- Modify: `ui/src/main.ts`
- Modify: `ui/src/components/showcase/showcase-home.css:1-27`

**Interfaces:**
- Consumes: `WorkbenchView`, `WorkbenchNavItem`, `WorkbenchEvidenceItem`, `GuidedLaunchUpdate`.
- Produces: `ImmersiveWorkbenchShell` slots `default` and `rail`; emits `switch-view`, `update:role`, `back-to-showcase`, `retry-guided-launch`.
- Produces: global `--showcase-*` variables and backward-compatible aliases `--ink`, `--teal`, `--line`, `--surface-muted`, `--ink-soft`, `--accent` inside `.immersive-workbench` only.

- [ ] **Step 1: Write failing theme contract tests**

Create `theme-contract.spec.ts` that reads CSS from disk and verifies exact tokens:

```ts
const theme = readFileSync(resolve(process.cwd(), 'src/styles/showcase-theme.css'), 'utf8')

it.each([
  ['--showcase-graphite', '#06090f'],
  ['--showcase-graphite-2', '#0c111a'],
  ['--showcase-cyan', '#70e8ff'],
  ['--showcase-violet', '#8f5cff'],
  ['--showcase-ivory', '#fff0d2'],
  ['--showcase-amber', '#ffd27a'],
])('defines %s as %s', (token, value) => {
  expect(theme.replace(/\s+/g, '')).toContain(`${token}:${value}`)
})
```

Add an assertion that `showcase-home.css` no longer declares `--showcase-graphite:`.

- [ ] **Step 2: Write failing shell tests**

Mount the shell with two available nav items, two evidence items and default/rail slots. Assert:

```ts
expect(wrapper.get('header').classes()).toContain('immersive-workbench__topbar')
expect(wrapper.get('nav').attributes('aria-label')).toBe('场景导航')
expect(wrapper.get('[data-workbench-stage]').text()).toContain('stage content')
expect(wrapper.get('[data-workbench-rail]').text()).toContain('rail content')
expect(wrapper.findAll('[data-evidence-item]')).toHaveLength(2)
await wrapper.get('[data-workbench-view="analytics"]').trigger('click')
expect(wrapper.emitted('switch-view')).toEqual([['analytics']])
```

- [ ] **Step 3: Run tests and verify failure**

Run: `cd ui; npm run test:unit -- src/styles/theme-contract.spec.ts src/components/workbench/ImmersiveWorkbenchShell.spec.ts`

Expected: FAIL because the files do not exist.

- [ ] **Step 4: Extract the exact homepage tokens**

Create `showcase-theme.css`:

```css
:root {
  --showcase-graphite: #06090f;
  --showcase-graphite-2: #0c111a;
  --showcase-border: rgba(176, 190, 208, 0.28);
  --showcase-border-soft: rgba(176, 190, 208, 0.16);
  --showcase-cyan: #70e8ff;
  --showcase-cyan-soft: rgba(112, 232, 255, 0.14);
  --showcase-violet: #8f5cff;
  --showcase-violet-soft: rgba(143, 92, 255, 0.32);
  --showcase-ivory: #fff0d2;
  --showcase-muted: #98a4b6;
  --showcase-amber: #ffd27a;
  --showcase-glass: rgba(4, 7, 12, 0.78);
  --showcase-glass-strong: rgba(4, 7, 12, 0.9);
  color: var(--showcase-ivory);
  background: var(--showcase-graphite);
  font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
  font-synthesis: none;
}

* { box-sizing: border-box; }
html, body, #app { min-width: 320px; min-height: 100%; margin: 0; }
body { min-height: 100vh; background: var(--showcase-graphite); }
button, input, textarea, select { font: inherit; }
:focus-visible { outline: 3px solid rgba(112, 232, 255, 0.95); outline-offset: 3px; }
```

Import it from `main.ts` before mounting the app. Remove the token block from `.showcase-home` without changing the remaining homepage rules.

- [ ] **Step 5: Implement the shell and evidence ribbon**

`ImmersiveWorkbenchShell` must render this structure:

```vue
<div class="immersive-workbench" data-testid="immersive-workbench-shell">
  <header class="immersive-workbench__topbar">
    <div class="immersive-workbench__brand">
      <Monitor aria-hidden="true" />
      <div><span>智慧园区 · 智能运营</span><strong>智慧园区智能运营中心</strong></div>
    </div>
    <nav class="immersive-workbench__nav" aria-label="场景导航">
      <button
        v-for="item in navItems.filter((candidate) => candidate.available)"
        :key="item.value"
        type="button"
        :class="{ active: item.value === activeView }"
        :data-workbench-view="item.value"
        @click="emit('switch-view', item.value)"
      >{{ item.label }}</button>
    </nav>
    <div class="immersive-workbench__actions">
      <el-select :model-value="role" aria-label="演示角色" @update:model-value="emit('update:role', $event)">
        <el-option label="查看者" value="VIEWER" />
        <el-option label="操作员" value="OPERATOR" />
        <el-option label="审批人" value="APPROVER" />
        <el-option label="客服坐席" value="CUSTOMER_AGENT" />
        <el-option label="管理员" value="ADMIN" />
      </el-select>
      <button type="button" data-workbench-action="back-to-showcase" @click="emit('back-to-showcase')">返回展示首页</button>
    </div>
    <div v-if="guidedLaunch" class="guided-launch-status" :data-state="guidedLaunch.state" role="status">
      <span>{{ guidedLaunch.message }}</span>
      <button v-if="guidedLaunch.state === 'failed'" type="button" @click="emit('retry-guided-launch')">重新开始</button>
    </div>
  </header>
  <div class="immersive-workbench__workspace">
    <section class="immersive-workbench__stage" data-workbench-stage><slot /></section>
    <details class="immersive-workbench__rail" data-workbench-rail :open="railPriority">
      <summary>执行轨迹</summary>
      <div class="immersive-workbench__rail-content"><slot name="rail" /></div>
    </details>
  </div>
  <WorkbenchEvidenceRibbon :items="evidenceItems" />
</div>
```

Import the existing Element Plus `Monitor` icon. Type props as `activeView: WorkbenchView`, `role: DemoRole`, `navItems: WorkbenchNavItem[]`, `evidenceItems: WorkbenchEvidenceItem[]`, `guidedLaunch?: GuidedLaunchUpdate | null`, and `railPriority?: boolean`; default `guidedLaunch` to null and `railPriority` to false.

The shell receives available navigation items rather than backend capability objects. The role selector emits `update:role`; it does not own business state or call APIs. The `railPriority` boolean is true while the workflow waits for approval. Desktop CSS always shows `.immersive-workbench__rail-content` and hides `<summary>`; mobile CSS uses native `<details>` behavior, so only priority states are open by default.

`WorkbenchEvidenceRibbon` renders a labelled `<footer>` containing a `<ul>`; each item has `data-evidence-item` and `data-tone`.

- [ ] **Step 6: Implement exact shell layout rules**

Use these non-negotiable rules in `immersive-workbench.css`:

```css
.immersive-workbench {
  --ink: var(--showcase-ivory);
  --ink-soft: var(--showcase-muted);
  --teal: var(--showcase-cyan);
  --accent: var(--showcase-cyan);
  --line: var(--showcase-border-soft);
  --surface-muted: rgba(12, 17, 26, 0.72);
  min-height: 100vh;
  color: var(--showcase-ivory);
  background:
    radial-gradient(circle at 18% 8%, rgba(112, 232, 255, 0.1), transparent 30%),
    radial-gradient(circle at 78% 18%, rgba(143, 92, 255, 0.14), transparent 28%),
    var(--showcase-graphite);
}
.immersive-workbench__workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 380px);
  gap: 18px;
  padding: 18px 24px;
  align-items: start;
}
.immersive-workbench__stage {
  position: relative;
  min-width: 0;
  isolation: isolate;
}
.immersive-workbench__stage::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  opacity: 0.14;
  background: linear-gradient(rgba(6, 9, 15, 0.62), rgba(6, 9, 15, 0.92)),
    url('../../assets/showcase/evidence-theater-park.png') 47% center / cover no-repeat;
}
.immersive-workbench__rail {
  position: sticky;
  top: 18px;
  max-height: calc(100vh - 132px);
  overflow: hidden;
}
@media (min-width: 1280px) {
  .immersive-workbench__rail > summary { display: none; }
  .immersive-workbench__rail:not([open]) > .immersive-workbench__rail-content { display: block; }
}
/* Put this shared rule in workbench-primitives.css, not the shell file. */
.immersive-workbench .panel {
  border: 1px solid var(--showcase-border);
  border-radius: 18px;
  color: var(--showcase-ivory);
  background: linear-gradient(180deg, rgba(8, 12, 20, 0.93), rgba(5, 8, 14, 0.86));
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(18px);
}
@media (max-width: 1279px) {
  .immersive-workbench__workspace { grid-template-columns: 1fr; }
  .immersive-workbench__rail { position: static; max-height: 440px; }
}
@media (max-width: 767px) {
  .immersive-workbench__workspace { padding: 12px; }
  .immersive-workbench__rail { max-height: none; }
}
@media (prefers-reduced-motion: reduce) {
  .immersive-workbench *, .immersive-workbench *::before, .immersive-workbench *::after {
    scroll-behavior: auto !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

Scope every primitive selector—including `.panel`, `.hero-row`, `.section-heading`, buttons and forms—under `.immersive-workbench`. Scope Element Plus custom properties under the same root; set text, fill, border, primary, warning and danger variables to the shared theme values.

- [ ] **Step 7: Run theme and shell tests**

Run the command from Step 3.

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add ui/src/main.ts ui/src/styles/showcase-theme.css ui/src/styles/theme-contract.spec.ts ui/src/styles/workbench-primitives.css ui/src/components/showcase/showcase-home.css ui/src/components/workbench
git commit -m "feat(ui): add shared immersive workbench design system"
```

---

### Task 5: Integrate the shell, persistent rail, and evidence ribbon

**Files:**
- Modify: `ui/src/components/OperationsWorkbench.vue:1-263`
- Modify: `ui/src/components/OperationsWorkbench.spec.ts`
- Modify: `ui/src/layout.spec.ts`

**Interfaces:**
- Consumes: `ImmersiveWorkbenchShell`, `WorkbenchNavItem`, `WorkbenchEvidenceItem`, `GuidedLaunchUpdate`.
- Produces: stable shell around all five existing `v-show` scene roots; the execution rail remains mounted across scene changes.

- [ ] **Step 1: Replace the old sibling-layout test with a shell contract test**

```ts
it('keeps every scenario inside one stable stage beside the persistent rail', async () => {
  const wrapper = mountWorkbench()
  await settleCapabilities()

  expect(wrapper.findAll('[data-workbench-stage] > .main-content')).toHaveLength(5)
  expect(wrapper.get('[data-workbench-rail] .global-rail').exists()).toBe(true)
  const shell = wrapper.get('[data-testid="immersive-workbench-shell"]')
  await wrapper.get('[data-workbench-view="analytics"]').trigger('click')
  expect(wrapper.get('[data-testid="immersive-workbench-shell"]').element).toBe(shell.element)
})
```

Update `layout.spec.ts` to read `components/workbench/immersive-workbench.css` and assert the exact 340–380px desktop rail and 1279px stacking breakpoint.

- [ ] **Step 2: Run tests and verify failure**

Run: `cd ui; npm run test:unit -- src/components/OperationsWorkbench.spec.ts src/layout.spec.ts`

Expected: FAIL because the old `.workspace` structure is still present.

- [ ] **Step 3: Compute navigation and evidence data in OperationsWorkbench**

```ts
const navItems = computed<WorkbenchNavItem[]>(() => [
  { value: 'workflow', label: '告警工作流', available: true },
  { value: 'customer', label: '园区客服', available: true },
  { value: 'voice', label: '实时语音', available: capabilities.value?.voiceEnabled === true },
  { value: 'collaboration', label: '专家协作', available: capabilities.value?.collaborationEnabled === true },
  { value: 'analytics', label: '运营分析', available: capabilities.value?.analyticsEnabled === true },
])

const evidenceItems = computed<WorkbenchEvidenceItem[]>(() => [
  { label: '场景', value: navItems.value.find((item) => item.value === activeView.value)?.label ?? '告警工作流' },
  { label: '执行轨迹', value: trace.status.value === 'streaming' ? '实时同步' : statusLabelForTrace(trace.status.value), tone: trace.status.value === 'failed' ? 'danger' : 'verified' },
  { label: '知识检索', value: capabilityLabels.value?.knowledge ?? '检查中' },
  { label: '数据模式', value: '真实只读数据', tone: 'verified' },
])
```

Use a dedicated `statusLabelForTrace` map for `idle`, `streaming`, `completed`, `failed`, and `interrupted`; do not reuse workflow status labels.

- [ ] **Step 4: Wrap all scenes with the shell**

Replace the old header/workspace/footer with:

```vue
<ImmersiveWorkbenchShell
  :active-view="activeView"
  :role="role"
  :nav-items="navItems"
  :evidence-items="evidenceItems"
  :guided-launch="guidedLaunchUpdate"
  :rail-priority="needsApproval"
  @switch-view="activeView = $event"
  @update:role="role = $event"
  @back-to-showcase="emit('back-to-showcase')"
  @retry-guided-launch="retryGuidedLaunch"
>
  <template #rail>
    <ExecutionTraceRail class="global-rail" :events="trace.events.value" :status="trace.status.value" :error="trace.error.value" />
  </template>
</ImmersiveWorkbenchShell>
```

Move the five existing `analytics`, `customer`, `voice`, `collaboration`, and `workflow` `<main v-show>` nodes from current lines 180–251 between the shell's opening tag and `<template #rail>` without changing them to `v-if`; analysis state, collaboration polling state and voice teardown semantics rely on mounted component instances. Implement retry forwarding exactly as:

```ts
function retryGuidedLaunch(): void {
  if (props.launchRequest) emit('retry-guided-launch', props.launchRequest.scenarioId)
}
```

- [ ] **Step 5: Run shell integration tests**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ui/src/components/OperationsWorkbench.vue ui/src/components/OperationsWorkbench.spec.ts ui/src/layout.spec.ts
git commit -m "refactor(ui): move workbench into immersive shell"
```

---

### Task 6: Migrate workflow and customer service to the shared dark theme

**Files:**
- Create: `ui/src/styles/workflow.css`
- Create: `ui/src/components/customer-service.css`
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/components/CustomerServiceConsole.vue`
- Modify: `ui/src/styles/theme-contract.spec.ts`
- Modify/Delete: `ui/src/styles.css`

**Interfaces:**
- Consumes: shared `.panel`, `.hero-row`, `.section-heading`, theme variables and Element Plus variables.
- Produces: workflow/customer styles without page-level light backgrounds.

- [ ] **Step 1: Add failing legacy-palette tests**

Read `workflow.css` and `customer-service.css`; assert both exist and reject these exact legacy surfaces:

```ts
const forbiddenLegacySurfaces = [
  '#edf3f2', '#f0f7f5', '#f9fbfa', '#f3faf8', '#eef8f5',
  'rgba(251,253,252,.94)', 'rgba(255,255,255,.92)',
]

for (const color of forbiddenLegacySurfaces) {
  expect(compact(workflowCss)).not.toContain(color)
  expect(compact(customerCss)).not.toContain(color)
}
```

Also assert `.workflow-node .node-inner` and `.chat-message.user p` use a dark/glass variable rather than `background:white` or `background:#fff`.

- [ ] **Step 2: Run the contract test and verify failure**

Run: `cd ui; npm run test:unit -- src/styles/theme-contract.spec.ts`

Expected: FAIL because the split files do not exist.

- [ ] **Step 3: Move workflow selector groups into `workflow.css`**

Move these complete groups out of `styles.css`: `.dashboard-grid` through `.helper`; `.summary-content` through `.empty-orbit`; `.lower-grid` through `.vue-flow__edge-path`; `.timeline-panel` through `.event-body small`; `.approval-panel` through `.error-banner`; `.demo-console`; `.result-panel`, `.result-header`, `.result-columns`, `.result-column`, `.result-footer`, and their responsive rules.

Apply this exact surface mapping while preserving dimensions and state colors:

```css
.alert-card,
.node-inner,
.summary-stats,
.selected-alert,
.demo-console {
  color: var(--showcase-ivory);
  background: rgba(8, 12, 20, 0.72);
  border-color: var(--showcase-border-soft);
}
.alert-card.active {
  border-color: var(--showcase-cyan);
  background: var(--showcase-cyan-soft);
  box-shadow: inset 3px 0 var(--showcase-cyan), 0 0 26px rgba(112, 232, 255, 0.12);
}
.workflow-id {
  color: var(--showcase-ivory);
  background: linear-gradient(135deg, rgba(112, 232, 255, 0.14), rgba(143, 92, 255, 0.16));
}
.approval-panel {
  border-color: rgba(255, 210, 122, 0.38);
  background: linear-gradient(180deg, rgba(35, 27, 13, 0.78), rgba(8, 12, 20, 0.9));
}
```

Use `var(--showcase-muted)` for helper/meta text, cyan for running, violet for selection, amber for waiting, and `#ff8f84` for failed text on dark backgrounds.

- [ ] **Step 4: Move customer selector groups into `customer-service.css`**

Move `.customer-main` through `.knowledge-admin` and the customer responsive rules. Use:

```css
.suggestion,
.chat-message p,
.knowledge-citations,
.ticket-table article {
  color: var(--showcase-ivory);
  border-color: var(--showcase-border-soft);
  background: rgba(12, 17, 26, 0.74);
}
.chat-message.user p {
  color: var(--showcase-graphite);
  background: linear-gradient(135deg, var(--showcase-cyan), #9befff);
}
.privacy-note,
.retrieval-trace {
  border-left-color: var(--showcase-cyan);
  background: var(--showcase-cyan-soft);
}
.ticket-strip,
.risk-reason {
  color: var(--showcase-amber);
  border-color: rgba(255, 210, 122, 0.38);
  background: rgba(255, 210, 122, 0.08);
}
```

Import this file from `CustomerServiceConsole.vue`; import `workbench-primitives.css` and `workflow.css` from `OperationsWorkbench.vue`.

- [ ] **Step 5: Remove migrated rules and run tests**

Delete the migrated selector groups from `styles.css`. If no selectors remain after Tasks 6–7, delete the file; until then, leave only unmigrated collaboration/voice groups.

Run:

```bash
cd ui
npm run test:unit -- src/styles/theme-contract.spec.ts src/components/OperationsWorkbench.spec.ts
npm run typecheck
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ui/src/styles/workflow.css ui/src/components/customer-service.css ui/src/components/OperationsWorkbench.vue ui/src/components/CustomerServiceConsole.vue ui/src/styles/theme-contract.spec.ts ui/src/styles.css
git commit -m "feat(ui): theme workflow and customer scenes"
```

---

### Task 7: Migrate collaboration, voice, and execution trace surfaces

**Files:**
- Create: `ui/src/components/expert-collaboration.css`
- Create: `ui/src/components/voice/voice-assistant.css`
- Modify: `ui/src/components/ExpertCollaborationPage.vue`
- Modify: `ui/src/components/voice/VoiceAssistantPage.vue`
- Modify: `ui/src/components/execution/execution-rail.css`
- Modify: `ui/src/styles/theme-contract.spec.ts`
- Delete: `ui/src/styles.css`

**Interfaces:**
- Consumes: shared theme variables and primitives.
- Produces: scene-local CSS with no dependency on the deleted monolithic stylesheet.

- [ ] **Step 1: Extend failing palette and ownership tests**

Assert that collaboration, voice, and execution CSS exist; none contains the legacy surface list from Task 6. Assert no `.collaboration-` or `.voice-` selectors remain in `ui/src/styles.css`, then assert `styles.css` does not exist once migration completes.

- [ ] **Step 2: Run the contract test and verify failure**

Run: `cd ui; npm run test:unit -- src/styles/theme-contract.spec.ts`

Expected: FAIL while the old selectors remain in `styles.css`.

- [ ] **Step 3: Move and retheme collaboration selectors**

Move `.collaboration-main` through `.synthesis-confidence`, all `.expert-*`, `.handoff-*`, `.evidence-list`, and collaboration media rules into `expert-collaboration.css`. Apply:

```css
.expert-card,
.expert-assignment,
.plan-summary,
.handoff-detail {
  color: var(--showcase-ivory);
  border-color: var(--showcase-border-soft);
  background: rgba(8, 12, 20, 0.76);
}
.expert-mark,
.evidence-list span {
  color: var(--showcase-cyan);
  background: var(--showcase-cyan-soft);
}
.expert-card-running { border-top-color: var(--showcase-cyan); }
.expert-card-supported { border-top-color: #63e6b2; }
.expert-card-insufficient_evidence { border-top-color: var(--showcase-amber); }
.expert-card-failed { border-top-color: #ff8f84; }
```

Import the file from `ExpertCollaborationPage.vue` so `ExpertCard.vue` inherits its scene styles without another global import.

- [ ] **Step 4: Move and retheme voice selectors**

Move every `.voice-` rule into `voice-assistant.css`. Preserve microphone button geometry and state transitions, but use:

```css
.voice-mic-panel,
.voice-transcript-panel,
.voice-answer-panel,
.voice-side-panel {
  background: linear-gradient(180deg, rgba(8, 12, 20, 0.9), rgba(5, 8, 14, 0.82));
}
.voice-mic-button {
  color: var(--showcase-ivory);
  border-color: rgba(112, 232, 255, 0.46);
  background: radial-gradient(circle, rgba(112, 232, 255, 0.2), rgba(143, 92, 255, 0.12));
}
.voice-mic-button.active,
.voice-mic-button.interruptible {
  box-shadow: 0 0 42px rgba(112, 232, 255, 0.28);
}
.voice-error {
  color: #ffb1aa;
  border-color: rgba(255, 143, 132, 0.4);
  background: rgba(90, 25, 21, 0.3);
}
```

Replace the current emoji microphone with the existing Element Plus `Microphone` icon component; do not add SVG or CSS art.

- [ ] **Step 5: Retheme the execution rail**

Replace light cards and payload surfaces with:

```css
.execution-event-card {
  color: var(--showcase-ivory);
  border-color: var(--showcase-border-soft);
  border-left-color: rgba(112, 232, 255, 0.58);
  background: rgba(8, 12, 20, 0.82);
}
.execution-event-card.is-error,
.trace-error {
  color: #ffb1aa;
  border-color: rgba(255, 143, 132, 0.4);
  background: rgba(90, 25, 21, 0.3);
}
.payload {
  color: #c8d3e0;
  border-color: var(--showcase-border-soft);
  background: rgba(112, 232, 255, 0.06);
}
.trace-list { scrollbar-color: rgba(112, 232, 255, 0.38) transparent; }
```

- [ ] **Step 6: Delete the monolithic stylesheet and run focused tests**

Delete `ui/src/styles.css` after confirming no component imports it and `rg "styles\.css" ui/src` returns no matches.

Run:

```bash
cd ui
npm run test:unit -- src/styles/theme-contract.spec.ts src/components/ExpertCollaborationPage.spec.ts src/components/voice/VoiceAssistantPage.spec.ts src/components/execution/ExecutionTraceRail.spec.ts src/components/execution/ExecutionEventCard.spec.ts
npm run typecheck
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add ui/src/components/expert-collaboration.css ui/src/components/voice/voice-assistant.css ui/src/components/ExpertCollaborationPage.vue ui/src/components/voice/VoiceAssistantPage.vue ui/src/components/execution/execution-rail.css ui/src/styles/theme-contract.spec.ts ui/src/styles.css
git commit -m "feat(ui): theme collaboration voice and execution trace"
```

---

### Task 8: Apply a real dark ECharts theme and finish responsive/accessibility verification

**Files:**
- Modify: `ui/src/components/analytics/analytics.css`
- Modify: `ui/src/components/analytics/AnalyticsChart.vue`
- Test: `ui/src/components/analytics/AnalyticsChart.spec.ts`
- Test: `ui/src/components/analytics/OperationsAnalysisPage.spec.ts`
- Modify: `ui/src/styles/theme-contract.spec.ts`
- Modify: `ui/src/components/workbench/immersive-workbench.css`
- Modify: `ui/src/styles/workbench-primitives.css`

**Interfaces:**
- Consumes: shared color variables.
- Produces: `withDarkTheme(option): Record<string, unknown>` applied to every non-KPI ECharts option.
- Produces: verified desktop, tablet and mobile layouts with reduced-motion support.

- [ ] **Step 1: Write a failing ECharts dark-theme test**

Extend the existing mocked `setOption` test:

```ts
it('applies the immersive dark theme to axes, tooltip and series', () => {
  const wrapper = mount(AnalyticsChart, {
    props: {
      chart: chart({ type: 'LINE', xField: 'building', yFields: ['energy_kwh'], seriesField: '-' }),
      columns: ['building', 'energy_kwh'],
      rows: [['A1', 100], ['A2', 120]],
    },
  })
  const option = setOption.mock.calls.at(-1)?.[0]
  expect(option.backgroundColor).toBe('transparent')
  expect(option.textStyle.color).toBe('#c8d3e0')
  expect(option.xAxis.axisLabel.color).toBe('#98a4b6')
  expect(option.series[0].itemStyle.color).toBe('#70e8ff')
  wrapper.unmount()
})
```

- [ ] **Step 2: Run analytics tests and verify failure**

Run: `cd ui; npm run test:unit -- src/components/analytics/AnalyticsChart.spec.ts src/components/analytics/OperationsAnalysisPage.spec.ts`

Expected: FAIL because options have no dark theme.

- [ ] **Step 3: Apply a shared option decorator**

Add a deterministic decorator and call it in `render()`:

```ts
const CHART_COLORS = ['#70e8ff', '#8f5cff', '#ffd27a', '#63e6b2', '#ff8f84']

function withDarkTheme(option: Record<string, unknown>): Record<string, unknown> {
  const axisStyle = {
    axisLabel: { color: '#98a4b6' },
    axisLine: { lineStyle: { color: 'rgba(176, 190, 208, 0.28)' } },
    splitLine: { lineStyle: { color: 'rgba(176, 190, 208, 0.12)' } },
    nameTextStyle: { color: '#c8d3e0' },
  }
  const decorateAxis = (axis: unknown) => Array.isArray(axis)
    ? axis.map((item) => ({ ...axisStyle, ...(item as object) }))
    : axis ? { ...axisStyle, ...(axis as object) } : axis
  const series = Array.isArray(option.series)
    ? option.series.map((item, index) => {
        const current = item as Record<string, unknown>
        const seriesType = String(current.type ?? '')
        if (seriesType === 'heatmap') return current
        const color = CHART_COLORS[index % CHART_COLORS.length]
        const itemStyle = typeof current.itemStyle === 'object' && current.itemStyle ? current.itemStyle : {}
        const lineStyle = typeof current.lineStyle === 'object' && current.lineStyle ? current.lineStyle : {}
        return {
          ...current,
          itemStyle: { ...itemStyle, color },
          ...(seriesType === 'line'
            ? { lineStyle: { ...lineStyle, color } }
            : {}),
        }
      })
    : option.series
  return {
    ...option,
    backgroundColor: 'transparent',
    color: CHART_COLORS,
    textStyle: { color: '#c8d3e0' },
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(4, 7, 12, 0.94)', borderColor: 'rgba(112, 232, 255, 0.35)', textStyle: { color: '#fff0d2' } },
    xAxis: decorateAxis(option.xAxis),
    yAxis: decorateAxis(option.yAxis),
    series,
  }
}
```

Use these exact type-specific additions while preserving all backend-derived fields and data arrays:

```ts
// heatmapOption/calendarOption, before return
const max = Math.max(...props.rows.map((row) => Number(row[valueIndex]) || 0), 0)

// calendarOption, before return
const range = dates.length > 1 ? [dates[0], dates[dates.length - 1]] : (dates[0] ?? '')

// heatmapOption and calendarOption
visualMap: {
  min: 0,
  max,
  calculable: true,
  textStyle: { color: '#98a4b6' },
  inRange: { color: ['#172334', '#275c73', '#70e8ff', '#ffd27a'] },
}

// calendarOption
calendar: {
  range,
  itemStyle: { color: 'rgba(8, 12, 20, 0.72)', borderColor: 'rgba(176, 190, 208, 0.16)' },
  splitLine: { lineStyle: { color: 'rgba(176, 190, 208, 0.28)' } },
  dayLabel: { color: '#98a4b6' },
  monthLabel: { color: '#c8d3e0' },
  yearLabel: { color: '#fff0d2' },
}

// gaugeOption series item
axisLine: { lineStyle: { color: [[1, 'rgba(176, 190, 208, 0.2)']] } },
axisLabel: { color: '#98a4b6' },
detail: { color: '#70e8ff' },
title: { color: '#c8d3e0' },
```

- [ ] **Step 4: Retheme analytics CSS**

Keep current layout dimensions, but replace light surfaces with shared variables:

```css
.question-row input,
.selection-row select,
.result-table th,
.result-table td,
.analytics-kpi {
  color: var(--showcase-ivory);
  border-color: var(--showcase-border-soft);
  background: rgba(8, 12, 20, 0.76);
}
.question-row button,
.clarify-panel button {
  color: var(--showcase-graphite);
  border-color: var(--showcase-cyan);
  background: linear-gradient(135deg, var(--showcase-cyan), #9befff);
}
.analytics-presets button {
  color: var(--showcase-cyan);
  border-color: rgba(112, 232, 255, 0.28);
  background: var(--showcase-cyan-soft);
}
.analytics-error,
.failed-panel {
  color: #ffb1aa;
  border-color: rgba(255, 143, 132, 0.4);
  background: rgba(90, 25, 21, 0.3);
}
```

- [ ] **Step 5: Complete responsive and accessibility contracts**

Extend `theme-contract.spec.ts` to assert:

```ts
expect(shellCss).toContain('@media(max-width:1279px)')
expect(shellCss).toContain('@media(max-width:767px)')
expect(shellCss).toContain('@media(prefers-reduced-motion:reduce)')
expect(themeCss).toContain(':focus-visible')
```

Add these exact mobile rules. The shell's `railPriority` prop keeps waiting approval expanded; all other mobile rails use native `<details>` disclosure behavior.

```css
@media (max-width: 767px) {
  .immersive-workbench__topbar { grid-template-columns: 1fr; padding: 14px 12px; }
  .immersive-workbench__nav { width: 100%; overflow-x: auto; white-space: nowrap; scrollbar-width: thin; }
  .immersive-workbench__actions { width: 100%; justify-content: space-between; }
  .immersive-workbench .hero-row { display: grid; gap: 18px; }
  .immersive-workbench .hero-row h2 { font-size: clamp(32px, 11vw, 44px); }
  .immersive-workbench .hero-metrics { display: grid; grid-template-columns: 1fr; }
  .immersive-workbench .collaboration-form,
  .immersive-workbench .question-row,
  .immersive-workbench .chat-composer,
  .immersive-workbench .approval-form { grid-template-columns: 1fr; }
  .immersive-workbench .result-panel,
  .immersive-workbench .analytics-page { min-width: 0; overflow-x: auto; }
  .immersive-workbench__rail > summary { display: list-item; color: var(--showcase-cyan); cursor: pointer; }
}
```

- [ ] **Step 6: Run the complete frontend suite**

Run:

```bash
cd ui
npm run typecheck
npm run test:unit
npm run build
```

Expected: all commands exit 0.

- [ ] **Step 7: Run full project and Docker verification**

Run from repository root:

```powershell
.\mvnw.cmd test
docker compose --profile analytics up -d --build
docker compose --profile analytics ps
powershell -ExecutionPolicy Bypass -File .\scripts\verify-showcase.ps1
```

Expected:

- Maven exits 0.
- Backend, frontend, PostgreSQL and time parser containers report healthy/running.
- Showcase verification reports READY for `EXPERT_COLLABORATION` and `OPERATIONS_ANALYSIS`.

- [ ] **Step 8: Perform same-viewport visual QA in the in-app browser**

Use the Codex in-app browser, not Playwright CLI. Capture and compare:

1. `1440x1000` homepage.
2. `1440x1000` manual workbench entry on workflow.
3. `1440x1000` customer, voice, collaboration and analytics scenes.
4. `1024x768` workbench with the rail stacked below the stage.
5. `390x844` workbench with scrollable navigation and collapsible rail.

For every capture verify: no large white/mint surface, no cropped heading, no page-level horizontal overflow, persistent desktop rail, readable chart labels, visible keyboard focus, and honest guided launch status. Test both entry paths: manual entry must remain idle; guided entry must start once; voice guided entry must not open a microphone permission prompt until the mic button is clicked.

- [ ] **Step 9: Commit**

```bash
git add ui/src/components/analytics/analytics.css ui/src/components/analytics/AnalyticsChart.vue ui/src/components/analytics/AnalyticsChart.spec.ts ui/src/components/analytics/OperationsAnalysisPage.spec.ts ui/src/components/workbench/immersive-workbench.css ui/src/styles/workbench-primitives.css ui/src/styles/theme-contract.spec.ts
git commit -m "feat(ui): finish immersive analytics and responsive polish"
```

---

## Final Review Checklist

- [ ] `start-scenario` creates a new guided request ID and triggers only the selected live scenario.
- [ ] `enter-workbench` clears guided state and opens an idle workflow view.
- [ ] Voice guided mode connects without requesting microphone permission.
- [ ] Workbench shell stays mounted across all five `v-show` scenes.
- [ ] Right rail is sticky at `>= 1280px` and stacked below at smaller widths.
- [ ] Homepage and workbench consume the same theme variables.
- [ ] `ui/src/styles.css` is removed and no imports remain.
- [ ] Workflow, customer, collaboration, voice, analytics and execution rail contain no legacy light surface colors.
- [ ] ECharts labels, axes, tooltip, calendar, gauge and series are readable on dark backgrounds.
- [ ] Focus, loading, empty, failure, approval and reduced-motion states are verified.
- [ ] Frontend typecheck, unit tests, build, Maven tests, Docker health and showcase verification all pass.
