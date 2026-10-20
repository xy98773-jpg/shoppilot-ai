// usePolling 单测（M4-B21b 硬验收项：卸载必须停止轮询）。
//
// 覆盖：
//   1. 2s 起、指数退避（×2）至 10s 上限；
//   2. `unmount()` 后 advanceTimersByTime 不再发起请求；
//   3. `shouldStop()` 返回 true（终态）后自动停；
//   4. `stop()` 会 abort 在途任务的 AbortSignal。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { usePolling, type PollingOptions, type PollingTask } from './usePolling'

function mountPolling(task: PollingTask, options?: PollingOptions) {
  let api: ReturnType<typeof usePolling> | null = null
  const Host = defineComponent({
    name: 'PollingHost',
    setup() {
      api = usePolling(task, options)
      return () => h('div', 'host')
    },
  })
  const wrapper = mount(Host)
  return {
    wrapper,
    get polling() {
      if (!api) throw new Error('usePolling 未初始化')
      return api
    },
  }
}

describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('按 2s → 4s → 8s → 10s 指数退避（封顶 10s）', async () => {
    const task = vi.fn().mockResolvedValue(undefined)
    const { polling } = mountPolling(task)

    polling.start()

    // 首个请求在 2s
    await vi.advanceTimersByTimeAsync(1999)
    expect(task).toHaveBeenCalledTimes(0)
    await vi.advanceTimersByTimeAsync(1)
    expect(task).toHaveBeenCalledTimes(1)

    // 第二次在 4s
    await vi.advanceTimersByTimeAsync(3999)
    expect(task).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(task).toHaveBeenCalledTimes(2)

    // 第三次在 8s
    await vi.advanceTimersByTimeAsync(7999)
    expect(task).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(task).toHaveBeenCalledTimes(3)

    // 之后封顶 10s
    await vi.advanceTimersByTimeAsync(9999)
    expect(task).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(1)
    expect(task).toHaveBeenCalledTimes(4)

    await vi.advanceTimersByTimeAsync(10000)
    expect(task).toHaveBeenCalledTimes(5)

    polling.stop()
  })

  it('页面卸载后不再发起请求（清 timer）', async () => {
    const task = vi.fn().mockResolvedValue(undefined)
    const { wrapper, polling } = mountPolling(task)

    polling.start()
    await vi.advanceTimersByTimeAsync(2000)
    expect(task).toHaveBeenCalledTimes(1)

    wrapper.unmount()

    // 卸载后推进 5 分钟：不得再有请求
    await vi.advanceTimersByTimeAsync(300_000)
    expect(task).toHaveBeenCalledTimes(1)
  })

  it('终态（shouldStop 为 true）后自动停止', async () => {
    let terminal = false
    const task = vi.fn().mockImplementation(async () => {
      terminal = true
    })
    const { polling } = mountPolling(task, { shouldStop: () => terminal })

    polling.start()
    await vi.advanceTimersByTimeAsync(2000)
    expect(task).toHaveBeenCalledTimes(1)
    expect(polling.stopped.value).toBe(true)

    await vi.advanceTimersByTimeAsync(300_000)
    expect(task).toHaveBeenCalledTimes(1)
  })

  it('stop() 会 abort 在途请求的 AbortSignal', async () => {
    const captured = {} as { signal?: AbortSignal }
    const task = vi.fn((signal: AbortSignal) => {
      captured.signal = signal
      return new Promise<void>(() => {
        /* 永不 resolve，模拟在途请求 */
      })
    })
    const { wrapper, polling } = mountPolling(task)

    polling.start()
    await vi.advanceTimersByTimeAsync(2000)
    const signal = captured.signal
    expect(signal).not.toBeNull()
    expect(signal?.aborted).toBe(false)

    polling.stop()
    expect(signal?.aborted).toBe(true)

    wrapper.unmount()
  })
})
