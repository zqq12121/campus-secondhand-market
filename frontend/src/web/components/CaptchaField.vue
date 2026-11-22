<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { authApi } from '../api'

/** 图形验证码输入框：点图片换一张。验证码校验一次即作废，提交后父组件需调用 refresh()。 */
const props = defineProps<{ inputId: string }>()
const code = defineModel<string>({ required: true })
const captchaId = ref('')
const image = ref('')
const loading = ref(false)

async function refresh() {
  loading.value = true
  try {
    const c = await authApi.captcha()
    captchaId.value = c.captchaId
    image.value = c.image
    code.value = ''
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
defineExpose({ refresh, captchaId })
</script>

<template>
  <div class="field">
    <label :for="props.inputId">图形验证码<span class="req">*</span></label>
    <div class="input-group">
      <input :id="props.inputId" v-model="code" class="input" placeholder="右图中的 4 个字符" maxlength="4" autocomplete="off" />
      <button class="captcha" type="button" :disabled="loading" aria-label="看不清，换一张" title="看不清，换一张" @click="refresh">
        <img v-if="image" :src="image" alt="图形验证码" width="120" height="40" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.captcha { flex: none; width: 120px; height: 44px; padding: 2px 0; border: 1px solid var(--border); border-radius: var(--radius); background: #fff; cursor: pointer; display: grid; place-items: center; }
.captcha img { display: block; }
</style>
