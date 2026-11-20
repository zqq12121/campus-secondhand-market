<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import { priceText } from '@/shared/format'
import { GOODS_STATUS } from '@/shared/enums'
import type { GoodsItem } from '@/shared/types'

const props = defineProps<{ goods: GoodsItem & { status?: number } }>()
const free = computed(() => Number(props.goods.price) === 0)
/** 收藏列表里已下架 / 已售出的商品置灰 */
const inactive = computed(() => props.goods.status != null && props.goods.status !== 2)
</script>

<template>
  <RouterLink class="goods-card" :class="{ inactive }" :to="`/goods/${goods.id}`">
    <div class="goods-cover">
      <CoverImage :src="goods.coverUrl" :alt="goods.title" :tint="(Number(goods.id) % 6) + 1" />
      <div class="badge-pos">
        <span v-if="goods.tradeMode !== 1" class="tag tag-green"><AppIcon name="shield" size="sm" />担保</span>
        <span v-if="free" class="tag tag-orange">免费送</span>
        <span v-if="inactive" class="tag">{{ GOODS_STATUS[goods.status!] }}</span>
      </div>
    </div>
    <div class="goods-body">
      <div class="goods-title">{{ goods.title }}</div>
      <div class="row">
        <span class="price"><small v-if="!free">¥</small>{{ priceText(goods.price) }}</span>
        <span v-if="goods.originalPrice && Number(goods.originalPrice) > 0" class="strike">¥{{ priceText(goods.originalPrice) }}</span>
      </div>
      <div class="goods-meta">
        <span class="seller"><UserAvatar size="sm" :name="goods.seller.nickname" :src="goods.seller.avatarUrl" /><span>{{ goods.seller.nickname }}</span></span>
        <span>{{ goods.campusName }}</span>
      </div>
    </div>
  </RouterLink>
</template>

<style scoped>
.goods-cover { overflow: hidden; }
.inactive { opacity: .6; }
</style>
