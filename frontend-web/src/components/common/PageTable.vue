<template>
  <div class="page-table">
    <el-table
      v-loading="loading"
      :data="rows"
      :row-key="rowKey"
      border
      stripe
      size="default"
      class="page-table__table"
    >
      <el-table-column
        v-for="col in columns"
        :key="col.prop"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align || 'left'"
        :show-overflow-tooltip="col.tooltip !== false"
      >
        <template v-if="col.slot" #default="scope">
          <slot :name="col.slot" :row="scope.row" :index="scope.$index" />
        </template>
      </el-table-column>

      <!-- 操作列：由父组件通过 #actions 提供按钮（通常按权限位显隐）。 -->
      <el-table-column
        v-if="$slots.actions"
        label="操作"
        :width="actionsWidth"
        align="center"
        fixed="right"
      >
        <template #default="scope">
          <slot name="actions" :row="scope.row" :index="scope.$index" />
        </template>
      </el-table-column>

      <template #empty>
        <el-empty description="暂无数据" :image-size="80" />
      </template>
    </el-table>

    <div class="page-table__pager">
      <el-pagination
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
// 通用分页表格：列配置驱动 + 行级具名插槽（自定义单元格）+ 操作列插槽 + 远程分页。
// 红线：只做展示与分页事件，不含任何业务规则（排序/筛选语义由服务端定义）。
//
// 说明：`rows` 的行结构由各业务视图自行保证，通用表格不感知具体实体，故行类型为 any
// （列配置 `columns` 仍是强类型，见 `@/types/ui`）。
import type { TableColumn } from '@/types/ui'

/** 表格行（结构由调用方保证）。 */
type TableRow = any

withDefaults(
  defineProps<{
    columns: TableColumn[]
    rows: TableRow[]
    loading?: boolean
    total?: number
    page?: number
    pageSize?: number
    /** 行唯一键（用于表格内部优化，可选）。 */
    rowKey?: string
    /** 操作列宽度。 */
    actionsWidth?: number | string
  }>(),
  {
    loading: false,
    total: 0,
    page: 1,
    pageSize: 20,
    rowKey: 'id',
    actionsWidth: 200,
  },
)

const emit = defineEmits<{
  (e: 'page-change', page: number): void
  (e: 'size-change', size: number): void
}>()

function onPageChange(page: number): void {
  emit('page-change', page)
}

function onSizeChange(size: number): void {
  emit('size-change', size)
}
</script>

<style scoped>
.page-table__table {
  width: 100%;
}
.page-table__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
