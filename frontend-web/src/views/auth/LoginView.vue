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
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" :loading="loading" native-type="submit" block>
          登录
        </el-button>
      </el-form>
      <!-- TODO: 接入真实登录逻辑：调用 auth.login，写入 user store 的 token 与 userInfo -->
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules<typeof form> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// TODO: 真实登录逻辑（调用 @/api/auth 的 login，并写入 stores/user）。
function onSubmit(): void {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (valid) {
      loading.value = true
      // 骨架占位：暂不发起真实请求。
      window.setTimeout(() => {
        loading.value = false
      }, 500)
    }
  })
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
</style>
