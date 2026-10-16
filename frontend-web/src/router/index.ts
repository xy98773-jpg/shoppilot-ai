// 路由表。
//
// ★ 角色口径（M2-T12 修正，权威来源 `docs/00-product/USER_ROLES.md §1.1 / §2.2`）：
//   - 合法角色**只有 5 个**：OWNER / ADMIN / OPERATOR / APPROVER / VIEWER。
//     M1 骨架误用了不存在的 `STAFF`（已全部清除；全仓不应再出现）。
//   - `meta.roles` 是**体验层**约定，`guards.ts` 会据此拦截；**服务端仍是唯一安全边界**。
//   - 分类规则（按 §2.2 矩阵）：
//       · 只读页（VIEWER 及以上可看）→ **不写 `roles` 键**，交由服务端把关；
//       · OPERATOR+ 写页（矩阵中 VIEWER ❌ 且 OPERATOR ✅）→ ['OWNER','ADMIN','OPERATOR','APPROVER']；
//       · 审批页（矩阵 #8，仅 OWNER/ADMIN/APPROVER）→ ['OWNER','ADMIN','APPROVER']；
//       · 管理页（矩阵 #11/#12/#13）→ ['OWNER','ADMIN']。
//
// ★ 本批仅实现：登录页、布局壳、工作台、主数据四页、403/404。
//   其余页面**保持 Placeholder 不动**（属后续里程碑：导入 / 库存 / 销售 / 分析 / 补货 / 采购 / 团队 / 审计）。
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { defineComponent, h } from 'vue'

import AppLayout from '@/components/layout/AppLayout.vue'
import LoginView from '@/views/auth/LoginView.vue'
import DashboardView from '@/views/dashboard/DashboardView.vue'
import ForbiddenView from '@/views/error/ForbiddenView.vue'
import NotFoundView from '@/views/error/NotFoundView.vue'

// 通用占位组件：仅用于体现路由结构，不含业务逻辑（后续里程碑替换为真实页面）。
const Placeholder = defineComponent({
  name: 'Placeholder',
  props: { name: { type: String, default: '页面' } },
  setup(props) {
    return () => h('div', `${props.name}（骨架占位，待实现）`)
  },
})

/** 矩阵 #1/#2/#3/#9/#10 的「OPERATOR 及以上」写角色（四名显式列举，含 APPROVER）。 */
const WRITE_ROLES = ['OWNER', 'ADMIN', 'OPERATOR', 'APPROVER']

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { requiresAuth: false, title: '登录' },
  },
  {
    path: '/',
    component: AppLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'home', redirect: '/dashboard' },

      // ---- 工作台（只读页；任何已登录成员） ----
      {
        path: 'dashboard',
        name: 'dashboard',
        component: DashboardView,
        meta: { title: '工作台' },
      },

      // ---- 主数据四页（只读页：VIEWER 亦可查看；写操作按钮在页面内按权限位显隐） ----
      { path: 'catalog', redirect: '/catalog/products' },
      {
        path: 'catalog/products',
        name: 'catalog-products',
        component: () => import('@/views/catalog/ProductList.vue'),
        meta: { title: '商品列表' },
      },
      {
        path: 'catalog/skus',
        name: 'catalog-skus',
        component: () => import('@/views/catalog/SkuList.vue'),
        meta: { title: 'SKU 列表' },
      },
      {
        path: 'catalog/suppliers',
        name: 'catalog-suppliers',
        component: () => import('@/views/catalog/SupplierList.vue'),
        meta: { title: '供应商列表' },
      },
      {
        path: 'catalog/warehouses',
        name: 'catalog-warehouses',
        component: () => import('@/views/catalog/WarehouseList.vue'),
        meta: { title: '仓库列表' },
      },

      // ---- 以下为后续里程碑页面（本批保持 Placeholder，仅订正角色口径） ----
      { path: 'importdata', redirect: '/importdata/wizard' },
      {
        path: 'importdata/wizard',
        name: 'importdata-wizard',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '导入向导', roles: WRITE_ROLES },
      },
      {
        path: 'importdata/batches',
        name: 'importdata-batches',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '导入批次', roles: WRITE_ROLES },
      },

      { path: 'inventory', redirect: '/inventory/overview' },
      {
        path: 'inventory/overview',
        name: 'inventory-overview',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '库存概览' },
      },
      {
        path: 'inventory/transactions',
        name: 'inventory-transactions',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '库存流水' },
      },
      {
        path: 'inventory/count-adjust',
        name: 'inventory-count-adjust',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '盘点调整', roles: WRITE_ROLES },
      },

      {
        path: 'sales',
        name: 'sales-list',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '销售记录' },
      },

      { path: 'analysis', redirect: '/analysis/runs' },
      {
        path: 'analysis/runs',
        name: 'analysis-runs',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '分析任务' },
      },
      {
        path: 'analysis/runs/:id',
        name: 'analysis-run-detail',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '分析任务详情' },
      },

      { path: 'replenishment', redirect: '/replenishment/suggestions' },
      {
        path: 'replenishment/suggestions',
        name: 'replenishment-suggestions',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '补货建议' },
      },
      {
        path: 'replenishment/review',
        name: 'replenishment-review',
        component: () => Promise.resolve(Placeholder),
        // 矩阵 #8 审批：仅 OWNER / ADMIN / APPROVER（OPERATOR ❌）
        meta: { title: '补货审核', roles: ['OWNER', 'ADMIN', 'APPROVER'] },
      },

      { path: 'purchasing', redirect: '/purchasing/orders' },
      {
        path: 'purchasing/orders',
        name: 'purchasing-orders',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '采购订单', roles: WRITE_ROLES },
      },
      {
        path: 'purchasing/receipts',
        name: 'purchasing-receipts',
        component: () => Promise.resolve(Placeholder),
        meta: { title: '收货记录', roles: WRITE_ROLES },
      },

      {
        path: 'audit',
        name: 'audit-logs',
        component: () => Promise.resolve(Placeholder),
        // 矩阵 #11 查看审计：仅管理角色
        meta: { title: '审计日志', roles: ['OWNER', 'ADMIN'] },
      },

      { path: 'team', redirect: '/team/members' },
      {
        path: 'team/members',
        name: 'team-members',
        component: () => Promise.resolve(Placeholder),
        // 矩阵 #12 成员管理
        meta: { title: '成员管理', roles: ['OWNER', 'ADMIN'] },
      },
      {
        path: 'team/settings',
        name: 'team-settings',
        component: () => Promise.resolve(Placeholder),
        // 矩阵 #13 团队配置
        meta: { title: '团队设置', roles: ['OWNER', 'ADMIN'] },
      },

      // ---- 错误页 ----
      {
        path: '403',
        name: 'forbidden',
        component: ForbiddenView,
        meta: { title: '无访问权限' },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: NotFoundView,
        meta: { title: '页面不存在' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
