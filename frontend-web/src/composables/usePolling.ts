// 轮询钩子（`M4-B21b` 强化）。
//
// 规格（PROJECT_STRUCTURE §6 + M4-B21b 任务书）：
//   - 2s 起，指数退避（×2）至 10s 上限；
//   - 终态（SUCCEEDED/DEGRADED/FAILED/TIMEOUT/CANCELLED）由调用方 `shouldStop` 判定后自动停；
//   - 页面卸载（`onUnmounted`）必须停止：清理 timer + `AbortController.abort()`。
//
// ★ 兼容性：参数与返回值均为**增量扩展**（`task` 新增可选 `signal` 形参，
//   `options` 新增 `factor` / `immediate` / `shouldStop`），零参任务照旧可用。
import { onUnmounted, ref } from 'vue'

/** 轮询任务：接收 `AbortSignal`，卸载/停止时会被 abort（可用于取消在途请求）。 */
export type PollingTask = (signal: AbortSignal) => Promise<void>

/** 轮询配置。 */
export interface PollingOptions {
  /** 初始延迟（毫秒），默认 2000。 */
  delay?: number
  /** 最大延迟（毫秒），默认 10000。 */
  maxDelay?: number
  /** 退避倍数，默认 2。 */
  factor?: number
  /** 是否 `start()` 后立即执行一次（不等首个 delay），默认 false。 */
  immediate?: boolean
  /** 返回 true 时停止轮询（终态判定），默认不停止。 */
  shouldStop?: () => boolean
}

/**
 * 指数退避轮询。
 *
 * @example
 * const polling = usePolling((signal) => fetchStatus(signal), {
 *   shouldStop: () => isTerminalStatus(run.value?.status),
 * })
 * onMounted(() => polling.start())
 */
export function usePolling(task: PollingTask, options: PollingOptions = {}) {
  const { delay = 2000, maxDelay = 10000, factor = 2, immediate = false, shouldStop } = options
  const loading = ref(false)
  const stopped = ref(true)

  let timer: ReturnType<typeof setTimeout> | null = null
  let currentDelay = delay
  let controller: AbortController | null = null
  /** 下一次 tick 是否为 `immediate` 触发的首跑（首跑后仍按 delay 起算，而非已翻倍）。 */
  let firstRun = false

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function schedule(): void {
    if (stopped.value) return
    timer = setTimeout(() => {
      timer = null
      void tick()
    }, currentDelay)
  }

  async function tick(): Promise<void> {
    if (stopped.value) return
    loading.value = true
    controller = new AbortController()
    try {
      await task(controller.signal)
    } catch {
      // 轮询失败不自行终止：错误提示由任务自身负责（网络抖动不应打死轮询）。
    } finally {
      loading.value = false
      controller = null
    }
    if (stopped.value) return
    // 终态：不再发起下一次请求
    if (shouldStop?.()) {
      stop()
      return
    }
    if (firstRun) {
      // immediate 首跑不算一次「退避间隔」，下一次仍从 delay 起算
      firstRun = false
    } else {
      currentDelay = Math.min(currentDelay * factor, maxDelay)
    }
    schedule()
  }

  /** 停止轮询：清 timer + abort 在途请求（幂等）。 */
  function stop(): void {
    stopped.value = true
    clearTimer()
    if (controller) {
      controller.abort()
      controller = null
    }
  }

  /** 启动轮询（重复调用不会叠加 timer）。 */
  function start(): void {
    if (!stopped.value && (timer !== null || loading.value)) return
    stopped.value = false
    currentDelay = delay
    if (immediate) {
      firstRun = true
      void tick()
    } else {
      firstRun = false
      schedule()
    }
  }

  onUnmounted(stop)

  return { loading, stopped, start, stop }
}
