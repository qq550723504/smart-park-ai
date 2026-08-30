# Showcase Home Option 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the selected “Evidence Theater” customer showcase home as the default Smart Park UI surface, driven only by the truthful showcase catalog and preserving the existing operations workbench.

**Architecture:** Reduce `App.vue` to a surface coordinator. Move the current operator-only state and scenario views into `OperationsWorkbench.vue`, then add a focused `ShowcaseHome.vue` that loads `/api/showcase/scenarios`, selects only a verified `READY`/`live` task, and maps its CTA into the existing scenario screen without duplicating orchestration. Recreate the selected visual using a generated, text-free park raster plus accessible Vue UI and a dedicated stylesheet.

**Tech Stack:** Vue 3.5, TypeScript 5.9, Vite 7, Vitest 4, Element Plus 2.11, `@element-plus/icons-vue` 2.3.2, existing Spring Boot showcase catalog.

**Spec:** `docs/superpowers/specs/2026-08-30-smart-park-agent-showcase-ui-design.md`

**Selected visual:** second displayed Image Gen result from the 2026-08-30 ideation set, preserved as `docs/design-references/2026-08-30-showcase-option-2.png` during Task 3.

## Global Constraints

- The customer showcase is the default surface; the existing operations workbench remains available without changing its REST, SSE, WebSocket, workflow, approval, or scenario ownership.
- A task is selectable only when the server returns both `status === 'READY'` and `live === true`; the frontend never manufactures readiness.
- `NOT_READY`, `DISABLED`, unknown-network, and empty-ready states cannot start a scenario and must retain a safe reason.
- No Mock, timer animation, static result, or pre-recorded output may look like a live Agent run.
- Starting from the showcase only routes into the existing scenario UI; it does not duplicate or bypass scenario execution logic.
- The design uses a real raster park asset and `@element-plus/icons-vue`; no handcrafted SVG, CSS drawing, emoji, or text glyph is used as a visible asset or icon.
- Exact primary copy: “智慧园区 Agent 体验中心”, “从真实问题开始，看见 Agent 如何形成可信结论”, and “开始现场演示”.
- Desktop baseline is 1440 × 1024; below 1250px the task panel moves below the stage, and below 760px content follows a single-column reading order.
- Status is expressed by text and icon as well as color; updates use `aria-live`; motion respects `prefers-reduced-motion`.
- Existing operator scenario state remains mounted while switching internal workbench views, and is preserved when the workbench is temporarily hidden by the customer surface.

## File Structure and Responsibilities

- `ui/src/App.vue`: owns only `showcase | workbench` surface state and scenario-to-workbench routing.
- `ui/src/App.spec.ts`: verifies default customer surface, workbench entry, scenario mapping, and cached workbench state.
- `ui/src/components/OperationsWorkbench.vue`: owns all current operator navigation, role, alert, approval, feedback, scenario pages, and execution rail.
- `ui/src/components/OperationsWorkbench.spec.ts`: preserves the current operator view/capability behavior through the extraction.
- `ui/src/components/showcase/ShowcaseHome.vue`: loads and renders the truthful catalog, selected task, trust boundary, empty/error states, and emits navigation intents.
- `ui/src/components/showcase/ShowcaseHome.spec.ts`: verifies readiness precedence, disabled states, errors, selection, and emitted scenario IDs.
- `ui/src/components/showcase/showcase-home.css`: implements the selected Evidence Theater layout, responsive behavior, focus states, and reduced motion.
- `ui/src/assets/showcase/evidence-theater-park.png`: generated text-free night smart-park raster matching the selected visual.
- `docs/design-references/2026-08-30-showcase-option-2.png`: immutable visual target used by design QA.
- `design-qa.md`: same-viewport visual comparison report and final pass/block status.

---

### Task 1: Extract the Existing Operations Workbench Without Behavior Drift

**Files:**
- Create: `ui/src/components/OperationsWorkbench.vue`
- Create: `ui/src/components/OperationsWorkbench.spec.ts`
- Modify: `ui/src/App.vue`
- Modify: `ui/src/App.spec.ts`

**Interfaces:**
- Produces: `export type WorkbenchView = 'workflow' | 'customer' | 'voice' | 'collaboration' | 'analytics'`.
- Produces: `OperationsWorkbench` prop `initialView: WorkbenchView` and event `back-to-showcase`.
- Preserves: direct workspace siblings for the five existing scenario `<main>` elements and `ExecutionTraceRail`.

- [ ] **Step 1: Write the failing extraction tests**

Create `OperationsWorkbench.spec.ts` by moving the current operator-only assertions out of `App.spec.ts`. The first test must import the missing component and prove view persistence:

```ts
it('keeps analysis mounted while switching operator views', async () => {
  const wrapper = mount(OperationsWorkbench, {
    props: { initialView: 'workflow' },
    global: { stubs: operatorStubs },
  })
  await settleCapabilities()
  await wrapper.get('[data-workbench-view="analytics"]').trigger('click')
  await wrapper.get('[data-workbench-view="workflow"]').trigger('click')
  expect(mounts.analysis).toBe(1)
  expect(unmounts.analysis).toBe(0)
})
```

Add assertions for five scenario siblings, capability-hidden voice/collaboration/analytics buttons, and an emitted `back-to-showcase` intent.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
npm.cmd run test:unit -- src/components/OperationsWorkbench.spec.ts
```

Expected: FAIL because `OperationsWorkbench.vue` does not exist.

- [ ] **Step 3: Move the current operator implementation intact**

Move the current `App.vue` script and operator template into `OperationsWorkbench.vue`. Replace the local `activeView` initialization with a watched prop while retaining `v-show` for all scenario pages:

```ts
export type WorkbenchView = 'workflow' | 'customer' | 'voice' | 'collaboration' | 'analytics'

const props = withDefaults(defineProps<{ initialView?: WorkbenchView }>(), {
  initialView: 'workflow',
})
const emit = defineEmits<{ 'back-to-showcase': [] }>()
const activeView = ref<WorkbenchView>(props.initialView)
watch(() => props.initialView, (view) => { activeView.value = view })
```

Add `data-workbench-view` attributes and one real button that emits `back-to-showcase`. Do not change workflow, approval, feedback, trace, or capability behavior in this task.

- [ ] **Step 4: Replace `App.vue` with the minimal surface shell**

Use temporary stubs for the not-yet-built showcase surface so the workbench extraction can pass independently:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import OperationsWorkbench, { type WorkbenchView } from './components/OperationsWorkbench.vue'

const surface = ref<'showcase' | 'workbench'>('showcase')
const requestedView = ref<WorkbenchView>('workflow')
</script>
```

Do not copy operator state back into `App.vue`.

- [ ] **Step 5: Run focused and full frontend tests**

Run:

```powershell
npm.cmd run test:unit -- src/components/OperationsWorkbench.spec.ts src/App.spec.ts
npm.cmd run test:unit
```

Expected: the focused extraction tests and the existing suite pass with no unmount regression.

- [ ] **Step 6: Commit the architecture slice**

```powershell
git add ui/src/App.vue ui/src/App.spec.ts ui/src/components/OperationsWorkbench.vue ui/src/components/OperationsWorkbench.spec.ts
git commit -m "refactor(ui): isolate operations workbench"
```

---

### Task 2: Build the Truthful Showcase Selection State Machine

**Files:**
- Create: `ui/src/components/showcase/ShowcaseHome.vue`
- Create: `ui/src/components/showcase/ShowcaseHome.spec.ts`
- Create: `ui/src/components/showcase/showcase-home.css`
- Modify: `ui/package.json`
- Modify: `ui/package-lock.json`

**Interfaces:**
- Consumes: `getShowcaseScenarios(): Promise<ShowcaseScenarioCatalog>`.
- Emits: `start-scenario` with one exact `ShowcaseScenario['id']` and `enter-workbench` with no payload.
- Selectability invariant: `scenario.status === 'READY' && scenario.live === true`.

- [ ] **Step 1: Add the existing open-source icon package**

Run:

```powershell
npm.cmd install @element-plus/icons-vue@2.3.2
```

Use `VideoPlay`, `Monitor`, `Connection`, `ShieldCheck`, `DataLine`, `DocumentChecked`, `User`, and `Lock`. Do not create SVG or CSS substitutes.

- [ ] **Step 2: Write failing behavior tests against the real component**

Mock only the HTTP client boundary and assert rendered behavior:

```ts
vi.mock('../../services/workflowApi', () => ({
  getShowcaseScenarios: vi.fn(),
}))

it('selects verified collaboration and emits its exact scenario id', async () => {
  vi.mocked(getShowcaseScenarios).mockResolvedValue(catalog([
    scenario('ALERT_WORKFLOW', 'NOT_READY', false, '最近一次在线检查未通过'),
    scenario('EXPERT_COLLABORATION', 'READY', true, null),
  ]))
  const wrapper = mount(ShowcaseHome)
  await flushPromises()
  expect(wrapper.get('[data-selected-scenario]').text()).toContain('跨域专家协作')
  await wrapper.get('[data-start-showcase]').trigger('click')
  expect(wrapper.emitted('start-scenario')).toEqual([['EXPERT_COLLABORATION']])
})
```

Add separate tests proving:

- `NOT_READY` and `DISABLED` rows show `unavailableReason` and cannot be selected.
- `READY` with `live: false` is not selectable.
- a request failure shows “当前无法确认演示链路” and no enabled start button.
- no ready scenario shows “暂无已验证场景”.
- the workbench action emits `enter-workbench`.

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```powershell
npm.cmd run test:unit -- src/components/showcase/ShowcaseHome.spec.ts
```

Expected: FAIL because `ShowcaseHome.vue` does not exist.

- [ ] **Step 4: Implement the minimal catalog state machine**

Use server fields directly. The only local ordering policy is customer-demo priority:

```ts
const priority = ['EXPERT_COLLABORATION', 'ALERT_WORKFLOW', 'OPERATIONS_ANALYSIS', 'VOICE_ASSISTANT'] as const
const isSelectable = (scenario: ShowcaseScenario) => scenario.status === 'READY' && scenario.live
const selectedId = ref<ShowcaseScenario['id'] | null>(null)

const orderedScenarios = computed(() => [...catalog.value.scenarios]
  .sort((a, b) => Number(isSelectable(b)) - Number(isSelectable(a))
    || priority.indexOf(a.id) - priority.indexOf(b.id))
  .slice(0, 3))
```

After a successful fetch, select the first selectable task. Never replace server title, question, duration, proof types, human boundary, reason, or verification time with fabricated values.

- [ ] **Step 5: Implement accessible loading, empty, error, and disabled states**

Use `aria-live="polite"` for the safe status summary, native buttons for scenario rows, `disabled` for unavailable tasks, visible focus rings, and text labels alongside icons. The evidence ribbon is explanatory product copy, never presented as a running event stream.

- [ ] **Step 6: Run RED→GREEN verification**

Run:

```powershell
npm.cmd run test:unit -- src/components/showcase/ShowcaseHome.spec.ts
npm.cmd run typecheck
```

Expected: all showcase tests and typecheck pass.

- [ ] **Step 7: Commit the truthful-selection slice**

```powershell
git add ui/package.json ui/package-lock.json ui/src/components/showcase
git commit -m "feat(ui): add truthful showcase home"
```

---

### Task 3: Wire the Selected Task Into Existing Scenario Ownership

**Files:**
- Modify: `ui/src/App.vue`
- Modify: `ui/src/App.spec.ts`
- Modify: `ui/src/components/OperationsWorkbench.vue`

**Interfaces:**
- Consumes: `start-scenario` IDs from `ShowcaseHome`.
- Produces mapping only: `ALERT_WORKFLOW → workflow`, `EXPERT_COLLABORATION → collaboration`, `OPERATIONS_ANALYSIS → analytics`, `VOICE_ASSISTANT → voice`.
- Preserves: the scenario component starts its own run through its existing controls and APIs.

- [ ] **Step 1: Write failing App integration tests**

Use real `App.vue` with focused child stubs:

```ts
it('opens collaboration in the cached workbench for a verified showcase task', async () => {
  const wrapper = mount(App, { global: { stubs } })
  expect(wrapper.get('[data-surface="showcase"]').isVisible()).toBe(true)
  wrapper.getComponent(ShowcaseHome).vm.$emit('start-scenario', 'EXPERT_COLLABORATION')
  await nextTick()
  expect(wrapper.getComponent(OperationsWorkbench).props('initialView')).toBe('collaboration')
})
```

Add tests for all four mappings, the secondary workbench action defaulting to `workflow`, and returning to the showcase without destroying the cached workbench instance.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
npm.cmd run test:unit -- src/App.spec.ts
```

Expected: FAIL because the event mapping and cached surface coordinator are incomplete.

- [ ] **Step 3: Implement exact routing without a new router or duplicate execution**

```ts
const scenarioView: Record<ShowcaseScenario['id'], WorkbenchView> = {
  ALERT_WORKFLOW: 'workflow',
  EXPERT_COLLABORATION: 'collaboration',
  OPERATIONS_ANALYSIS: 'analytics',
  VOICE_ASSISTANT: 'voice',
}

function startScenario(id: ShowcaseScenario['id']) {
  requestedView.value = scenarioView[id]
  surface.value = 'workbench'
}
```

Wrap the workbench in Vue `KeepAlive`, mount it only after first entry, and return to the customer surface through `back-to-showcase`. Do not call scenario start APIs from `App.vue`.

- [ ] **Step 4: Run focused and full frontend tests**

Run:

```powershell
npm.cmd run test:unit -- src/App.spec.ts src/components/OperationsWorkbench.spec.ts src/components/showcase/ShowcaseHome.spec.ts
npm.cmd run test:unit
```

Expected: all mappings and the full suite pass.

- [ ] **Step 5: Commit the surface-routing slice**

```powershell
git add ui/src/App.vue ui/src/App.spec.ts ui/src/components/OperationsWorkbench.vue
git commit -m "feat(ui): route showcase tasks to live scenarios"
```

---

### Task 4: Recreate the Evidence Theater Visual and Pass Design QA

**Files:**
- Create: `docs/design-references/2026-08-30-showcase-option-2.png`
- Create: `ui/src/assets/showcase/evidence-theater-park.png`
- Modify: `ui/src/components/showcase/ShowcaseHome.vue`
- Modify: `ui/src/components/showcase/showcase-home.css`
- Create: `design-qa.md`

**Interfaces:**
- The hero raster contains only the park, lighting, and data connections; all product text, states, controls, and icons remain semantic Vue UI.
- CSS custom properties are scoped under `.showcase-home` and do not alter the workbench theme.

- [ ] **Step 1: Preserve the exact visual target**

Copy the selected generated result to the tracked design reference path without modifying the original generated file. Record its pixel dimensions and SHA-256 in `design-qa.md`.

- [ ] **Step 2: Generate the one required raster asset**

Use built-in Image Gen with the selected target as the reference image. Request a text-free aerial night smart-park scene matching its camera, graphite palette, cyan/violet Agent connections, lake placement, and left-side focal labels, but containing no UI, words, logos, icons, buttons, panels, or watermarks. Save the final result as `ui/src/assets/showcase/evidence-theater-park.png`.

- [ ] **Step 3: Implement measured Evidence Theater styling**

At 1440 × 1024, match these measured regions from the target:

- stage: left `0–68%`, top `0–84%`;
- task panel: right `68–100%`, top `2–82%`;
- evidence ribbon: full width, bottom `82–100%`;
- product lockup: top-left with 40–48px outer inset;
- right primary action: full panel width minus 32px gutters;
- borders: one-pixel low-contrast graphite; radii 16–20px only for major surfaces.

Use background image cropping plus black glass overlays, never recreate park connections with CSS. Add hover, selected, disabled, focus-visible, loading, and reduced-motion states.

- [ ] **Step 4: Verify responsive layouts**

At `<1250px`, stack the task panel under the stage and keep the evidence ribbon readable. At `<760px`, use a single column, hide nonessential decorative labels, preserve the selected question and start action, and keep all controls keyboard reachable.

- [ ] **Step 5: Run automated verification**

Run:

```powershell
npm.cmd run test:unit
npm.cmd run typecheck
npm.cmd run build
git diff --check
```

Expected: tests, typecheck, production build, and whitespace check pass. Report the existing Vite chunk warning separately if it remains.

- [ ] **Step 6: Run same-viewport browser and design QA**

Start the existing Vite app on an unused verified port, open the exact project/page in the Codex in-app browser, and capture 1440 × 1024 screenshots for:

- successful catalog with collaboration selected;
- no-ready/error state;
- operator workbench after the CTA.

Compare the reference and implementation at the same viewport. Write `design-qa.md`, fix every P0/P1/P2 issue, recapture, and repeat until it says `final result: passed`. Keep only P3 polish as follow-up notes.

- [ ] **Step 7: Run full repository verification**

Run:

```powershell
.\mvnw.cmd -B test
```

Expected: backend suite remains green because this slice consumes but does not change the merged catalog contract.

- [ ] **Step 8: Commit the visual and QA slice**

```powershell
git add docs/design-references/2026-08-30-showcase-option-2.png ui/src/assets/showcase/evidence-theater-park.png ui/src/components/showcase/ShowcaseHome.vue ui/src/components/showcase/showcase-home.css design-qa.md
git commit -m "feat(ui): recreate evidence theater showcase"
```

## Plan Self-Review

- Spec coverage: default customer surface, workbench preservation, truthful readiness, disabled reasons, task selection, trust boundary, scenario ownership, responsive behavior, accessibility, real visual asset, and online/browser QA are assigned to explicit tasks.
- Scope boundary: this plan does not add a second Vue app, new URL, duplicated orchestrator, fake execution animation, production receipt writer, deployment, or a full customer task-stage redesign.
- Type consistency: `ShowcaseScenario['id']` is the event payload; `WorkbenchView` is the workbench prop and mapping output in every task.
- Placeholder scan: no TBD/TODO steps remain; every code behavior has a focused test command and expected failure/pass condition.
