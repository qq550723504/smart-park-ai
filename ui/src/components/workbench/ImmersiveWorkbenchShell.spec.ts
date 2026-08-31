import { mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ImmersiveWorkbenchShell from './ImmersiveWorkbenchShell.vue'
import type { WorkbenchEvidenceItem, WorkbenchNavItem } from '../../types/workbench'

const roleSelectStub = defineComponent({
  props: {
    teleported: {
      type: null,
      default: undefined,
    },
  },
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

function installViewport(initialWidth: number) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>()
  const mediaQuery = {
    matches: initialWidth >= 768,
    media: '(min-width: 768px)',
    onchange: null,
    addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.add(listener)
    }),
    removeEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.delete(listener)
    }),
    dispatchEvent: vi.fn(),
  }
  vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery as unknown as MediaQueryList))

  return {
    mediaQuery,
    resizeTo(width: number): void {
      mediaQuery.matches = width >= 768
      listeners.forEach((listener) => listener({
        matches: mediaQuery.matches,
        media: mediaQuery.media,
      } as MediaQueryListEvent))
    },
  }
}

describe('ImmersiveWorkbenchShell', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

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

  it('keeps the role dropdown inside the immersive workbench scope', () => {
    const wrapper = mountShell()

    expect(wrapper.getComponent(roleSelectStub).props('teleported')).toBe(false)
  })

  it('announces only the active navigation view as the current page', () => {
    const wrapper = mountShell()

    expect(wrapper.get('[data-workbench-view="workflow"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-workbench-view="analytics"]').attributes('aria-current')).toBeUndefined()
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

  it('keeps the rail behaviorally open at 1024 and natively collapsible at 390', async () => {
    const viewport = installViewport(1024)
    const wrapper = mountShell({ railPriority: false })
    const rail = wrapper.get('[data-workbench-rail]')

    expect((rail.element as HTMLDetailsElement).open).toBe(true)

    viewport.resizeTo(390)
    await nextTick()
    expect((rail.element as HTMLDetailsElement).open).toBe(false)

    await wrapper.setProps({ railPriority: true })
    expect((rail.element as HTMLDetailsElement).open).toBe(true)

    await wrapper.setProps({ railPriority: false })
    expect((rail.element as HTMLDetailsElement).open).toBe(false)
    wrapper.get('summary').element.click()
    expect((rail.element as HTMLDetailsElement).open).toBe(true)
    wrapper.get('summary').element.click()
    expect((rail.element as HTMLDetailsElement).open).toBe(false)
  })

  it('removes the rail viewport listener when the shell unmounts', () => {
    const viewport = installViewport(1024)
    const wrapper = mountShell()

    expect(viewport.mediaQuery.addEventListener).toHaveBeenCalledOnce()
    const listener = viewport.mediaQuery.addEventListener.mock.calls[0]?.[1]
    wrapper.unmount()

    expect(viewport.mediaQuery.removeEventListener).toHaveBeenCalledWith('change', listener)
  })
})
