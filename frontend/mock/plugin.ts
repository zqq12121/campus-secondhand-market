/**
 * Vite 开发服务器里的 Mock 后端：按接口文档的路径、统一响应格式和错误码返回数据。
 * 只在 VITE_USE_MOCK=true 时启用（见 vite.config.ts），生产构建不会包含。
 */
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Plugin } from 'vite'
import { MockError, userById } from './db'
import { registerUserRoutes } from './user'
import { registerAdminRoutes } from './admin'

export type Auth = 'public' | 'optional' | 'user' | 'admin'
export interface Ctx {
  params: Record<string, string>
  query: Record<string, string | string[]>
  body: Record<string, any>
  /** 当前登录用户 ID（用户端） */
  uid: string
  /** 当前管理员 ID（后台） */
  aid: string
  file?: { type: string; buf: Buffer }
}
type Handler = (ctx: Ctx) => unknown
interface Route { method: string; re: RegExp; keys: string[]; handler: Handler; auth: Auth }

const routes: Route[] = []
export function route(method: string, path: string, auth: Auth, handler: Handler) {
  const keys: string[] = []
  const re = new RegExp('^' + path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)' }) + '$')
  routes.push({ method, re, keys, handler, auth })
}

/** 上传的文件存在内存里，通过 /mock-files/<id> 访问 */
export const files = new Map<string, { type: string; buf: Buffer }>()

registerUserRoutes()
registerAdminRoutes()

function send(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(body))
}

function readBody(req: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    req.on('data', (c: Buffer) => chunks.push(c))
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

/** 从 multipart/form-data 里取出 file 字段和普通字段 */
function parseMultipart(buf: Buffer, contentType: string) {
  const boundary = '--' + (contentType.split('boundary=')[1] ?? '')
  const fields: Record<string, string> = {}
  let file: { type: string; buf: Buffer } | undefined
  let start = buf.indexOf(boundary)
  while (start >= 0) {
    const next = buf.indexOf(boundary, start + boundary.length)
    if (next < 0) break
    const part = buf.subarray(start + boundary.length + 2, next - 2)
    const sep = part.indexOf('\r\n\r\n')
    const head = part.subarray(0, sep).toString()
    const content = part.subarray(sep + 4)
    const name = /name="([^"]+)"/.exec(head)?.[1] ?? ''
    if (/filename="/.test(head)) file = { type: /Content-Type: (\S+)/i.exec(head)?.[1] ?? 'application/octet-stream', buf: Buffer.from(content) }
    else fields[name] = content.toString()
    start = next
  }
  return { fields, file }
}

function placeholderSvg(text: string, tint: number) {
  const colors = [['#ECFDF5', '#047857'], ['#EFF6FF', '#1D4ED8'], ['#FFF7ED', '#C2410C'], ['#F5F3FF', '#6D28D9'], ['#FDF2F8', '#BE185D'], ['#F0FDFA', '#0F766E']]
  const [bg, fg] = colors[(tint - 1 + 6) % 6]
  const safe = text.replace(/[<>&"]/g, '')
  return `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 600 600"><rect width="600" height="600" fill="${bg}"/><rect x="230" y="190" width="140" height="140" rx="20" fill="none" stroke="${fg}" stroke-width="10" opacity=".7"/><circle cx="270" cy="235" r="14" fill="${fg}" opacity=".7"/><path d="M240 320l45-50 30 30 25-20 25 40" fill="none" stroke="${fg}" stroke-width="10" opacity=".7"/><text x="300" y="400" font-size="40" font-family="sans-serif" text-anchor="middle" fill="${fg}">${safe}</text></svg>`
}

export function mockPlugin(): Plugin {
  return {
    name: 'campus-trade-mock',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost')
        const path = url.pathname

        if (path.startsWith('/mock-img/')) {
          const m = /^\/mock-img\/(.+)-(\d)\.svg$/.exec(path)
          res.setHeader('Content-Type', 'image/svg+xml')
          return res.end(placeholderSvg(decodeURIComponent(m?.[1] ?? '图片'), Number(m?.[2] ?? 1)))
        }
        if (path.startsWith('/mock-files/')) {
          const f = files.get(path.slice('/mock-files/'.length))
          if (!f) { res.statusCode = 404; return res.end() }
          res.setHeader('Content-Type', f.type)
          return res.end(f.buf)
        }

        const isUser = path.startsWith('/api/v1/')
        const isAdmin = path.startsWith('/api/admin/v1/')
        if (!isUser && !isAdmin) return next()

        const sub = path.slice(isUser ? '/api/v1'.length : '/api/admin/v1'.length)
        const method = (req.method ?? 'GET').toUpperCase()
        const prefix = isUser ? 'U ' : 'A '
        const r = routes.find((x) => x.method === prefix + method && x.re.test(sub))
        if (!r) return send(res, 404, { code: 404, message: `Mock 未实现：${method} ${path}`, data: null })

        const query: Record<string, string | string[]> = {}
        url.searchParams.forEach((v, k) => {
          const old = query[k]
          query[k] = old === undefined ? v : ([] as string[]).concat(old, v)
        })
        const raw = await readBody(req)
        const ct = req.headers['content-type'] ?? ''
        let body: Record<string, any> = {}
        let file: Ctx['file']
        if (ct.includes('multipart/form-data')) {
          const mp = parseMultipart(raw, ct)
          body = mp.fields
          file = mp.file
        } else if (raw.length) {
          try { body = JSON.parse(raw.toString()) } catch { body = {} }
        }

        // 鉴权：token 形如 mock-user.<id> / mock-admin.<id>
        const token = (req.headers.authorization ?? '').replace(/^Bearer /, '')
        const uid = isUser && token.startsWith('mock-user.') ? token.slice(10) : ''
        const aid = isAdmin && token.startsWith('mock-admin.') ? token.slice(11) : ''
        if (r.auth === 'user' && !uid) return send(res, 401, { code: 401, message: '未登录或登录已过期', data: null })
        if (r.auth === 'admin' && !aid) return send(res, 401, { code: 401, message: '未登录或登录已过期', data: null })
        if (uid && userById(uid)?.status === 2) return send(res, 403, { code: 1009, message: '账号已封禁', data: null })

        const params: Record<string, string> = {}
        const match = r.re.exec(sub)!
        r.keys.forEach((k, i) => (params[k] = decodeURIComponent(match[i + 1])))

        // 模拟网络延迟，便于看到加载状态
        await new Promise((ok) => setTimeout(ok, 120))
        try {
          const data = await r.handler({ params, query, body, uid, aid, file })
          if (typeof data === 'string' && data.startsWith('<!doctype html>')) {
            res.setHeader('Content-Type', 'text/html; charset=utf-8')
            return res.end(data)
          }
          send(res, 200, { code: 0, message: 'ok', data: data ?? null, traceId: Math.random().toString(16).slice(2, 12) })
        } catch (e) {
          if (e instanceof MockError) return send(res, e.http, { code: e.code, message: e.message, data: e.data })
          console.error('[mock]', e)
          send(res, 500, { code: 500, message: '服务器内部错误（Mock）', data: null })
        }
      })
    },
  }
}
