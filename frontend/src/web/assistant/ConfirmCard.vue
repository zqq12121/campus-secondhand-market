<script setup lang="ts">
import { computed, reactive } from 'vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { CONDITION } from '@/shared/enums'
import type { ConfirmCard } from '../stores/assistant'

/** 写操作确认卡片：求购帖直接发布，发布草稿确认后去发布页。可以先改几个关键字段再确认。 */
const props = defineProps<{ card: ConfirmCard; disabled: boolean }>()
const emit = defineEmits<{ decide: ['confirm' | 'cancel', object | undefined] }>()

const VALID_DAYS = [3, 7, 15, 30]
const isWant = computed(() => props.card.kind === 'want_post')
const form = reactive(props.card.kind === 'want_post'
  ? { title: props.card.draft.title, priceMin: props.card.draft.priceMin ?? '', priceMax: props.card.draft.priceMax ?? '',
      validDays: props.card.draft.validDays, price: '' as number | string }
  : { title: props.card.draft.title, priceMin: '', priceMax: '', validDays: 7, price: props.card.draft.price as number | string })

const STATE_TEXT: Record<string, string> = { confirmed: '已确认', cancelled: '已取消', expired: '未确认，已失效' }

function num(v: number | string): number | null {
  return v === '' || v == null ? null : Number(v)
}

/** 只提交改动过的字段 */
function edits(): object | undefined {
  const c = props.card
  const changed: Record<string, unknown> = {}
  if (form.title.trim() !== c.draft.title) changed.title = form.title.trim()
  if (c.kind === 'want_post') {
    if (num(form.priceMin) !== c.draft.priceMin) changed.priceMin = num(form.priceMin)
    if (num(form.priceMax) !== c.draft.priceMax) changed.priceMax = num(form.priceMax)
    if (form.validDays !== c.draft.validDays) changed.validDays = form.validDays
  } else if (num(form.price) !== c.draft.price) {
    changed.price = num(form.price)
  }
  return Object.keys(changed).length ? changed : undefined
}
</script>

<template>
  <section class="confirm" :class="card.state">
    <header class="confirm-head">
      <AppIcon :name="isWant ? 'megaphone' : 'package'" size="sm" />
      <strong>{{ isWant ? '发布求购前请确认' : '发布草稿' }}</strong>
      <span v-if="card.state !== 'pending'" class="tag" :class="card.state === 'confirmed' ? 'tag-green' : ''">{{ STATE_TEXT[card.state] }}</span>
    </header>

    <div class="confirm-body">
      <label class="f"><span>标题</span>
        <input v-model="form.title" class="input" maxlength="30" :disabled="card.state !== 'pending'" /></label>

      <template v-if="card.kind === 'want_post'">
        <div class="f2">
          <label class="f"><span>预算下限（元）</span>
            <input v-model="form.priceMin" class="input" type="number" min="0" :disabled="card.state !== 'pending'" /></label>
          <label class="f"><span>预算上限（元）</span>
            <input v-model="form.priceMax" class="input" type="number" min="0" :disabled="card.state !== 'pending'" /></label>
        </div>
        <label class="f"><span>有效期</span>
          <select v-model="form.validDays" class="select" :disabled="card.state !== 'pending'">
            <option v-for="d in VALID_DAYS" :key="d" :value="d">{{ d }} 天</option>
          </select></label>
        <dl class="meta">
          <dt>分类</dt><dd>{{ card.draft.categoryName }}</dd>
          <dt>校区</dt><dd>{{ card.draft.campusName }}</dd>
          <template v-if="card.draft.description"><dt>说明</dt><dd>{{ card.draft.description }}</dd></template>
        </dl>
      </template>

      <template v-else>
        <div v-if="card.draft.images.length" class="imgs">
          <img v-for="u in card.draft.images" :key="u" :src="u" alt="商品图片" />
        </div>
        <label class="f"><span>售价（元）</span>
          <input v-model="form.price" class="input" type="number" min="0" :disabled="card.state !== 'pending'" /></label>
        <p class="subtle">{{ card.draft.priceBasis }}<template v-if="card.draft.priceRange">，参考区间 ¥{{ card.draft.priceRange[0] }}–{{ card.draft.priceRange[1] }}</template></p>
        <dl class="meta">
          <dt>分类</dt><dd>{{ card.draft.categoryName }}</dd>
          <dt>成色</dt><dd>{{ CONDITION[card.draft.conditionLevel] }}</dd>
          <dt>描述</dt><dd>{{ card.draft.description }}</dd>
        </dl>
      </template>
    </div>

    <footer v-if="card.state === 'pending'" class="confirm-foot">
      <button type="button" class="btn btn-outline btn-sm" :disabled="disabled" @click="emit('decide', 'cancel', undefined)">取消</button>
      <button type="button" class="btn btn-primary btn-sm" :disabled="disabled || !form.title.trim()" @click="emit('decide', 'confirm', edits())">
        {{ isWant ? '确认发布求购' : '确认，去发布页' }}
      </button>
    </footer>
  </section>
</template>

<style scoped>
.confirm { border: 1px solid var(--primary-border); border-radius: 12px; background: #fff; overflow: hidden; transition: opacity .2s; }
.confirm.cancelled, .confirm.expired { opacity: .65; }
.confirm-head { display: flex; align-items: center; gap: 6px; padding: 10px 12px; background: var(--primary-soft); color: var(--primary); font-size: 14px; }
.confirm-head .tag { margin-left: auto; }
.confirm-body { padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.f { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-2); }
.f .input, .f .select { height: 36px; font-size: 14px; }
.f2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.meta { display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; margin: 0; font-size: 13px; }
.meta dt { color: var(--text-3); }
.meta dd { margin: 0; color: var(--text); white-space: pre-wrap; }
.imgs { display: flex; gap: 6px; }
.imgs img { width: 64px; height: 64px; object-fit: cover; border-radius: 8px; border: 1px solid var(--border); }
.confirm-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 10px 12px; border-top: 1px solid var(--border); }
</style>
