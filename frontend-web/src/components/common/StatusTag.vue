<template>
  <el-tag :type="tag.type" :effect="effect" :size="size">{{ tag.text }}</el-tag>
</template>

<script setup lang="ts">
// 状态标签：仅做「状态字符串 → 展示样式」的映射，**不承载状态语义**（语义由服务端定义）。
import { computed } from 'vue'

/** 已知状态的展示映射（未知状态原样展示，不臆造语义）。 */
const STATUS_STYLE: Record<string, { type: 'success' | 'info' | 'warning' | 'danger'; text: string }> = {
  ACTIVE: { type: 'success', text: '启用' },
  INACTIVE: { type: 'info', text: '停用' },
}

const props = withDefaults(
  defineProps<{
    /** 原始状态值（如 `ACTIVE` / `INACTIVE`）。 */
    status?: string
    /** 显示文案覆盖（缺省时用内置映射或原始值）。 */
    label?: string
    size?: 'large' | 'default' | 'small'
    effect?: 'dark' | 'light' | 'plain'
  }>(),
  { status: '', label: '', size: 'small', effect: 'light' },
)

const tag = computed(() => {
  const style = STATUS_STYLE[props.status] ?? { type: 'info' as const, text: props.status || '—' }
  return { type: style.type, text: props.label || style.text }
})
</script>
