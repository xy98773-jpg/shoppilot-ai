<template>
  <div class="dashboard">
    <el-card shadow="never">
      <template #header>
        <span>工作台</span>
      </template>
      <p class="dashboard__welcome">
        欢迎，{{ userStore.displayName || userStore.user?.username || '用户' }}。
      </p>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="用户 ID">{{ userStore.user?.user_id ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="登录名">{{ userStore.user?.username ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="展示名">{{ userStore.user?.display_name ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="账号状态">{{ userStore.user?.status ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="团队 ID">{{ teamStore.teamId ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="团队名称">{{ teamStore.teamName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="团队时区">{{ teamStore.timezone || '—' }}</el-descriptions-item>
        <el-descriptions-item label="当前角色">{{ teamStore.role || '—' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never" class="dashboard__perms">
      <template #header>
        <span>能力位（来自 <code>GET /auth/me</code>；仅用于界面显隐，非安全边界）</span>
      </template>
      <el-tag
        v-for="(granted, key) in userStore.permissions"
        :key="key"
        :type="granted ? 'success' : 'info'"
        class="dashboard__perm-tag"
        :title="`${key}（${granted ? '已授予' : '未授予'}）`"
      >
        {{ capabilityLabel(String(key)) }} {{ granted ? '✓' : '✗' }}
      </el-tag>
      <el-empty v-if="!permKeys.length" description="未获取到能力位" :image-size="60" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 工作台最小落地页：欢迎 + 当前用户 / 团队 / 角色 / 能力位展示（只读，无业务规则）。
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { useTeamStore } from '@/stores/team'

const userStore = useUserStore()
const teamStore = useTeamStore()

const permKeys = computed(() => Object.keys(userStore.permissions))

/**
 * 能力位 key → 中文标签。
 *
 * ★ 语义以 `USER_ROLES.md §2.2` 权限矩阵 14 行为准（与后端
 * `identity/.../Permission.java` javadoc 逐行对应），**不自造**。
 * 后端 JSON key 是枚举名小写（`Permission#key()`）。
 * 未登记的 key 兜底显示原键（后端新增能力位时前端不白屏）。
 */
const CAPABILITY_LABELS: Record<string, string> = {
  import_data: '数据导入',
  master_data_manage: '主数据管理',
  inventory_adjust: '库存调整',
  create_analysis: '发起分析',
  view_analysis: '查看分析',
  edit_replenishment_qty: '修改建议量',
  submit_review: '提交审核',
  approve: '审批',
  create_purchase_order: '创建采购单',
  register_receipt: '到货登记',
  view_audit: '查看审计',
  manage_members: '成员管理',
  manage_team_config: '团队配置',
  transfer_ownership: '转让所有权',
}

function capabilityLabel(key: string): string {
  return CAPABILITY_LABELS[key] ?? key
}
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.dashboard__welcome {
  margin-top: 0;
}
.dashboard__perm-tag {
  margin: 0 8px 8px 0;
}
</style>
