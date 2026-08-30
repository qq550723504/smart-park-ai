<script setup lang="ts">
import type { WorkbenchEvidenceItem } from '../../types/workbench'

defineProps<{ items: WorkbenchEvidenceItem[] }>()
</script>

<template>
  <footer class="immersive-workbench__evidence" aria-label="证据摘要">
    <ul>
      <li v-for="item in items" :key="`${item.label}-${item.value}`" :data-evidence-item="item.label" :data-tone="item.tone ?? 'default'">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </li>
    </ul>
  </footer>
</template>
