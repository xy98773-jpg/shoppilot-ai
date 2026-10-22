// 路由表。
//
// ★ 角色口径（M2-T12 修正，权威来源 `docs/00-product/USER_ROLES.md §1.1 / §2.2`）：
//   - 合法角色**只有 5 个**：OWNER / ADMIN / OPERATOR / APPROVER / VIEWER。
//     M1 骨架曾误用一个不存在的只读角色码（已全部清除；本文件不再出现该字面量，
//     以便「全仓检索 → 0 命中」可机械核验）。
//   - `meta.roles` 是**体验层**约定，`guards.ts` 会据此拦截；**服务端仍是唯一安全边界**。
//   - 分类规则（按 §2.2 矩阵）：
//       · 只读页（VIEWER 及以上可看）→ **不写 `roles` 键**，交由服务端把关；
//       · OPERATOR+ 写页（矩阵中 VIEWER ❌ 且 OPERATOR ✅）→ ['OWNER','ADMIN','OPERATOR','APPROVER']；
//       · 审批页（矩阵 #8，仅 OWNER/ADMIN/APPROVER）→ ['OWNER','ADMIN','APPROVER']；
//       · 管理页（矩阵 #11/#12/#13）→ ['OWNER','ADMIN']。
//
// ★ 已实现：登录页、布局壳、工作台、主数据四页、403/404（M2-T12）；
//   导入两页 + 库存三页 + 销售一页（M3-B10 / T18）；
//   分析三页（M4）+ 采购两页（列表/详情，M6-T11）。
//   其余页面**保持 Placeholder 不动**（属后续里程碑：补货 / 团队 / 审计）。
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

      // ---- 导入（M3-B10 / T18：导入向导 + 批次管理；OPERATOR+ 写页） ----
      { path: 'importdata', redirect: '/importdata/wizard' },
      {
        path: 'importdata/wizard',
        name: 'importdata-wizard',
        component: () => import('@/views/importdata/ImportWizard.vue'),
        meta: { title: '导入向导', roles: WRITE_ROLES },
      },
      {
        path: 'importdata/batches',
        name: 'importdata-batches',
        component: () => import('@/views/importdata/ImportBatchList.vue'),
        meta: { title: '导入批次', roles: WRITE_ROLES },
      },

      // ---- 库存（M3-B10 / T18：概览 / 流水 / 盘点调整） ----
      { path: 'inventory', redirect: '/inventory/overview' },
      {
        path: 'inventory/overview',
        name: 'inventory-overview',
        component: () => import('@/views/inventory/InventoryOverview.vue'),
        meta: { title: '库存概览' },
      },
      {
        path: 'inventory/transactions',
        name: 'inventory-transactions',
        component: () => import('@/views/inventory/TransactionList.vue'),
        meta: { title: '库存流水' },
      },
      {
        path: 'inventory/count-adjust',
        name: 'inventory-count-adjust',
        component: () => import('@/views/inventory/CountAdjust.vue'),
        meta: { title: '盘点调整', roles: WRITE_ROLES },
      },

      // ---- 销售（M3-B10 / T18：只读列表） ----
      {
        path: 'sales',
        name: 'sales-list',
        component: () => import('@/views/sales/SalesList.vue'),
        meta: { title: '销售记录' },
      },

      { path: 'analysis', redirect: '/analysis/runs' },
      {
        path: 'analysis/runs',
        name: 'analysis-runs',
        component: () => import('@/views/analysis/AnalysisRunList.vue'),
        meta: { title: '分析任务' },
      },
      // ★ 静态段必须排在 `:id` 之前（避免 "new" 被当成 run_id）
      {
        path: 'analysis/runs/new',
        name: 'analysis-run-create',
        component: () => import('@/views/analysis/AnalysisRunCreate.vue'),
        // 创建是写操作：与后端 @RequireRole(OPERATOR+) 对齐（体验层）
        meta: { title: '新建分析任务', roles: WRITE_ROLES },
      },
      {
        path: 'analysis/runs/:id',
        name: 'analysis-run-detail',
        component: () => import('@/views/analysis/AnalysisRunDetail.vue'),
        meta: { title: '分析任务详情' },
      },

      { path: 'replenishment', redirect: '/replenishment/suggestions' },
      {
        path: 'replenishment/suggestions',
        name: 'replenishment-suggestions',
        // M8-R200：真实页面（列表 + 提交/撤回/作废 + 勾选转采购单）
        component: () => import('@/views/replenishment/ReplenishmentList.vue'),
        meta: { title: '补货建议' },
      },
      {
        path: 'replenishment/review',
        name: 'replenishment-review',
        // 与「补货建议」同一组件，按路由名切成「待审批队列」模式
        component: () => import('@/views/replenishment/ReplenishmentList.vue'),
        // 矩阵 #8 审批：仅 OWNER / ADMIN / APPROVER（OPERATOR ❌）
        meta: { title: '补货审核', roles: ['OWNER', 'ADMIN', 'APPROVER'] },
      },

      // ---- 采购（M6-T11：采购单 / 到货单；列表与详情均为只读页，写操作按钮在页面内按角色显隐） ----
      { path: 'purchasing', redirect: '/purchasing/orders' },
      // ★ 静态段必须排在 `:id` 之前（与 analysis 段同规则）
      {
        path: 'purchasing/orders',
        name: 'purchasing-orders',
        component: () => import('@/views/purchasing/PurchaseOrderList.vue'),
        meta: { title: '采购单' },
      },
      {
        path: 'purchasing/orders/:id',
        name: 'purchasing-order-detail',
        component: () => import('@/views/purchasing/PurchaseOrderDetail.vue'),
        meta: { title: '采购单详情' },
      },
      {
        path: 'purchasing/receipts',
        name: 'purchasing-receipts',
        component: () => import('@/views/purchasing/GoodsReceiptList.vue'),
        meta: { title: '到货单' },
      },
      {
        path: 'purchasing/receipts/:id',
        name: 'purchasing-receipt-detail',
        component: () => import('@/views/purchasing/GoodsReceiptDetail.vue'),
        meta: { title: '到货单详情' },
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
