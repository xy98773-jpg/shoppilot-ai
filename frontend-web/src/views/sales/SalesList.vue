<template>
  <div class="sales-page">
    <el-card shadow="never">
      <template #header>
        <div class="sales-page__head">
          <span class="sales-page__title">销售记录</span>
          <span class="sales-page__subtitle">只读：销售数据经导入进入（F-SAL-01），本页不提供录入</span>
        </div>
      </template>

      <el-form :inline="true" class="sales-page__filters">
        <el-form-item label="SKU ID">
          <el-input v-model="filters.sku_id" placeholder="如 9002" style="width: 120px" />
        </el-form-item>
        <el-form-item label="仓库 ID">
          <el-input v-model="filters.warehouse_id" placeholder="如 9004" style="width: 120px" />
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker
            v-model="filters.dateFrom"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="yyyy-MM-dd"
            style="width: 155px"
          />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker
            v-model="filters.dateTo"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="yyyy-MM-dd"
            style="width: 155px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <PageTable
        :columns="columns"
        :rows="list"
        :loading="loading"
        :total="total"
        :page="query.page"
        :page-size="query.pageSize"
        @page-change="changePage"
        @size-change="changeSize"
      >
        <template #createdAt="{ row }">{{ formatDateTime(row.created_at) }}</template>
        <template #source="{ row }">{{ sourceLabel(row.source) }}</template>
      </PageTable>

      <p class="sales-page__note">
        数量与金额一律原样展示后端返回的字符串（如 "12.000" / "1080.00"），前端不做精确算术（§14.2）。
      </p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 销售记录列表（F-SAL-02，只读）。
//
// ★ 只读：销售数据经导入进入（PRD F-SAL-01），本批不做录入表单（任务书 T18-4 明确）。
// ★ 数量/金额原样展示字符串，不重算净销量、不做金额汇总（口径铁律 ④）。
// ★ 参数名 `dateFrom` / `dateTo` 逐字对齐后端（驼峰），见 `salesQuery.ts`。
import { onMounted, reactive } from 'vue'
import PageTable from '@/components/common/PageTable.vue'
import { listSalesDaily } from '@/api/sales'
import type { SalesDailyQuery, SalesDailyRow } from '@/types/sales'
import type { TableColumn } from '@/types/ui'
import { usePagination } from '@/composables/usePagination'
import { formatDateTime } from '@/utils/format'
import { sourceLabel } from '@/utils/terms'
import { buildSalesQuery, type SalesFilterInput } from './salesQuery'

const filters = reactive<SalesFilterInput>({
  sku_id: '',
  warehouse_id: '',
  dateFrom: '',
  dateTo: '',
})

const columns: TableColumn[] = [
  { prop: 'sale_date', label: '业务日期', width: 130 },
  { prop: 'warehouse_id', label: '仓库 ID', width: 100 },
  { prop: 'sku_code', label: 'SKU（商品规格）', minWidth: 130 },
  { prop: 'qty', label: '净销量', width: 120 },
  { prop: 'return_qty', label: '退货量', width: 110 },
  { prop: 'sales_amount', label: '销售金额', width: 130 },
  { prop: 'order_count', label: '订单数', width: 100 },
  { prop: 'source', label: '来源', width: 140, slot: 'source' },
  { prop: 'created_at', label: '写入时间', minWidth: 170, slot: 'createdAt' },
]

const { list, total, loading, query, load, changePage, changeSize } = usePagination<SalesDailyRow>(
  (q) => listSalesDaily(buildSalesQuery(filters, q.page, q.pageSize) as SalesDailyQuery),
)

function onSearch(): void {
  query.page = 1
  void load()
}

function onReset(): void {
  filters.sku_id = ''
  filters.warehouse_id = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  query.page = 1
  void load()
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.sales-page__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.sales-page__title {
  font-weight: 600;
}
.sales-page__subtitle {
  color: #909399;
  font-size: 12px;
}
.sales-page__filters {
  margin-bottom: 4px;
}
.sales-page__note {
  margin: 12px 0 0;
  color: #909399;
  font-size: 12px;
}
</style>
