<template>
  <div class="page-table">
    <!-- 通用分页表格占位：后续接入 el-table + 分页，保持可复用，不依赖具体 views。 -->
    <slot />
  </div>
</template>

<script setup lang="ts">
// TODO: 通用分页表格组件（基于 el-table + el-pagination，支持列配置与远程分页）。
</script>
