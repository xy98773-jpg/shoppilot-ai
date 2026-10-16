<template>
  <el-form class="search-form" :inline="true" @submit.prevent="onSearch">
    <el-form-item v-for="field in fields" :key="field.prop" :label="field.label">
      <el-select
        v-if="field.type === 'select'"
        v-model="model[field.prop]"
        :placeholder="field.placeholder || `请选择${field.label}`"
        clearable
        :style="{ width: `${field.width ?? 160}px` }"
      >
        <el-option
          v-for="opt in field.options || []"
          :key="String(opt.value)"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-input
        v-else
        v-model="model[field.prop]"
        :placeholder="field.placeholder || `请输入${field.label}`"
        clearable
        :style="{ width: `${field.width ?? 180}px` }"
        @keyup.enter="onSearch"
      />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" native-type="submit">查询</el-button>
      <el-button @click="onReset">重置</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
// 通用搜索表单：由字段配置驱动，提交时把**非空**字段以平铺对象 emit 出去。
// 红线：仅做参数收集，不做任何业务规则（筛选语义由服务端定义）。
import { reactive } from 'vue'
import type { SearchField } from '@/types/ui'

const props = defineProps<{ fields: SearchField[] }>()

const emit = defineEmits<{
  (e: 'search', params: Record<string, unknown>): void
  (e: 'reset'): void
}>()

const model = reactive<Record<string, string | number>>({})
for (const field of props.fields) {
  model[field.prop] = ''
}

/** 收集非空字段作为查询参数。 */
function collect(): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  for (const field of props.fields) {
    const value = model[field.prop]
    if (value !== '' && value !== null && value !== undefined) {
      params[field.prop] = value
    }
  }
  return params
}

function onSearch(): void {
  emit('search', collect())
}

function onReset(): void {
  for (const field of props.fields) {
    model[field.prop] = ''
  }
  emit('reset')
}

/** 供父组件在列表刷新后清空条件（可选）。 */
function resetFields(): void {
  for (const field of props.fields) {
    model[field.prop] = ''
  }
}

defineExpose({ resetFields })
</script>

<style scoped>
.search-form {
  margin-bottom: 8px;
}
</style>
