<template>
  <el-dialog v-model="visible" :title="title" width="420px">
    <span>{{ message }}</span>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const visible = ref(false)
const title = ref('确认')
const message = ref('')

// TODO: 通用确认弹窗（暴露 open/close 方法与 confirm 回调）。
function onConfirm(): void {
  visible.value = false
}
</script>
