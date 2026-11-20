/**
 * axios 封装：统一解包 { code, message, data }、带 token、按接口文档 1.3 处理 401/403/429。
 * 用户端与后台各创建一个实例（token 存储键、前缀、未登录跳转都不同）。
 */
import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from './types'

export class ApiError extends Error {
  constructor(public code: number, message: string, public data: unknown = null) {
    super(message)
  }
}

export interface RequestOptions {
  /** true：出错时不自动弹提示，由调用方自己处理 */
  silent?: boolean
}

interface HttpConfig {
  baseURL: string
  tokenKey: string
  onUnauthorized: () => void
}

export function createHttp({ baseURL, tokenKey, onUnauthorized }: HttpConfig) {
  const instance = axios.create({
    baseURL,
    timeout: 15000,
    // 数组参数按接口文档写成重复键：conditionLevel=1&conditionLevel=2
    paramsSerializer: { indexes: null },
  })

  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem(tokenKey)
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })

  async function request<T>(config: AxiosRequestConfig, opts: RequestOptions = {}): Promise<T> {
    try {
      const res = await instance.request<ApiResult<T>>(config)
      const body = res.data
      if (body.code !== 0) throw new ApiError(body.code, body.message, body.data)
      return body.data
    } catch (e) {
      const err = toApiError(e)
      if (err.code === 401) {
        localStorage.removeItem(tokenKey)
        onUnauthorized()
      }
      if (!opts.silent) ElMessage.error(err.message)
      throw err
    }
  }

  return {
    get: <T>(url: string, params?: object, opts?: RequestOptions) => request<T>({ url, method: 'get', params }, opts),
    post: <T>(url: string, data?: unknown, opts?: RequestOptions) => request<T>({ url, method: 'post', data }, opts),
    put: <T>(url: string, data?: unknown, opts?: RequestOptions) => request<T>({ url, method: 'put', data }, opts),
    del: <T>(url: string, params?: object, opts?: RequestOptions) => request<T>({ url, method: 'delete', params }, opts),
    upload: <T>(url: string, form: FormData, opts?: RequestOptions) => request<T>({ url, method: 'post', data: form }, opts),
  }
}

function toApiError(e: unknown): ApiError {
  if (e instanceof ApiError) return e
  if (e instanceof AxiosError) {
    const status = e.response?.status
    const body = e.response?.data as Partial<ApiResult<{ retryAfter?: number }>> | undefined
    if (status === 401) return new ApiError(401, '登录已过期，请重新登录')
    if (status === 403) return new ApiError(403, body?.message || '没有权限执行此操作', body?.data)
    if (status === 429) {
      const wait = body?.data?.retryAfter
      return new ApiError(429, wait ? `操作太频繁，请 ${wait} 秒后再试` : '操作太频繁，请稍后再试', body?.data)
    }
    if (body?.code != null) return new ApiError(body.code, body.message || '请求失败', body.data)
    if (e.code === 'ECONNABORTED') return new ApiError(-1, '请求超时，请检查网络')
    return new ApiError(status ?? -1, status ? `服务器错误（${status}）` : '网络异常，请稍后再试')
  }
  return new ApiError(-1, (e as Error)?.message || '未知错误')
}
