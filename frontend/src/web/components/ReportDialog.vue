<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ImageUploader from '@/shared/components/ImageUploader.vue'
import { REPORT_REASON, REPORT_TARGET } from '@/shared/enums'
import { commonApi, reportApi } from '../api'

/** 举报弹窗（接口文档 12.1） */
const props = defineProps<{ targetType: 1 | 2 | 3; targetId: string }>()
const visible = defineModel<boolean>({ required: true })

const form = reactive({ reasonType: 1, description: '', evidenceUrls: [] as string[] })
const submitting = ref(false)
const upload = (f: File) => commonApi.upload(f, 'EVIDENCE')

async function submit() {
  if (form.reasonType === 5 && !form.description.trim()) return ElMessage.warning('选择「其他」时请填写说明')
  submitting.value = true
  try {
    await reportApi.submit({ targetType: props.targetType, targetId: props.targetId, ...form })
    ElMessage.success('举报已提交，处理结果会通过通知告诉你')
    visible.value = false
    Object.assign(form, { reasonType: 1, description: '', evidenceUrls: [] })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`举报${REPORT_TARGET[targetType]}`" width="min(520px, 92vw)">
    <div class="stack">
      <div class="field">
        <label>举报原因<span class="req">*</span></label>
        <div class="radio-group">
          <button v-for="(t, k) in REPORT_REASON" :key="k" type="button" class="radio-card"
            :class="{ active: form.reasonType === Number(k) }" @click="form.reasonType = Number(k)">{{ t }}</button>
        </div>
      </div>
      <div class="field">
        <label for="rp-desc">补充说明</label>
        <el-input id="rp-desc" v-model="form.description" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="描述你遇到的问题" />
      </div>
      <div class="field">
        <label>截图凭证（最多 6 张）</label>
        <ImageUploader v-model="form.evidenceUrls" :uploader="upload" :max="6" />
      </div>
      <p class="subtle">同一对象 24 小时内只能举报一次，处理结果会通过站内通知告诉你。</p>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交举报</el-button>
    </template>
  </el-dialog>
</template>
