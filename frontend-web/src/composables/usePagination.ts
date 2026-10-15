// 分页钩子（骨架）。
import { reactive, ref } from 'vue'
import type { PageQuery, PageResult } from '@/types/common'

/**
 * 通用分页状态管理。
 * @param fetcher 接收分页查询参数、返回分页结果的异步函数。
 */
export function usePagination<T>(fetcher: (query: PageQuery) => Promise<PageResult<T>>) {
  const query = reactive<PageQuery>({ page: 1, size: 20 })
  const list = ref<T[]>([])
  const total = ref(0)
  const loading = ref(false)

  async function load(): Promise<void> {
    loading.value = true
    try {
      const res = await fetcher({ ...query })
      list.value = res.records
      total.value = res.total
    } finally {
      loading.value = false
    }
  }

  function changePage(page: number): void {
    query.page = page
    void load()
  }

  function changeSize(size: number): void {
    query.size = size
    query.page = 1
    void load()
  }

  return { query, list, total, loading, load, changePage, changeSize }
}
