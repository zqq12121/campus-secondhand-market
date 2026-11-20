<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import type { ApiError } from '@/shared/request'
import { useAdminAuth } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAdminAuth()
const form = reactive({ username: '', password: '' })
const error = ref('')
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) return (error.value = '请输入账号和密码')
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.username.trim(), form.password)
    router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard')
  } catch (e) {
    error.value = (e as ApiError).message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="bg">
    <form class="box card card-pad stack" @submit.prevent="submit">
      <div class="logo" style="justify-content: center"><span class="logo-mark"><AppIcon name="package" /></span>WU 校园闲置 · 管理后台</div>
      <div class="field"><label for="a-u">管理员账号</label>
        <div class="input-affix"><AppIcon name="user" /><input id="a-u" v-model="form.username" class="input" autocomplete="username" /></div></div>
      <div class="field"><label for="a-p">密码</label>
        <div class="input-affix"><AppIcon name="lock" /><input id="a-p" v-model="form.password" class="input" type="password" autocomplete="current-password" /></div>
        <span v-if="error" class="error" role="alert">{{ error }}</span></div>
      <button class="btn btn-primary btn-lg btn-block" type="submit" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
      <p class="subtle" style="text-align: center">管理员账号由超级管理员创建</p>
    </form>
  </div>
</template>

<style scoped>
.bg { min-height: 100vh; display: grid; place-items: center; background: #0B2A22; padding: 16px; }
.box { width: 100%; max-width: 380px; }
</style>
