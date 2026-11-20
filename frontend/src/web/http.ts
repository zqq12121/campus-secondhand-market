import { createHttp } from '@/shared/request'

export const USER_TOKEN_KEY = 'ct_user_token'

export const http = createHttp({
  baseURL: '/api/v1',
  tokenKey: USER_TOKEN_KEY,
  onUnauthorized: () => {
    // 动态引入避免循环依赖
    import('./router').then(({ default: router }) => {
      const current = router.currentRoute.value
      if (current.name !== 'login') router.replace({ name: 'login', query: { redirect: current.fullPath } })
    })
  },
})
