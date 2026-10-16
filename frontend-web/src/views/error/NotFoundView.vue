<template>
  <el-result icon="info" title="页面不存在" :sub-title="`未找到路由：${route.fullPath}`">
    <template #extra>
      <el-button type="primary" @click="goHome">返回工作台</el-button>
    </template>
  </el-result>
</template>

<script setup lang="ts">
// 404 兜底页（通配路由 `:pathMatch(.*)*` 的目标页）。
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

function goHome(): void {
  void router.push('/dashboard')
}
</script>
