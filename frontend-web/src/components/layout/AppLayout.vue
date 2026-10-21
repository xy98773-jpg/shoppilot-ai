<template>
  <el-container class="app-layout">
    <el-header class="app-layout__header">
      <div class="app-layout__brand">ShopPilot AI</div>
      <div class="app-layout__grow" />
      <div class="app-layout__session">
        <span class="app-layout__team">{{ teamStore.teamName || '—' }}</span>
        <el-tag size="small" type="success" effect="dark">{{ teamStore.role || '—' }}</el-tag>
        <span class="app-layout__user">{{ userStore.displayName || userStore.user?.username || '' }}</span>
        <el-button link type="primary" @click="onLogout">登出</el-button>
      </div>
    </el-header>
    <el-container class="app-layout__body">
      <el-aside width="200px" class="app-layout__aside">
        <!-- 侧栏：已实现页面（工作台 / 主数据 / 导入 / 库存 / 采购 / 销售）；
             写页按角色显隐（体验层，服务端仍是唯一安全边界）。 -->
        <el-menu :default-active="activePath" router class="app-layout__menu">
          <el-menu-item index="/dashboard">工作台</el-menu-item>
          <el-menu-item index="/catalog/products">商品</el-menu-item>
          <el-menu-item index="/catalog/skus">SKU</el-menu-item>
          <el-menu-item index="/catalog/suppliers">供应商</el-menu-item>
          <el-menu-item index="/catalog/warehouses">仓库</el-menu-item>
          <el-menu-item v-if="canWrite" index="/importdata/wizard">导入向导</el-menu-item>
          <el-menu-item v-if="canWrite" index="/importdata/batches">导入批次</el-menu-item>
          <el-menu-item index="/inventory/overview">库存概览</el-menu-item>
          <el-menu-item index="/inventory/transactions">库存流水</el-menu-item>
          <el-menu-item v-if="canWrite" index="/inventory/count-adjust">盘点调整</el-menu-item>
          <el-menu-item index="/purchasing/orders">采购单</el-menu-item>
          <el-menu-item index="/purchasing/receipts">到货单</el-menu-item>
          <el-menu-item index="/sales">销售记录</el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="app-layout__main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
// 应用布局壳：顶栏（团队名 + 当前角色 + 登出） + 侧栏（已实现页面导航） + 内容区。
// 红线：不承载任何业务规则；角色仅作展示（体验层），服务端仍是唯一安全边界。
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const teamStore = useTeamStore()

/** 写页入口显隐（体验层）：与路由 `meta.roles` 的 WRITE_ROLES 口径一致。 */
const canWrite = computed(() =>
  hasAnyRole(teamStore.role, ['OWNER', 'ADMIN', 'OPERATOR', 'APPROVER']),
)

/** 当前激活菜单项：优先精确匹配，其次匹配 `/catalog/...` 前缀（如商品下 SKU 详情）。 */
const activePath = computed(() => {
  const path = route.path
  if (path.startsWith('/catalog/')) {
    return path
  }
  return path
})

async function onLogout(): Promise<void> {
  await userStore.signOut()
  await router.push('/login')
}
</script>

<style scoped>
.app-layout {
  height: 100%;
}
.app-layout__header {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #001529;
  color: #fff;
}
.app-layout__brand {
  font-size: 16px;
  font-weight: 600;
}
.app-layout__grow {
  flex: 1;
}
.app-layout__session {
  display: flex;
  align-items: center;
  gap: 12px;
}
.app-layout__user {
  opacity: 0.85;
}
.app-layout__body {
  height: calc(100% - 60px);
}
.app-layout__aside {
  background: #fff;
  border-right: 1px solid #ebeef5;
}
.app-layout__menu {
  border-right: none;
}
.app-layout__main {
  background: #f5f7fa;
}
</style>
