import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import ExpertCollaborationPage from './ExpertCollaborationPage.vue'
import type { ExecutionEvent } from '../types/execution'
import type { ExecutionTrace } from '../composables/useExecutionTrace'
import type { CollaborationRun } from '../types/collaboration'
import { formatSynthesis } from '../utils/collaborationPresentation'

const RUN_ID = '11111111-2222-3333-4444-555555555555'
let polls = 0
let collaborationPosts = 0

const collaborationElementStubs = {
  'el-tag': { template: '<span><slot /></span>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-button': { props: ['loading', 'disabled'], template: '<button :disabled="disabled"><slot /></button>' },
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function traceStub(events: ExecutionEvent[] = [], subscribe = vi.fn()): ExecutionTrace {
  return {
    events: ref(events), status: ref('streaming'), error: ref(''), lastSequence: ref(events.length),
    isTerminal: ref(false), subscribe, reset: () => undefined,
  }
}

beforeEach(() => {
  polls = 0
  collaborationPosts = 0
  globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'POST') {
      collaborationPosts += 1
      return jsonResponse({ runId: RUN_ID, statusUrl: '/status', eventsUrl: '/events' }, 202)
    }
    polls += 1
    return jsonResponse({
      runId: RUN_ID, question: 'q', status: 'COMPLETED',
      plan: {
        normalizedQuestion: 'q', selectedDomains: ['ENERGY', 'SECURITY'],
        assignments: { ENERGY: 'check load', SECURITY: 'check access' }, selectionReason: 'two domains',
      },
      findings: [
        { domain: 'ENERGY', status: 'SUPPORTED', conclusion: 'energy supported', evidenceRefs: ['energy:1'], confidence: 0.9, nextChecks: [] },
        { domain: 'SECURITY', status: 'INSUFFICIENT_EVIDENCE', conclusion: 'security uncertain', evidenceRefs: [], confidence: 0.2, nextChecks: ['review redacted summary'] },
      ],
      synthesis: { status: 'INSUFFICIENT_EVIDENCE', conclusion: 'needs review', evidenceRefs: ['energy:1'], confidence: 0.5, uncertainties: ['security evidence is incomplete'] },
      error: null, updatedAt: '2026-08-25T00:00:00Z',
    })
  }) as unknown as typeof fetch
})

describe('ExpertCollaborationPage', () => {
  it('does not turn a supported negative relationship conclusion into a positive one', () => {
    const display = formatSynthesis({
      status: 'SUPPORTED', conclusion: '无关联', evidenceRefs: [], confidence: 0.9, uncertainties: [],
    })

    expect(display.conclusion).toBe('无关联')
    expect(display.conclusion).not.toContain('已确认存在关联')
  })

  it('does not label a supported factual conclusion as a relationship', () => {
    const display = formatSynthesis({
      status: 'SUPPORTED', conclusion: 'MTR-2 consumption is 18.5 above baseline', evidenceRefs: [], confidence: 0.9, uncertainties: [],
    })

    expect(display.conclusion).toBe('MTR-2 consumption is 18.5 above baseline')
    expect(display.conclusion).not.toContain('已确认存在关联')
  })

  it('keeps failed-expert uncertainty distinct from insufficient evidence', () => {
    const display = formatSynthesis({
      status: 'INSUFFICIENT_EVIDENCE', conclusion: 'needs review', evidenceRefs: [], confidence: 0,
      uncertainties: ['DEVICE: failed to query device'],
    })

    expect(display.uncertainties[0]).toContain('设备专家执行失败')
    expect(display.uncertainties[0]).not.toContain('证据不足')
  })

  it('preserves the supervisor conclusion when it is not one of the localized status labels', async () => {
    globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({ runId: RUN_ID, statusUrl: '/status', eventsUrl: '/events' }, 202)
      }
      return jsonResponse({
        runId: RUN_ID, question: 'q', status: 'COMPLETED', plan: null, findings: [],
        synthesis: { status: 'SUPPORTED', conclusion: 'ENERGY and DEVICE are aligned', evidenceRefs: [], confidence: 0.9, uncertainties: [] },
        error: null, updatedAt: '2026-08-25T00:00:00Z',
      })
    }) as unknown as typeof fetch

    const wrapper = mount(ExpertCollaborationPage, {
      props: { trace: traceStub() },
      global: { stubs: collaborationElementStubs },
    })
    await wrapper.find('form').trigger('submit')
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(wrapper.find('.synthesis-panel').text()).toContain('ENERGY and DEVICE are aligned')
    wrapper.unmount()
  })

  it('renders Chinese customer-facing wording for supervisor uncertainty', async () => {
    globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({ runId: RUN_ID, statusUrl: '/status', eventsUrl: '/events' }, 202)
      }
      return jsonResponse({
        runId: RUN_ID, question: 'q', status: 'COMPLETED',
        plan: {
          normalizedQuestion: 'q', selectedDomains: ['ENERGY', 'DEVICE', 'SECURITY'],
          assignments: { ENERGY: 'energy', DEVICE: 'device', SECURITY: 'security' }, selectionReason: 'three domains',
        },
        findings: [
          { domain: 'ENERGY', status: 'SUPPORTED', conclusion: 'energy supported', evidenceRefs: ['energy:1'], confidence: 0.9, nextChecks: [] },
          { domain: 'DEVICE', status: 'SUPPORTED', conclusion: 'device supported', evidenceRefs: ['device:1'], confidence: 0.8, nextChecks: [] },
          { domain: 'SECURITY', status: 'INSUFFICIENT_EVIDENCE', conclusion: 'security uncertain', evidenceRefs: [], confidence: 0.0, nextChecks: ['review redacted summary'] },
        ],
        synthesis: {
          status: 'INSUFFICIENT_EVIDENCE', conclusion: '无法确认', evidenceRefs: [], confidence: 0,
          uncertainties: ['SECURITY finding has confidence 0.0 and provides no temporal, spatial, or causal link to ENERGY or DEVICE findings; all findings are isolated mock data with no cross-domain correlation evidence'],
        },
        error: null, updatedAt: '2026-08-25T00:00:00Z',
      })
    }) as unknown as typeof fetch

    const wrapper = mount(ExpertCollaborationPage, {
      props: { trace: traceStub() },
      global: { stubs: collaborationElementStubs },
    })
    await wrapper.find('form').trigger('submit')
    await new Promise((resolve) => setTimeout(resolve, 10))

    const synthesis = wrapper.find('.synthesis-panel').text()
    expect(synthesis).toContain('无法确认')
    expect(synthesis).toContain('安防专家')
    expect(synthesis).toContain('当前证据不足')
    expect(synthesis).not.toContain('SECURITY finding has confidence')
    expect(synthesis).not.toContain('temporal, spatial, or causal')
    wrapper.unmount()
  })

  it('renders only selected experts and displays findings, synthesis, and real handoffs', async () => {
    const handoff: ExecutionEvent = {
      eventId: 'event-1', runId: RUN_ID, sequence: 1, timestamp: '2026-08-25T08:00:00Z',
      scenario: 'EXPERT_COLLABORATION', actor: 'EnergyExpert', stage: 'EXPERT_EXECUTION',
      eventType: 'EXPERT_HANDOFF', status: 'RUNNING', safeSummary: 'Energy expert completed',
      displayPayload: { payloadType: 'EXPERT_HANDOFF', domain: 'ENERGY', direction: 'RETURN', findingStatus: 'SUPPORTED' },
    }
    const wrapper = mount(ExpertCollaborationPage, {
      props: { trace: traceStub([handoff]) },
      global: {
        stubs: collaborationElementStubs,
      },
    })
    await wrapper.find('input[aria-label="专家协作问题"]').setValue('q')
    await wrapper.find('.collaboration-presets button').trigger('click')
    expect((wrapper.find('input[aria-label="专家协作问题"]').element as HTMLInputElement).value).toContain('DEV-ENERGY-001')
    await wrapper.find('form').trigger('submit')
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(wrapper.find('[data-testid="expert-card-ENERGY"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="expert-card-SECURITY"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="expert-card-DEVICE"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('能耗专家已返回核查结果')
    expect(wrapper.text()).toContain('安防专家当前证据不足')
    expect(wrapper.text()).toContain('当前证据不足，暂无法确认关联')
    expect(wrapper.text()).toContain('EnergyExpert')
    expect(wrapper.text()).toContain('Energy expert completed')
    expect(wrapper.text()).toContain('50%证据覆盖')
    expect(wrapper.text()).toContain('工具证据已验证')
    expect(wrapper.text()).toContain('工具证据覆盖 50%')
    expect(wrapper.text()).toContain('模型置信度 20%')
    expect(wrapper.text()).not.toContain('汇总置信度')
    expect(polls).toBeGreaterThan(0)
    wrapper.unmount()
  })

  it('ships only presets whose exact identifiers exist in the mock expert tools', () => {
    const wrapper = mount(ExpertCollaborationPage, {
      props: { trace: traceStub() },
      global: {
        stubs: collaborationElementStubs,
      },
    })

    const input = (wrapper.find('input[aria-label="专家协作问题"]').element as HTMLInputElement).value
    const presets = wrapper.findAll('.collaboration-presets button').map((button) => button.text())
    expect(input).toContain('DEV-ENERGY-001')
    expect(input).toContain('DEV-POWER-001')
    expect(input).toContain('SEC-ACCESS-001')
    expect(presets).toEqual([
      '电表 DEV-ENERGY-001 当前能耗是否高于基线',
      '设备 DEV-HVAC-001 当前状态如何，是否存在关联告警',
      '电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联',
    ])
    wrapper.unmount()
  })

  it('resubscribes to the existing run when its view becomes active again', async () => {
    const subscribe = vi.fn()
    const wrapper = mount(ExpertCollaborationPage, {
      props: { trace: traceStub([], subscribe), active: true },
      global: {
        stubs: collaborationElementStubs,
      },
    })

    await wrapper.find('form').trigger('submit')
    await new Promise((resolve) => setTimeout(resolve, 10))
    const subscriptionsAfterStart = subscribe.mock.calls.length

    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })

    expect(subscribe.mock.calls.length).toBe(subscriptionsAfterStart + 1)
    expect(subscribe).toHaveBeenLastCalledWith(RUN_ID)
    wrapper.unmount()
  })

  it('does not resubscribe when polling updates the same run id', async () => {
    const subscribe = vi.fn()
    const wrapper = mount(ExpertCollaborationPage, {
      props: { trace: traceStub([], subscribe), active: true },
      global: {
        stubs: collaborationElementStubs,
      },
    })
    const running: CollaborationRun = {
      runId: RUN_ID, question: 'q', status: 'RUNNING', plan: null,
      findings: [], synthesis: null, error: null, updatedAt: '2026-08-25T00:00:00Z',
    }

    const sharedRun = wrapper.vm as unknown as { run: CollaborationRun | null }
    sharedRun.run = running
    await wrapper.vm.$nextTick()
    const subscriptionsAfterRunId = subscribe.mock.calls.length
    sharedRun.run = { ...running, updatedAt: '2026-08-25T00:00:01Z' }
    await wrapper.vm.$nextTick()

    expect(subscribe.mock.calls.length).toBe(subscriptionsAfterRunId)
    wrapper.unmount()
  })

  it('starts the default collaboration once for a matching guided request', async () => {
    const request = {
      requestId: 21,
      mode: 'guided',
      scenarioId: 'EXPERT_COLLABORATION',
      view: 'collaboration',
      launchInput: {
        alertId: null,
        question: '电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联',
      },
    } as const
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

  it('restores the configured question before a guided collaboration launch', async () => {
    let guidedQuestion = ''
    globalThis.fetch = (async (_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        guidedQuestion = JSON.parse(String(init.body)).question
        return jsonResponse({ runId: RUN_ID, statusUrl: '/status', eventsUrl: '/events' }, 202)
      }
      return jsonResponse({
        runId: RUN_ID, question: guidedQuestion, status: 'COMPLETED', plan: null,
        findings: [], synthesis: null, error: null, updatedAt: '2026-08-25T00:00:00Z',
      })
    }) as typeof fetch
    const wrapper = mount(ExpertCollaborationPage, {
      props: { trace: traceStub(), active: true },
      global: { stubs: collaborationElementStubs },
    })
    await wrapper.find('input[aria-label="专家协作问题"]').setValue('手工输入的无关问题')

    await wrapper.setProps({
      launchRequest: {
        requestId: 22, mode: 'guided', scenarioId: 'EXPERT_COLLABORATION', view: 'collaboration',
        launchInput: { alertId: null, question: '目录下发的专家协作问题' },
      },
    })
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(guidedQuestion).toBe('目录下发的专家协作问题')
    wrapper.unmount()
  })
})
