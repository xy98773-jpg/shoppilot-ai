// 路由表（骨架结构）。
// 说明：本里程碑仅实现 LoginView（占位），其余页面为骨架占位组件，
// 不承载任何业务页面逻辑。各页面视图将在后续里程碑逐步补齐。
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { defineComponent, h } from 'vue'

import LoginView from '@/views/auth/LoginView.vue'

// 通用占位组件：仅用于体现路由结构，不含业务逻辑。
const Placeholder = defineComponent({
  name: 'Placeholder',
  props: { name: { type: String, default: '页面' } },
  setup(props) {
    return () => h('div', `${props.name}（骨架占位，待实现）`)
  },
})

// 鉴权相关 meta 约定：
// - requiresAuth: 是否需要登录（默认 true）
// - title: 页面标题
// - roles: 允许访问的角色（体验层；真正鉴权在服务端）
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { requiresAuth: false, title: '登录' },
  },
  {
    path: '/',
    redirect: '/dashboard',
    meta: { requiresAuth: true },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '工作台' },
  },
  {
    path: '/catalog',
    redirect: '/catalog/products',
    meta: { requiresAuth: true },
  },
  {
    path: '/catalog/products',
    name: 'catalog-products',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '商品列表', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/catalog/skus',
    name: 'catalog-skus',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: 'SKU 列表', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/catalog/suppliers',
    name: 'catalog-suppliers',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '供应商列表', roles: ['OWNER', 'ADMIN'] },
  },
  {
    path: '/catalog/warehouses',
    name: 'catalog-warehouses',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '仓库列表', roles: ['OWNER', 'ADMIN'] },
  },
  {
    path: '/importdata',
    redirect: '/importdata/wizard',
    meta: { requiresAuth: true },
  },
  {
    path: '/importdata/wizard',
    name: 'importdata-wizard',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '导入向导', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/importdata/batches',
    name: 'importdata-batches',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '导入批次', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/inventory',
    redirect: '/inventory/overview',
    meta: { requiresAuth: true },
  },
  {
    path: '/inventory/overview',
    name: 'inventory-overview',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '库存概览', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/inventory/transactions',
    name: 'inventory-transactions',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '库存流水', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/inventory/count-adjust',
    name: 'inventory-count-adjust',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '盘点调整', roles: ['OWNER', 'ADMIN'] },
  },
  {
    path: '/sales',
    name: 'sales-list',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '销售记录', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/analysis',
    redirect: '/analysis/runs',
    meta: { requiresAuth: true },
  },
  {
    path: '/analysis/runs',
    name: 'analysis-runs',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '分析任务', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/analysis/runs/:id',
    name: 'analysis-run-detail',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '分析任务详情', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/replenishment',
    redirect: '/replenishment/suggestions',
    meta: { requiresAuth: true },
  },
  {
    path: '/replenishment/suggestions',
    name: 'replenishment-suggestions',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '补货建议', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/replenishment/review',
    name: 'replenishment-review',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '补货审核', roles: ['OWNER', 'ADMIN'] },
  },
  {
    path: '/purchasing',
    redirect: '/purchasing/orders',
    meta: { requiresAuth: true },
  },
  {
    path: '/purchasing/orders',
    name: 'purchasing-orders',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '采购订单', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/purchasing/receipts',
    name: 'purchasing-receipts',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '收货记录', roles: ['OWNER', 'ADMIN', 'STAFF'] },
  },
  {
    path: '/audit',
    name: 'audit-logs',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '审计日志', roles: ['OWNER', 'ADMIN'] },
  },
  {
    path: '/team',
    redirect: '/team/members',
    meta: { requiresAuth: true },
  },
  {
    path: '/team/members',
    name: 'team-members',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '成员管理', roles: ['OWNER', 'ADMIN'] },
  },
  {
    path: '/team/settings',
    name: 'team-settings',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '团队设置', roles: ['OWNER'] },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => Promise.resolve(Placeholder),
    meta: { requiresAuth: true, title: '未找到' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
