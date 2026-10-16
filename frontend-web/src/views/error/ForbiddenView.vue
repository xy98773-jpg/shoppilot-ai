<template>
  <el-result icon="warning" title="无访问权限" sub-title="当前角色无权访问该页面（体验层拦截；服务端仍会独立校验）。">
    <template #extra>
      <el-button type="primary" @click="goHome">返回工作台</el-button>
    </template>
  </el-result>
</template>

<script setup lang="ts">
// 403 页面（体验层路由拦截的目标页；`guards.ts` 中 `return { path: '/403' }`）。
// 注意：这只是前端体验层提示，真正的越权由服务端拒绝（`TEAM-2001` / `TEAM-2003`）。
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome(): void {
  void router.push('/dashboard')
}
</script>
