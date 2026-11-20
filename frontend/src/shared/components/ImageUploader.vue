<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import AppIcon from './AppIcon.vue'

/**
 * 图片上传（接口文档 2.3）：选择后立即上传，v-model 为已上传的 URL 数组，第一张为封面。
 * 上传函数由调用方传入（用户端 / 后台各自的 http 实例）。
 */
const props = withDefaults(defineProps<{
  modelValue: string[]
  uploader: (file: File) => Promise<{ url: string }>
  max?: number
  coverLabel?: boolean
}>(), { max: 9, coverLabel: false })
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const uploading = ref(0)
const input = ref<HTMLInputElement>()
const ALLOWED = ['image/jpeg', 'image/png', 'image/webp']
const MAX_SIZE = 5 * 1024 * 1024

async function onPick(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? [])
  ;(e.target as HTMLInputElement).value = ''
  const room = props.max - props.modelValue.length - uploading.value
  if (files.length > room) ElMessage.warning(`最多 ${props.max} 张，已忽略多出的 ${files.length - room} 张`)
  const urls = [...props.modelValue]
  for (const f of files.slice(0, Math.max(room, 0))) {
    if (!ALLOWED.includes(f.type)) { ElMessage.error(`${f.name}：只支持 jpg / png / webp`); continue }
    if (f.size > MAX_SIZE) { ElMessage.error(`${f.name}：超过 5MB`); continue }
    uploading.value++
    try {
      const { url } = await props.uploader(f)
      urls.push(url)
      emit('update:modelValue', [...urls])
    } finally {
      uploading.value--
    }
  }
}

function remove(i: number) {
  const urls = [...props.modelValue]
  urls.splice(i, 1)
  emit('update:modelValue', urls)
}

function setCover(i: number) {
  if (i === 0) return
  const urls = [...props.modelValue]
  const [u] = urls.splice(i, 1)
  emit('update:modelValue', [u, ...urls])
}
</script>

<template>
  <div class="upload-grid">
    <div v-for="(u, i) in modelValue" :key="u" class="upload-slot filled thumb-box">
      <img :src="u" alt="" />
      <span v-if="coverLabel && i === 0" class="cover-tag">封面</span>
      <button v-else-if="coverLabel" type="button" class="set-cover" @click="setCover(i)">设为封面</button>
      <button type="button" class="del" :aria-label="`删除第 ${i + 1} 张`" @click="remove(i)"><AppIcon name="x" size="sm" /></button>
    </div>
    <div v-for="n in uploading" :key="`u${n}`" class="upload-slot"><span>上传中…</span></div>
    <button v-if="modelValue.length + uploading < max" type="button" class="upload-slot" @click="input?.click()">
      <span><AppIcon name="upload" /><br />添加图片<br />{{ modelValue.length }}/{{ max }}</span>
    </button>
    <input ref="input" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="onPick" />
  </div>
</template>

<style scoped>
.upload-slot { background: #fff; }
.thumb-box { overflow: hidden; }
.thumb-box img { width: 100%; height: 100%; object-fit: cover; border-radius: 12px; }
.del { position: absolute; top: 4px; right: 4px; width: 24px; height: 24px; border-radius: 50%; border: 0; background: rgba(15,23,42,.6); color: #fff; display: grid; place-items: center; cursor: pointer; }
.set-cover { position: absolute; left: 0; right: 0; bottom: 0; border: 0; background: rgba(15,23,42,.55); color: #fff; font-size: 12px; padding: 2px; cursor: pointer; border-radius: 0 0 12px 12px; }
</style>
