<script setup lang="ts">
import { Cpu, OfficeBuilding, UserFilled } from '@element-plus/icons-vue'
import campusBanner from '../../assets/customer/campus-banner.png'
import campusBanner960 from '../../assets/customer/campus-banner-960.webp'
import campusBanner1440 from '../../assets/customer/campus-banner-1440.webp'
import campusBanner2172 from '../../assets/customer/campus-banner-2172.webp'
import type { CustomerPage } from '../../types/customer'

const props = withDefaults(defineProps<{ activePage?: CustomerPage }>(), { activePage: 'overview' })
defineEmits<{
  'enter-workbench': []
  navigate: [page: CustomerPage]
}>()

const customerNavigation = [
  { id: 'overview', label: '园区总览', available: true },
  { id: 'analysis', label: '运营分析', available: true },
  { id: 'work-orders', label: '事件与工单', available: false },
  { id: 'reports', label: '运营报告', available: false },
  { id: 'assistant', label: 'AI 助手', available: false },
] as const
</script>

<template>
  <div class="customer-shell" data-customer-shell>
    <a class="customer-shell__skip" :href="activePage === 'analysis' ? '#customer-analysis-main' : '#customer-overview-main'">
      跳到{{ activePage === 'analysis' ? '运营分析' : '园区总览' }}
    </a>
    <header class="customer-shell__topbar">
      <div class="customer-shell__brand" aria-label="AI 智慧园区">
        <span class="customer-shell__brand-mark"><OfficeBuilding aria-hidden="true" /></span>
        <strong>AI 智慧园区</strong>
      </div>
      <span class="customer-shell__divider" aria-hidden="true"></span>
      <div class="customer-shell__park">
        <strong>演示园区</strong>
        <span>模拟业务数据</span>
      </div>
      <nav class="customer-shell__nav" aria-label="客户业务导航">
        <template v-for="item in customerNavigation" :key="item.id">
          <button
            v-if="item.available"
            type="button"
            :class="{ 'is-current': activePage === item.id }"
            :data-customer-nav="item.id"
            :aria-current="activePage === item.id ? 'page' : undefined"
            @click="$emit('navigate', item.id)"
          >
            {{ item.label }}
          </button>
          <span
            v-else
            class="is-planned"
            :data-customer-nav="item.id"
            aria-disabled="true"
            title="功能暂未开放"
          >
            {{ item.label }}
          </span>
        </template>
      </nav>
      <div class="customer-shell__user-zone">
        <span class="customer-shell__scope">{{ activePage === 'analysis' ? '分析页继承总览选择' : '总览内支持楼宇选择' }}</span>
        <button type="button" class="customer-shell__workbench" data-enter-workbench @click="$emit('enter-workbench')">
          <span class="customer-shell__avatar"><UserFilled aria-hidden="true" /></span>
          <span><strong>园区管理方</strong><small>进入内部工作台</small></span>
          <Cpu aria-hidden="true" />
        </button>
      </div>
    </header>

    <div class="customer-shell__body">
      <section v-if="props.activePage === 'overview'" class="customer-shell__hero" aria-labelledby="customer-hero-title">
        <picture class="customer-art" aria-hidden="true">
          <source
            type="image/webp"
            :srcset="`${campusBanner960} 960w, ${campusBanner1440} 1440w, ${campusBanner2172} 2172w`"
            sizes="(max-width: 760px) 96vw, 98vw"
          />
          <img :src="campusBanner" alt="" fetchpriority="high" />
        </picture>
        <div>
          <h1 id="customer-hero-title">AI 让园区更智慧，让运营更从容</h1>
          <p>安全 · 绿色 · 高效 · 人性化　打造可持续发展的未来园区</p>
        </div>
        <span>科技赋能空间<br />让美好发生</span>
      </section>
      <slot />
      <footer class="customer-shell__footer">
        <span>演示园区 · 模拟业务数据 · 园区画面为空间示意</span>
        <span>数据失败会明确提示，不自动替换为成功样例</span>
      </footer>
    </div>
  </div>
</template>
