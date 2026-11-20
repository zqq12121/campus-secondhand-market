<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import { ApiError } from '@/shared/request'
import { authApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { useMetaStore } from '../stores/meta'

/** 登录 / 注册 / 找回密码（接口文档 3.1–3.5）。邮箱后缀固定 @wu.edu.cn，只让用户输入前缀。 */
const SUFFIX = '@wu.edu.cn'
const PWD_RULE = /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const meta = useMetaStore()

const tab = ref<'login' | 'register' | 'reset'>('login')
const loading = ref(false)
const login = reactive({ prefix: '', password: '', error: '' })
const reg = reactive({ prefix: '', code: '', nickname: '', campusId: '', password: '' })
const reset = reactive({ prefix: '', code: '', password: '' })
const countdown = ref(0)
let timer: number | undefined

const regPwdError = computed(() => (reg.password && !PWD_RULE.test(reg.password) ? '密码需 8–20 位，且同时包含字母和数字' : ''))
const email = (prefix: string) => prefix.trim().replace(/@.*$/, '') + SUFFIX

onMounted(async () => {
  await meta.load()
  reg.campusId ||= meta.campuses[0]?.id ?? ''
})
onBeforeUnmount(() => clearInterval(timer))

function done() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect)
}

async function doLogin() {
  if (!login.prefix || !login.password) return (login.error = '请填写邮箱和密码')
  loading.value = true
  login.error = ''
  try {
    auth.applyLogin(await authApi.login(email(login.prefix), login.password))
    done()
  } catch (e) {
    const err = e as ApiError
    const d = err.data as { remainingAttempts?: number; retryAfter?: number; banUntil?: string | null } | null
    if (err.code === 1005 && d?.remainingAttempts != null) login.error = `邮箱或密码错误，还可以再试 ${d.remainingAttempts} 次`
    else if (err.code === 1006 && d?.retryAfter) login.error = `失败次数过多，请 ${Math.ceil(d.retryAfter / 60)} 分钟后再试`
    else if (err.code === 1009) login.error = d?.banUntil ? `账号已封禁至 ${d.banUntil}` : '账号已被永久封禁'
    else login.error = err.message
  } finally {
    loading.value = false
  }
}

async function sendCode(prefix: string, scene: 'REGISTER' | 'RESET_PASSWORD') {
  if (!prefix.trim()) return ElMessage.warning('请先填写邮箱')
  await authApi.sendCode(email(prefix), scene)
  ElMessage.success('验证码已发送，5 分钟内有效')
  countdown.value = 60
  clearInterval(timer)
  timer = window.setInterval(() => { if (--countdown.value <= 0) clearInterval(timer) }, 1000)
}

async function doRegister() {
  if (!reg.prefix || !reg.code || !reg.nickname || !reg.campusId || !reg.password) return ElMessage.warning('请把信息填写完整')
  if (reg.nickname.trim().length < 2) return ElMessage.warning('昵称需要 2–16 个字')
  if (regPwdError.value) return
  loading.value = true
  try {
    auth.applyLogin(await authApi.register({ email: email(reg.prefix), code: reg.code, password: reg.password, nickname: reg.nickname.trim(), campusId: reg.campusId }))
    ElMessage.success('注册成功，已完成学生认证')
    done()
  } finally {
    loading.value = false
  }
}

async function doReset() {
  if (!reset.prefix || !reset.code || !reset.password) return ElMessage.warning('请把信息填写完整')
  if (!PWD_RULE.test(reset.password)) return ElMessage.warning('密码需 8–20 位，且同时包含字母和数字')
  loading.value = true
  try {
    await authApi.resetPassword(email(reset.prefix), reset.code, reset.password)
    ElMessage.success('密码已重置，请用新密码登录')
    login.prefix = reset.prefix
    tab.value = 'login'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <aside class="auth-side">
      <RouterLink class="logo" to="/"><span class="logo-mark"><AppIcon name="package" /></span>WU 校园闲置</RouterLink>
      <div>
        <h1>只属于 WU 同学的<br />二手交易平台</h1>
        <ul>
          <li><AppIcon name="shield" />校园邮箱注册，人人实名在校</li>
          <li><AppIcon name="pin" />两校区就近面交</li>
          <li><AppIcon name="wallet" />担保交易，确认收货后才打款</li>
        </ul>
      </div>
      <p class="side-foot">遇到问题请联系平台管理员</p>
    </aside>

    <main class="auth-main">
      <div class="auth-box card card-pad">
        <div class="tabs" role="tablist">
          <button class="tab" :class="{ active: tab === 'login' }" role="tab" @click="tab = 'login'">登录</button>
          <button class="tab" :class="{ active: tab === 'register' }" role="tab" @click="tab = 'register'">注册</button>
          <button class="tab" :class="{ active: tab === 'reset' }" role="tab" @click="tab = 'reset'">找回密码</button>
        </div>

        <form v-if="tab === 'login'" class="stack mt-24" @submit.prevent="doLogin">
          <div class="field"><label for="l-email">校园邮箱</label>
            <div class="suffix"><input id="l-email" v-model="login.prefix" placeholder="邮箱前缀" autocomplete="username" /><span>{{ SUFFIX }}</span></div></div>
          <div class="field"><label for="l-pwd">密码</label>
            <input id="l-pwd" v-model="login.password" class="input" :class="{ 'is-error': login.error }" type="password" placeholder="请输入密码" autocomplete="current-password" />
            <span v-if="login.error" class="error" role="alert">{{ login.error }}</span></div>
          <button class="btn btn-primary btn-lg btn-block" type="submit" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
          <p class="subtle" style="text-align: center">连续输错 5 次将锁定 15 分钟</p>
        </form>

        <form v-else-if="tab === 'register'" class="stack mt-24" @submit.prevent="doRegister">
          <div class="field"><label for="r-email">校园邮箱<span class="req">*</span></label>
            <div class="suffix"><input id="r-email" v-model="reg.prefix" placeholder="邮箱前缀" autocomplete="username" /><span>{{ SUFFIX }}</span></div>
            <span class="hint">只支持 {{ SUFFIX }} 邮箱，验证通过即完成学生认证</span></div>
          <div class="field"><label for="r-code">邮箱验证码<span class="req">*</span></label>
            <div class="input-group"><input id="r-code" v-model="reg.code" class="input" placeholder="6 位验证码" maxlength="6" inputmode="numeric" />
              <button class="btn btn-outline" type="button" :disabled="countdown > 0" @click="sendCode(reg.prefix, 'REGISTER')">{{ countdown > 0 ? `${countdown} 秒后重发` : '获取验证码' }}</button></div></div>
          <div class="field"><label for="r-nick">昵称<span class="req">*</span></label>
            <input id="r-nick" v-model="reg.nickname" class="input" maxlength="16" placeholder="2–16 个字" /></div>
          <div class="field"><label>所在校区<span class="req">*</span></label>
            <div class="radio-group">
              <button v-for="c in meta.campuses" :key="c.id" type="button" class="radio-card" :class="{ active: reg.campusId === c.id }" @click="reg.campusId = c.id">{{ c.name }}</button>
            </div></div>
          <div class="field"><label for="r-pwd">设置密码<span class="req">*</span></label>
            <input id="r-pwd" v-model="reg.password" class="input" :class="{ 'is-error': regPwdError }" type="password" placeholder="8–20 位，含字母和数字" autocomplete="new-password" />
            <span v-if="regPwdError" class="error">{{ regPwdError }}</span></div>
          <button class="btn btn-primary btn-lg btn-block" type="submit" :disabled="loading">注册并登录</button>
        </form>

        <form v-else class="stack mt-24" @submit.prevent="doReset">
          <div class="field"><label for="f-email">校园邮箱</label>
            <div class="suffix"><input id="f-email" v-model="reset.prefix" placeholder="邮箱前缀" /><span>{{ SUFFIX }}</span></div></div>
          <div class="field"><label for="f-code">邮箱验证码</label>
            <div class="input-group"><input id="f-code" v-model="reset.code" class="input" placeholder="6 位验证码" maxlength="6" />
              <button class="btn btn-outline" type="button" :disabled="countdown > 0" @click="sendCode(reset.prefix, 'RESET_PASSWORD')">{{ countdown > 0 ? `${countdown} 秒后重发` : '获取验证码' }}</button></div></div>
          <div class="field"><label for="f-pwd">新密码</label>
            <input id="f-pwd" v-model="reset.password" class="input" type="password" placeholder="8–20 位，含字母和数字" autocomplete="new-password" /></div>
          <button class="btn btn-primary btn-lg btn-block" type="submit" :disabled="loading">重置密码</button>
        </form>
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth { min-height: 100vh; display: grid; grid-template-columns: 1fr 1fr; }
.auth-side { background: linear-gradient(150deg, #047857, #10B981); color: #fff; padding: 48px; display: flex; flex-direction: column; justify-content: space-between; }
.auth-side .logo { color: #fff; }
.auth-side .logo-mark { background: #fff; color: var(--primary); }
.auth-side h1 { font-size: 34px; margin-bottom: 16px; }
.auth-side ul { list-style: none; padding: 0; margin: 0; opacity: .95; }
.auth-side li { display: flex; gap: 10px; align-items: center; margin-top: 12px; }
.side-foot { opacity: .8; font-size: 13px; }
.auth-main { display: grid; place-items: center; padding: 32px 16px; min-width: 0; }
.auth-box { width: 100%; max-width: 400px; }
.suffix { display: flex; align-items: center; border: 1px solid var(--border); border-radius: var(--radius); background: #fff; overflow: hidden; }
.suffix:focus-within { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(4,120,87,.15); }
.suffix input { flex: 1; border: 0; outline: 0; height: 44px; padding: 0 14px; min-width: 0; }
.suffix span { padding: 0 14px; color: var(--text-2); background: #F8FAFC; height: 44px; display: grid; place-items: center; border-left: 1px solid var(--border); }
@media (max-width: 768px) { .auth { grid-template-columns: 1fr; } .auth-side { display: none; } }
</style>
