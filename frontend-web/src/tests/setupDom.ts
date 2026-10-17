// jsdom 环境补齐（仅测试用，不参与构建产物）。
//
// Element Plus 的表格/弹窗依赖 `ResizeObserver` 与 `matchMedia`，jsdom 默认不实现，
// 缺少时组件挂载会抛错。这里提供最小桩：只保证「不崩」，不模拟布局计算。

class ResizeObserverStub {
  observe(): void {
    /* noop */
  }

  unobserve(): void {
    /* noop */
  }

  disconnect(): void {
    /* noop */
  }
}

if (!('ResizeObserver' in globalThis)) {
  ;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub
}

if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia
}

export {}
