<template>
  <el-dialog v-model="visible" :title="title" width="420px" append-to-body @closed="onClosed">
    <span>{{ message }}</span>
    <template #footer>
      <el-button @click="onCancel">取消</el-button>
      <el-button type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
// 通用确认弹窗：以 Promise 暴露 `open(...)`，调用方 `const ok = await dialog.open({ message })`。
// ★ 关闭方式（确定 / 取消 / 右上角 X / ESC / 遮罩）一律会 resolve，Promise 不会悬挂。
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 默认标题。 */
    defaultTitle?: string
  }>(),
  { defaultTitle: '确认' },
)

const visible = ref(false)
const title = ref(props.defaultTitle)
const message = ref('')

/** 待决议回调；同一时刻只会有一个等待中的确认框。 */
let resolver: ((ok: boolean) => void) | null = null
/** 本次交互是否点了「确定」。 */
let confirmed = false

interface OpenOptions {
  title?: string
  message: string
}

/** 打开确认框；resolve 为用户是否确认。 */
function open(options: OpenOptions): Promise<boolean> {
  title.value = options.title ?? props.defaultTitle
  message.value = options.message
  confirmed = false
  visible.value = true
  return new Promise<boolean>((resolve) => {
    resolver = resolve
  })
}

function onConfirm(): void {
  confirmed = true
  visible.value = false
}

function onCancel(): void {
  confirmed = false
  visible.value = false
}

/** 弹窗真正关闭后统一决议（覆盖 X / ESC / 遮罩等所有关闭路径）。 */
function onClosed(): void {
  resolver?.(confirmed)
  resolver = null
  confirmed = false
}

defineExpose({ open })
</script>
