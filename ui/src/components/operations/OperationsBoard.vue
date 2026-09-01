<script setup lang="ts">
const emit = defineEmits<{ 'open-analysis': [question: string] }>()

const groups = [
  {
    title: '停车与交通',
    description: '按停车区域查看进场量和车位利用率。',
    questions: ['过去5天各停车区域停车利用率', '过去5天各停车区域进场量'],
  },
  {
    title: '能耗与空间',
    description: '从楼宇、占用人数和基线偏差理解空间运营。',
    questions: ['过去5天各楼宇能耗基线偏差', '过去5天各楼宇平均占用人数', '过去5天各楼宇能耗与占用人数关系'],
  },
]
</script>

<template>
  <main class="main-content operations-board" data-operations-board>
    <section class="hero-row">
      <div>
        <span class="eyebrow">运营看板 · 只读</span>
        <h2>停车与能耗，<br /><em>从同一套证据出发</em></h2>
        <p class="hero-copy">选择一个指标卡进入自然语言分析。看板不缓存或编造业务数字，结果始终来自后端登记的只读指标。</p>
      </div>
      <div class="hero-metrics"><div><strong>05</strong><span>受控问题</span></div><div><strong>只读</strong><span>执行模式</span></div></div>
    </section>
    <section v-for="group in groups" :key="group.title" class="panel operations-board__group" :aria-label="group.title">
      <div class="section-heading compact"><div><span class="eyebrow">指标分组</span><h2>{{ group.title }}</h2></div><span class="count-badge">{{ group.questions.length }} 个入口</span></div>
      <p>{{ group.description }}</p>
      <div class="operations-board__cards">
        <button v-for="question in group.questions" :key="question" type="button" data-board-question :data-question="question" @click="emit('open-analysis', question)">
          <span class="operations-board__card-icon">↗</span>
          <span><strong>{{ question }}</strong><small>打开真实只读分析</small></span>
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.operations-board__group { padding: 24px; margin-bottom: 18px; }
.operations-board__group > p { color: var(--showcase-muted); margin: 0 0 16px; }
.operations-board__cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.operations-board__cards button { display: flex; align-items: center; gap: 12px; padding: 16px; text-align: left; color: var(--showcase-ivory); border: 1px solid var(--showcase-border-soft); background: rgba(12, 17, 26, .58); cursor: pointer; }
.operations-board__cards button:hover { border-color: var(--showcase-cyan); background: var(--showcase-cyan-soft); }
.operations-board__cards strong, .operations-board__cards small { display: block; }
.operations-board__cards small { margin-top: 5px; color: var(--showcase-muted); }
.operations-board__card-icon { color: var(--showcase-cyan); font-size: 1.3rem; }
@media (max-width: 650px) { .operations-board__cards { grid-template-columns: 1fr; } }
</style>
