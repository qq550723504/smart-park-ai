import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import ImmersiveWorkbenchShell from './ImmersiveWorkbenchShell.vue'
import type { WorkbenchEvidenceItem, WorkbenchNavItem } from '../../types/workbench'

const roleSelectStub = defineComponent({
  emits: ['update:modelValue'],
  template: '<button type="button" data-role-switch @click="$emit(\'update:modelValue\', \'OPERATOR\')">切换角色</button>',
})

const navItems: WorkbenchNavItem[] = [
  { value: 'workflow', label: '告警工作流', available: true },
  { value: 'analytics', label: '运营分析', available: true },
]

const evidenceItems: WorkbenchEvidenceItem[] = [
  { label: '数据源', value: '实时事件', tone: 'verified' },
  { label: '审批状态', value: '等待确认', tone: 'warning' },
]

function mountShell(overrides: Record<string, unknown> = {}) {
  return mount(ImmersiveWorkbenchShell, {
    props: {
      activeView: 'workflow',
      role: 'ADMIN',
      navItems,
      evidenceItems,
      ...overrides,
    },
    slots: {
      default: 'stage content',
      rail: 'rail content',
    },
    global: {
      stubs: {
        'el-select': roleSelectStub,
        'el-option': true,
      },
    },
  })
}

describe('ImmersiveWorkbenchShell', () => {
  it('renders the presentational workspace structure with supplied content', () => {
    const wrapper = mountShell()

    expect(wrapper.get('header').classes()).toContain('immersive-workbench__topbar')
    expect(wrapper.get('nav').attributes('aria-label')).toBe('场景导航')
    expect(wrapper.get('[data-workbench-stage]').text()).toContain('stage content')
    expect(wrapper.get('[data-workbench-rail]').text()).toContain('rail content')
    expect(wrapper.findAll('[data-evidence-item]')).toHaveLength(2)
  })

  it('emits a requested available view', async () => {
    const wrapper = mountShell()

    await wrapper.get('[data-workbench-view="analytics"]').trigger('click')

    expect(wrapper.emitted('switch-view')).toEqual([['analytics']])
  })

  it('omits unavailable navigation instead of exposing it as disabled', () => {
    const wrapper = mountShell({
      navItems: [...navItems, { value: 'voice', label: '实时语音', available: false }],
    })

    expect(wrapper.find('[data-workbench-view="voice"]').exists()).toBe(false)
  })

  it('emits the selected role without owning role state', async () => {
    const wrapper = mountShell()

    await wrapper.get('[data-role-switch]').trigger('click')

    expect(wrapper.emitted('update:role')).toEqual([['OPERATOR']])
  })

  it('emits back and retry actions from the supplied guided status', async () => {
    const wrapper = mountShell({
      guidedLaunch: { requestId: 7, state: 'failed', message: '引导启动失败' },
    })

    await wrapper.get('[data-workbench-action="back-to-showcase"]').trigger('click')
    await wrapper.get('[data-workbench-action="retry-guided-launch"]').trigger('click')

    expect(wrapper.emitted('back-to-showcase')).toEqual([[]])
    expect(wrapper.emitted('retry-guided-launch')).toEqual([[]])
  })

  it('exposes each evidence item tone to consumers', () => {
    const wrapper = mountShell()

    expect(wrapper.findAll('[data-evidence-item]').map((item) => item.attributes('data-tone')))
      .toEqual(['verified', 'warning'])
  })

  it('opens the native rail only when priority is requested', async () => {
    const wrapper = mountShell({ railPriority: false })

    expect(wrapper.get('[data-workbench-rail]').attributes('open')).toBeUndefined()

    await wrapper.setProps({ railPriority: true })

    expect(wrapper.get('[data-workbench-rail]').attributes('open')).toBeDefined()
  })
})
