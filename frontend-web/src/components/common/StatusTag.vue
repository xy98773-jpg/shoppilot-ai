<template>
  <el-tag :type="tagType">
    <slot>{{ label }}</slot>
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

withDefaults(
  defineProps<{
    status?: string
    label?: string
  }>(),
  { status: '', label: '' },
)

// TODO: 状态 → 标签类型映射（仅展示，状态语义由服务端定义）。
const tagType = computed(() => 'info' as const)
</script>
