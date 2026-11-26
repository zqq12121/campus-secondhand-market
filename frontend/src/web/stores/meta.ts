import { defineStore } from 'pinia'
import { ref } from 'vue'
import { commonApi } from '../api'
import type { Campus, Category, SiteConfig } from '@/shared/types'

/** 站点配置、校区、分类等基础数据，首次使用时加载一次 */
export const useMetaStore = defineStore('meta', () => {
  const campuses = ref<Campus[]>([])
  const categories = ref<Category[]>([])
  // 加载前按未开放处理，避免先闪出担保选项
  const config = ref<SiteConfig>({ schoolName: '', emailSuffix: '', guaranteeEnabled: false })
  let loading: Promise<void> | null = null

  function load() {
    loading ??= Promise.all([commonApi.campuses(), commonApi.categories(), commonApi.config()]).then(([c, cat, cfg]) => {
      config.value = cfg
      campuses.value = c
      categories.value = cat
    }).catch((e) => { loading = null; throw e })
    return loading
  }

  function categoryName(id: string | null | undefined): string {
    for (const c of categories.value) {
      if (c.id === id) return c.name
      const child = c.children?.find((x) => x.id === id)
      if (child) return child.name
    }
    return ''
  }

  return { campuses, categories, config, load, categoryName }
})
