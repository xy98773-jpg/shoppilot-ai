<template>
  <div class="login-view">
    <el-card class="login-card" shadow="always">
      <h2 class="login-title">ShopPilot AI 登录</h2>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-alert
          v-if="errorText"
          :title="errorText"
          type="error"
          :closable="false"
          show-icon
          class="login-error"
        />

        <el-button type="primary" :loading="loading" native-type="submit" class="login-submit" block>
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 登录页：调用真实 `POST /auth/login`（契约 §11.1）→ 写入 token / user / team / role，
// 再经 `GET /auth/me` 水合权限位（见 stores/user.signIn）。
//
// 失败按**错误码**提示（契约 §10.1 主要错误码）：
//   AUTH-1002 用户名或密码错误 / AUTH-1003 账号已禁用 / COM-0001 参数校验失败（含 details）。
// ★ 前端不判定"谁是合法用户"（防枚举提示由服务端统一给出），也不做任何权限判定。
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { describeError, errorCodeOf } from '@/utils/error'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorText = ref('')

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules<typeof form> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 按服务端错误码给出面向用户的提示。 */
function describeLoginError(error: unknown): string {
  const code = errorCodeOf(error)
  switch (code) {
    case 'AUTH-1002':
      return '用户名或密码错误（AUTH-1002）'
    case 'AUTH-1003':
      return '账号已被禁用，请联系管理员（AUTH-1003）'
    case 'COM-0001':
      return `请求参数校验失败：${describeError(error)}`
    default:
      return describeError(error)
  }
}

/** 仅接受站内相对路径作为登录后跳转目标（防开放重定向）。 */
function safeRedirect(value: unknown): string {
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')) {
    return value
  }
  return '/'
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  errorText.value = ''
  try {
    await userStore.signIn({ username: form.username.trim(), password: form.password })
    await router.replace(safeRedirect(route.query.redirect))
  } catch (error) {
    errorText.value = describeLoginError(error)
    ElMessage.error(errorText.value)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-view {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: #f5f7fa;
}
.login-card {
  width: 360px;
}
.login-title {
  margin: 0 0 16px;
  text-align: center;
}
.login-error {
  margin-bottom: 12px;
}
.login-submit {
  margin-top: 4px;
}
</style>
