// 通用 UI 组件配置类型（纯类型，不依赖任何层）。
//
// 抽到 `types/` 的原因：`components/common/*.vue` 以 `props` 暴露配置，
// 而 `<script setup>` 内**不允许出现 ES module export**，故配置类型必须外置，
// 供各列表页复用（否则调用方只能靠字面量推断，丢失 `type` 字面量校验）。

/** 通用分页表格的列配置（`components/common/PageTable.vue`）。 */
export interface TableColumn {
  /** 字段名（同时用作 `el-table-column` 的 `prop`；配 `slot` 时可仅作 key）。 */
  prop: string
  label: string
  /** 固定宽度。 */
  width?: number | string
  /** 最小宽度（与 `width` 二选一，通常数据列用 `minWidth`）。 */
  minWidth?: number | string
  align?: 'left' | 'center' | 'right'
  /** 自定义单元格的具名插槽名；提供时该列由插槽渲染。 */
  slot?: string
  /** 是否显示溢出省略 tooltip，缺省 `true`。 */
  tooltip?: boolean
}

/** 通用搜索表单的字段配置（`components/common/SearchForm.vue`）。 */
export interface SearchField {
  /** 参数字段名（提交时以该名平铺上传）。 */
  prop: string
  label: string
  /** 控件类型，缺省 `input`。 */
  type?: 'input' | 'select'
  placeholder?: string
  /** `select` 型字段的可选项。 */
  options?: { label: string; value: string | number }[]
  /** 控件宽度（px）。 */
  width?: number
}
