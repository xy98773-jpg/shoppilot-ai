// 轮询钩子（骨架）。
// 规格（PROJECT_STRUCTURE §6）：2s→10s 指数退避轮询，页面卸载停止。
import { onUnmounted, ref } from 'vue'

/**
 * 指数退避轮询。
 * @param task 每次轮询执行的异步任务（返回 Promise）。
 * @param options.delay 初始延迟（毫秒），默认 2000。
 * @param options.maxDelay 最大延迟（毫秒），默认 10000。
 */
export function usePolling(
  task: () => Promise<void>,
  options: { delay?: number; maxDelay?: number } = {},
) {
  const { delay = 2000, maxDelay = 10000 } = options
  const loading = ref(false)
  let timer: number | null = null
  let currentDelay = delay
  let stopped = false

  function schedule(): void {
    if (stopped) return
    timer = window.setTimeout(async () => {
      if (stopped) return
      loading.value = true
      try {
        await task()
      } finally {
        loading.value = false
      }
      // 指数退避，封顶 maxDelay
      currentDelay = Math.min(currentDelay * 2, maxDelay)
      schedule()
    }, currentDelay)
  }

  function stop(): void {
    stopped = true
    if (timer !== null) {
      window.clearTimeout(timer)
      timer = null
    }
  }

  function start(): void {
    stopped = false
    currentDelay = delay
    schedule()
  }

  onUnmounted(stop)

  return { loading, start, stop }
}
