<script setup lang="ts">
import { computed } from 'vue'
import { Cpu, Refresh, UserFilled } from '@element-plus/icons-vue'
import campusBanner from '../../assets/customer/campus-banner.png'
import campusBanner960 from '../../assets/customer/campus-banner-960.webp'
import campusBanner1440 from '../../assets/customer/campus-banner-1440.webp'
import campusBanner2172 from '../../assets/customer/campus-banner-2172.webp'
import brandMark from '../../assets/customer/smart-park-brand-mark-v2.png'
import type { CustomerPage } from '../../types/customer'

const props = withDefaults(defineProps<{ activePage?: CustomerPage; assistantOpen?: boolean }>(), { activePage: 'overview', assistantOpen: false })
const emit = defineEmits<{
  'enter-workbench': []
  'open-assistant': []
  'restart-demo': []
  navigate: [page: CustomerPage]
}>()

const customerNavigation = [
  { id: 'overview', label: '园区总览', available: true },
  { id: 'analysis', label: '运营分析', available: true },
  { id: 'work-orders', label: '事件与工单', available: true },
  { id: 'reports', label: '运营报告', available: true },
  { id: 'assistant', label: 'AI 助手', available: true },
] as const

function activateNavigation(id: typeof customerNavigation[number]['id']): void {
  if (id === 'assistant') emit('open-assistant')
  else emit('navigate', id)
}

const pageMeta = computed(() => ({
  overview: { mainId: 'customer-overview-main', titleId: 'customer-hero-title', label: '园区总览', scope: '总览内支持楼宇选择' },
  analysis: { mainId: 'customer-analysis-main', titleId: 'customer-analysis-title', label: '运营分析', scope: '分析页继承总览选择' },
  'work-orders': { mainId: 'customer-work-orders-main', titleId: 'customer-work-orders-title', label: '事件与工单', scope: '工单页继承同一告警' },
  reports: { mainId: 'customer-reports-main', titleId: 'customer-reports-title', label: '运营报告', scope: '报告页读取不可变快照' },
}[props.activePage]))
</script>

<template>
  <div class="customer-shell" data-customer-shell>
    <a class="customer-shell__skip" :href="`#${pageMeta.mainId}`">
      跳到{{ pageMeta.label }}
    </a>
    <header class="customer-shell__topbar">
      <div class="customer-shell__brand" aria-label="AI 智慧园区">
        <img class="customer-shell__brand-mark" :src="brandMark" alt="" aria-hidden="true" />
        <strong>AI 智慧园区</strong>
      </div>
      <span class="customer-shell__divider" aria-hidden="true"></span>
      <div class="customer-shell__park">
        <strong>演示园区</strong>
        <span>模拟业务数据</span>
      </div>
      <nav class="customer-shell__nav" aria-label="客户业务导航">
        <button
          v-for="item in customerNavigation"
          :key="item.id"
          type="button"
          :class="{ 'is-current': item.id === 'assistant' ? assistantOpen : activePage === item.id }"
          :data-customer-nav="item.id"
          :aria-current="item.id !== 'assistant' && activePage === item.id ? 'page' : undefined"
          :aria-expanded="item.id === 'assistant' ? assistantOpen : undefined"
          @click="activateNavigation(item.id)"
        >
          {{ item.label }}
        </button>
      </nav>
      <div class="customer-shell__user-zone">
        <span class="customer-shell__scope">{{ pageMeta.scope }}</span>
        <button type="button" class="customer-shell__restart" data-restart-demo aria-label="重开导览" @click="$emit('restart-demo')">
          <Refresh aria-hidden="true" />
          <span>重开导览</span>
        </button>
        <button type="button" class="customer-shell__workbench" data-enter-workbench @click="$emit('enter-workbench')">
          <span class="customer-shell__avatar"><UserFilled aria-hidden="true" /></span>
          <span><strong>园区管理方</strong><small>进入内部工作台</small></span>
          <Cpu aria-hidden="true" />
        </button>
      </div>
    </header>

    <div class="customer-shell__body">
      <section
        class="customer-shell__hero"
        :aria-labelledby="pageMeta.titleId"
      >
        <picture class="customer-art" aria-hidden="true">
          <source
            type="image/webp"
            :srcset="`${campusBanner960} 960w, ${campusBanner1440} 1440w, ${campusBanner2172} 2172w`"
            sizes="(max-width: 760px) 96vw, 98vw"
          />
          <img :src="campusBanner" alt="" fetchpriority="high" />
        </picture>
        <slot v-if="props.activePage === 'analysis'" name="analysis-hero">
          <div>
            <h1 id="customer-analysis-title">运营分析</h1>
            <p>请先从园区总览选择需要分析的楼宇</p>
          </div>
        </slot>
        <slot v-else-if="props.activePage === 'work-orders'" name="work-orders-hero">
          <div>
            <h1 id="customer-work-orders-title">事件与工单中心</h1>
            <p>围绕异常发现、确认、建单与跟进，形成可核验的处理闭环</p>
          </div>
        </slot>
        <slot v-else-if="props.activePage === 'reports'" name="reports-hero">
          <div>
            <h1 id="customer-reports-title">运营报告中心</h1>
            <p>生成、阅读并下载同一份不可变运营快照</p>
          </div>
        </slot>
        <template v-else>
          <div>
            <h1 id="customer-hero-title">AI 让园区更智慧，让企业更卓越</h1>
            <p>安全 · 绿色 · 高效 · 人性化　打造可持续发展的未来园区</p>
          </div>
          <span>科技赋能空间<br />让美好发生</span>
        </template>
      </section>
      <slot />
      <footer class="customer-shell__footer">
        <span>演示园区 · 模拟业务数据 · 园区画面为空间示意</span>
        <span>数据失败会明确提示，不自动替换为成功样例</span>
      </footer>
    </div>
  </div>
</template>
