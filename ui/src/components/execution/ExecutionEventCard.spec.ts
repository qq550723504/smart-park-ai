import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ExecutionEventCard from './ExecutionEventCard.vue'
import type { ExecutionEvent } from '../../types/execution'

const base = {
  eventId: 'e1',
  runId: 'r1',
  sequence: 1,
  timestamp: '2026-08-24T08:00:00Z',
  scenario: 'ALERT_WORKFLOW' as const,
  actor: 'alert workflow',
  eventType: 'RUN_STARTED',
  stage: 'ANALYSIS',
  status: 'RUNNING',
  safeSummary: 'collectParkContext completed',
}

function eventOf(overrides: Partial<ExecutionEvent>): ExecutionEvent {
  return { ...base, ...overrides } as ExecutionEvent
}

describe('ExecutionEventCard', () => {
  it('renders actor, stage, status and safe summary', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: { event: eventOf({ displayPayload: null }) },
    })
    expect(wrapper.text()).toContain('alert workflow')
    expect(wrapper.text()).toContain('分析')
    expect(wrapper.text()).toContain('collectParkContext completed')
  })

  it('renders a text payload', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: {
        event: eventOf({
          eventType: 'TEXT_DELTA',
          displayPayload: { payloadType: 'TEXT', text: '正在分析能耗数据', partial: true },
        }),
      },
    })
    expect(wrapper.text()).toContain('正在分析能耗数据')
    expect(wrapper.text()).toContain('部分')
  })

  it('renders tool call payloads with safe arguments only', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: {
        event: eventOf({
          eventType: 'TOOL_CALL_COMPLETED',
          displayPayload: {
            payloadType: 'TOOL_CALL',
            toolName: 'EnergyQueryTool',
            safeArguments: { buildingId: 'B1' },
            resultSummary: '返回 24 小时曲线',
          },
        }),
      },
    })
    expect(wrapper.text()).toContain('EnergyQueryTool')
    expect(wrapper.text()).toContain('B1')
    expect(wrapper.find('.tool-arguments').text()).not.toContain('apiKey')
  })

  it('renders expert handoff payloads', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: {
        event: eventOf({
          eventType: 'EXPERT_HANDOFF',
          displayPayload: { payloadType: 'EXPERT_HANDOFF', domain: 'energy', direction: 'DISPATCH', findingStatus: 'SUPPORTED' },
        }),
      },
    })
    expect(wrapper.text()).toContain('energy')
    expect(wrapper.text()).toContain('DISPATCH')
  })

  it('renders sql payloads without any connection details', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: {
        event: eventOf({
          eventType: 'SQL_VALIDATED',
          displayPayload: {
            payloadType: 'SQL',
            safeSql: 'SELECT building_id FROM analytics.v_energy_hourly LIMIT 10',
            parameterNames: ['fromHour', 'toHour'],
            validationStatus: 'PASSED',
          },
        }),
      },
    })
    expect(wrapper.text()).toContain('v_energy_hourly')
    expect(wrapper.text()).toContain('fromHour')
    expect(wrapper.text()).not.toContain('jdbc')
    expect(wrapper.text()).not.toContain('postgres://')
  })

  it('renders chart specs with type and fields', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: {
        event: eventOf({
          eventType: 'CHART_SPECIFIED',
          displayPayload: {
            payloadType: 'CHART',
            type: 'LINE',
            title: '能耗趋势',
            xField: 'hour',
            yFields: ['kwh'],
            seriesField: 'buildingId',
            unit: 'kWh',
          },
        }),
      },
    })
    expect(wrapper.text()).toContain('LINE')
    expect(wrapper.text()).toContain('能耗趋势')
    expect(wrapper.text()).toContain('kWh')
  })

  it('renders audio payloads as state metadata only', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: {
        event: eventOf({
          eventType: 'AUDIO_STARTED',
          displayPayload: { payloadType: 'AUDIO', state: 'STREAMING', durationMs: null },
        }),
      },
    })
    expect(wrapper.text()).toContain('STREAMING')
  })

  it('marks error payloads with an error state and retry hint', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: {
        event: eventOf({
          eventType: 'FAILED',
          status: 'FAILED',
          displayPayload: {
            payloadType: 'ERROR',
            stage: 'TOOL_EXECUTION',
            errorCode: 'TOOL_TIMEOUT',
            retryable: true,
            safeMessage: '工具执行超时',
          },
        }),
      },
    })
    expect(wrapper.classes()).toContain('is-error')
    expect(wrapper.text()).toContain('TOOL_TIMEOUT')
    expect(wrapper.text()).toContain('可重试')
  })

  it('exposes an accessible label combining actor and event type', () => {
    const wrapper = mount(ExecutionEventCard, {
      props: { event: eventOf({ displayPayload: null }) },
    })
    expect(wrapper.attributes('role')).toBe('listitem')
    expect(wrapper.attributes('aria-label')).toContain('alert workflow')
    expect(wrapper.attributes('aria-label')).toContain('RUN_STARTED')
  })
})
